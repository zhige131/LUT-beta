package com.lutbeta.app.ptp

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A real PTP/IP client for the Sony A7C II (and other PTP cameras).
 *
 * It is deliberately not FTP and not HTTP: after the phone joins the camera's
 * Wi-Fi Direct AP, PTP/IP is a TCP connection to the camera on port 15740.
 * The client performs the PTP/IP initialization handshake, opens a PTP session,
 * then uses standard PTP object operations (GetStorageIDs, GetObjectHandles,
 * GetObjectInfo, GetObject). Sony-specific response codes are surfaced with
 * their actual numeric code instead of being replaced by fake data.
 */
class PtpIpClient(
    private val host: String,
    private val network: Network?,
    private val guidSeed: String,
    private val onEvent: (PtpEvent) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val port: Int = PORT
) : Closeable {

    companion object {
        const val PORT = 15740
        const val OP_GET_DEVICE_INFO = 0x1001
        const val OP_OPEN_SESSION = 0x1002
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009

        // Sony SDIO / Camera Remote Command extensions.
        const val OP_SDIO_CONNECT = 0x9201
        const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        const val OP_SDIO_OPEN_SESSION = 0x9210
        const val OP_SDIO_SET_CONTENTS_TRANSFER_MODE = 0x9212
        const val SDIO_VERSION_V3 = 0x012C
        const val SDIO_FUNCTION_CONTENT_TRANSFER = 1

        const val RC_OK = 0x2001
        const val RC_STORE_NOT_AVAILABLE = 0x2013
        const val EVENT_OBJECT_ADDED = 0x4002
        const val ALL_STORAGE_ID = 0xFFFFFFFFL

        private const val MAX_PACKET = 64 * 1024 * 1024
        private const val PKT_INIT_COMMAND_REQUEST = 1
        private const val PKT_INIT_COMMAND_ACK = 2
        private const val PKT_INIT_EVENT_REQUEST = 3
        private const val PKT_INIT_EVENT_ACK = 4
        private const val PKT_INIT_FAIL = 5
        private const val PKT_OPERATION_REQUEST = 6
        private const val PKT_OPERATION_RESPONSE = 7
        private const val PKT_EVENT = 8
        private const val PKT_START_DATA = 9
        private const val PKT_DATA = 10
        private const val PKT_END_DATA = 12
        private const val PKT_PROBE_REQUEST = 13
        private const val PKT_PROBE_RESPONSE = 14

        fun leInt(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
        }

        fun leShort(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    data class PtpEvent(val code: Int, val transactionId: Long, val params: LongArray)
    data class PtpResult(val code: Int, val params: LongArray, val data: ByteArray)
    data class PtpObjectInfo(
        val handle: Long,
        val storageId: Long,
        val format: Int,
        val size: Long,
        val width: Long,
        val height: Long,
        val fileName: String
    )

    class PtpException(
        message: String,
        val operation: Int? = null,
        val responseCode: Int? = null
    ) : IOException(message)

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandIn: DataInputStream? = null
    private var commandOut: OutputStream? = null
    private var eventIn: DataInputStream? = null
    private var eventOut: OutputStream? = null
    private var eventThread: Thread? = null

    @Volatile
    private var closed = false
    private var transactionId = 1L
    private var connectionNumber = 0L

    val isOpen: Boolean get() = commandSocket?.isConnected == true && !closed

    fun open(timeoutMs: Int = 5000) {
        if (isOpen) return
        try {
            val command = Socket()
            network?.bindSocket(command)
            command.soTimeout = timeoutMs
            command.tcpNoDelay = true
            command.connect(InetSocketAddress(host, port), timeoutMs)
            commandSocket = command
            commandIn = DataInputStream(command.getInputStream().buffered())
            commandOut = command.getOutputStream().buffered()

            sendInitCommandRequest()
            val ack = readPacket(commandIn!!)
            when (ack.type) {
                PKT_INIT_COMMAND_ACK -> {
                    if (ack.payload.size < 4) throw PtpException("PTP/IP 初始化应答过短")
                    connectionNumber = leInt(ack.payload, 0).toLong() and 0xFFFFFFFFL
                    onLog("PTP/IP 握手成功，connection=$connectionNumber")
                }
                PKT_INIT_FAIL -> {
                    val reason = if (ack.payload.size >= 4) leInt(ack.payload, 0) else -1
                    throw PtpException("相机拒绝 PTP/IP 初始化（reason=$reason）")
                }
                else -> throw PtpException("PTP/IP 初始化返回了未知包类型 ${ack.type}")
            }

            val event = Socket()
            network?.bindSocket(event)
            event.soTimeout = timeoutMs
            event.tcpNoDelay = true
            event.connect(InetSocketAddress(host, port), timeoutMs)
            eventSocket = event
            eventIn = DataInputStream(event.getInputStream().buffered())
            eventOut = event.getOutputStream().buffered()

            val eventRequest = ByteArrayOutputStream().apply {
                writeLeInt(connectionNumber.toInt())
            }.toByteArray()
            sendPacket(eventOut!!, PKT_INIT_EVENT_REQUEST, eventRequest)
            val eventAck = readPacket(eventIn!!)
            if (eventAck.type == PKT_INIT_FAIL) {
                throw PtpException("相机拒绝了 PTP/IP 事件连接")
            }
            if (eventAck.type != PKT_INIT_EVENT_ACK) {
                throw PtpException("PTP/IP 事件连接返回了未知包类型 ${eventAck.type}")
            }
            event.soTimeout = 0
            startEventReader(eventIn!!)
        } catch (e: Exception) {
            close()
            if (e is PtpException) throw e
            throw PtpException("无法连接相机 PTP/IP ${host}:${PORT}：${e.message}")
        }
    }

    fun openSession(sessionId: Long = 1L): PtpResult {
        val r = execute(OP_OPEN_SESSION, longArrayOf(sessionId), expectData = false)
        if (r.code != RC_OK) throw PtpException("OpenSession 失败 0x${hex(r.code)}", OP_OPEN_SESSION, r.code)
        return r
    }

    /**
     * Sony's content-transfer session. Standard PTP OpenSession puts the camera
     * in Remote Control Mode, where GetStorageIDs can return 0x2013
     * StoreNotAvailable. 0x9210 with FunctionMode=1 opens Content Transfer Mode.
     */
    fun sdioOpenSession(
        sessionId: Long = 1L,
        functionMode: Long = SDIO_FUNCTION_CONTENT_TRANSFER.toLong()
    ) {
        val r = execute(
            OP_SDIO_OPEN_SESSION,
            longArrayOf(sessionId, functionMode),
            expectData = false
        )
        if (r.code != RC_OK) {
            throw PtpException(
                "SDIO_OpenSession(functionMode=$functionMode) 返回 0x${hex(r.code)}",
                OP_SDIO_OPEN_SESSION,
                r.code
            )
        }
    }

    /** Sony SDIO authentication: Connect phases 1/2, GetExtDeviceInfo, Connect 3. */
    fun sdioAuthenticate(version: Int = SDIO_VERSION_V3) {
        sdioConnect(1)
        sdioConnect(2)
        var negotiated = 0
        for (attempt in 0 until 20) {
            val r = execute(
                OP_SDIO_GET_EXT_DEVICE_INFO,
                longArrayOf(version.toLong()),
                expectData = true
            )
            if (r.code != RC_OK) {
                throw PtpException(
                    "SDIO_GetExtDeviceInfo 返回 0x${hex(r.code)}",
                    OP_SDIO_GET_EXT_DEVICE_INFO,
                    r.code
                )
            }
            if (r.data.size >= 2) negotiated = leShort(r.data, 0)
            if (negotiated == version) break
            Thread.sleep(50)
        }
        if (negotiated != version) {
            throw PtpException(
                "Sony SDIO 版本协商失败：期望 0x${hex(version)}，相机返回 0x${hex(negotiated)}"
            )
        }
        sdioConnect(3)
    }

    private fun sdioConnect(phase: Int) {
        val r = execute(
            OP_SDIO_CONNECT,
            longArrayOf(phase.toLong(), 0L, 0L),
            expectData = true
        )
        if (r.code != RC_OK) {
            throw PtpException(
                "SDIO_Connect($phase) 返回 0x${hex(r.code)}",
                OP_SDIO_CONNECT,
                r.code
            )
        }
    }

    /**
     * Enable Content Transfer Mode. Parameters are from Sony's reference:
     * SELECT_ON_REMOTE_DEVICE=2, MODE_ON=1, ADD_INFO_NONE=0.
     */
    fun setContentsTransferMode(on: Boolean) {
        val r = execute(
            OP_SDIO_SET_CONTENTS_TRANSFER_MODE,
            longArrayOf(2L, if (on) 1L else 0L, 0L),
            expectData = false
        )
        if (r.code != RC_OK) {
            throw PtpException(
                "SDIO_SetContentsTransferMode(${if (on) "on" else "off"}) 返回 0x${hex(r.code)}",
                OP_SDIO_SET_CONTENTS_TRANSFER_MODE,
                r.code
            )
        }
    }

    fun getStorageIds(): LongArray {
        val r = execute(OP_GET_STORAGE_IDS, expectData = true)
        if (r.code != RC_OK) {
            throw PtpException(
                "GetStorageIDs 返回 0x${hex(r.code)} ${responseName(r.code)}",
                OP_GET_STORAGE_IDS,
                r.code
            )
        }
        val data = r.data
        if (data.size < 4) throw PtpException("GetStorageIDs 数据为空")
        val count = leInt(data, 0)
        val result = LongArray(count.coerceAtLeast(0))
        for (i in result.indices) {
            val off = 4 + i * 4
            if (off + 4 > data.size) break
            result[i] = leInt(data, off).toLong() and 0xFFFFFFFFL
        }
        return result
    }

    fun getObjectHandles(storageId: Long, format: Int = 0, parent: Long = 0L): LongArray {
        val r = execute(
            OP_GET_OBJECT_HANDLES,
            longArrayOf(storageId and 0xFFFFFFFFL, format.toLong(), parent and 0xFFFFFFFFL),
            expectData = true
        )
        if (r.code != RC_OK) {
            throw PtpException(
                "GetObjectHandles(${storageId}) 返回 0x${hex(r.code)} ${responseName(r.code)}",
                OP_GET_OBJECT_HANDLES,
                r.code
            )
        }
        val data = r.data
        if (data.size < 4) return LongArray(0)
        val count = leInt(data, 0)
        val result = LongArray(count.coerceAtLeast(0))
        for (i in result.indices) {
            val off = 4 + i * 4
            if (off + 4 > data.size) break
            result[i] = leInt(data, off).toLong() and 0xFFFFFFFFL
        }
        return result
    }

    fun getObjectInfo(handle: Long): PtpObjectInfo {
        val r = execute(OP_GET_OBJECT_INFO, longArrayOf(handle), expectData = true)
        if (r.code != RC_OK) {
            throw PtpException(
                "GetObjectInfo(${handle}) 返回 0x${hex(r.code)}",
                OP_GET_OBJECT_INFO,
                r.code
            )
        }
        return parseObjectInfo(handle, r.data)
    }

    fun getObject(handle: Long): ByteArray {
        val r = execute(OP_GET_OBJECT, longArrayOf(handle), expectData = true)
        if (r.code != RC_OK) {
            throw PtpException(
                "GetObject(${handle}) 返回 0x${hex(r.code)}",
                OP_GET_OBJECT,
                r.code
            )
        }
        return r.data
    }

    fun getDeviceInfo(): ByteArray {
        val r = execute(OP_GET_DEVICE_INFO, expectData = true)
        if (r.code != RC_OK) throw PtpException("GetDeviceInfo 返回 0x${hex(r.code)}")
        return r.data
    }

    @Synchronized
    fun execute(operation: Int, params: LongArray = LongArray(0), expectData: Boolean): PtpResult {
        val input = commandIn ?: throw PtpException("PTP/IP 未连接")
        val output = commandOut ?: throw PtpException("PTP/IP 未连接")
        val tid = transactionId++
        val request = ByteArrayOutputStream().apply {
            writeLeInt(1) // no data-out phase
            writeLeShort(operation)
            writeLeInt(tid.toInt())
            for (p in params.take(5)) writeLeInt(p.toInt())
        }.toByteArray()
        sendPacket(output, PKT_OPERATION_REQUEST, request)

        // PTP/IP cameras may send the data phase before or after the operation
        // response. Collect both and only finish when both are present.
        val dataOut = ByteArrayOutputStream()
        var responseCode = -1
        var responseParams = LongArray(0)
        var responseSeen = false
        var dataComplete = false
        var dataSeen = false

        while (true) {
            val packet = readPacket(input)
            when (packet.type) {
                PKT_OPERATION_RESPONSE -> {
                    if (packet.payload.size < 6) throw PtpException("PTP 操作应答过短")
                    val code = leShort(packet.payload, 0)
                    val responseTid = leInt(packet.payload, 2).toLong() and 0xFFFFFFFFL
                    if (responseTid != tid) {
                        onLog("忽略旧事务应答 tid=$responseTid expected=$tid")
                        continue
                    }
                    responseCode = code
                    responseParams = readParams(packet.payload, 6)
                    responseSeen = true
                    if (code != RC_OK) {
                        return PtpResult(code, responseParams, ByteArray(0))
                    }
                    if (!expectData) {
                        return PtpResult(code, responseParams, ByteArray(0))
                    }
                    if (dataComplete) {
                        return PtpResult(code, responseParams, dataOut.toByteArray())
                    }
                }
                PKT_START_DATA -> {
                    dataSeen = true
                    if (packet.payload.size < 12) throw PtpException("StartData 包过短")
                    val dataTid = leInt(packet.payload, 0).toLong() and 0xFFFFFFFFL
                    if (dataTid != tid) onLog("StartData tid=$dataTid expected=$tid")
                }
                PKT_DATA, PKT_END_DATA -> {
                    dataSeen = true
                    if (packet.payload.size < 4) throw PtpException("Data 包过短")
                    val dataTid = leInt(packet.payload, 0).toLong() and 0xFFFFFFFFL
                    if (dataTid != tid) onLog("Data tid=$dataTid expected=$tid")
                    val length = packet.payload.size - 4
                    if (length > 0) {
                        if (dataOut.size() + length > MAX_PACKET) {
                            throw PtpException("PTP 数据超过 ${MAX_PACKET / 1024 / 1024} MB 限制")
                        }
                        dataOut.write(packet.payload, 4, length)
                    }
                    if (packet.type == PKT_END_DATA) {
                        dataComplete = true
                        if (responseSeen) {
                            return PtpResult(responseCode, responseParams, dataOut.toByteArray())
                        }
                    }
                }
                PKT_EVENT -> dispatchEvent(packet.payload)
                PKT_PROBE_REQUEST -> sendProbeResponse()
                else -> {
                    if (!dataSeen) throw PtpException("PTP 操作收到未知包类型 ${packet.type}")
                    onLog("操作阶段忽略包类型 ${packet.type}")
                }
            }
        }
    }

    private fun parseObjectInfo(handle: Long, data: ByteArray): PtpObjectInfo {
        if (data.size < 52) throw PtpException("ObjectInfo 数据过短")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val storageId = bb.int.toLong() and 0xFFFFFFFFL
        val format = bb.short.toInt() and 0xFFFF
        bb.short // protection
        val size = bb.int.toLong() and 0xFFFFFFFFL
        bb.short // thumb format
        bb.int // thumb size
        bb.int // thumb width
        bb.int // thumb height
        val width = bb.int.toLong() and 0xFFFFFFFFL
        val height = bb.int.toLong() and 0xFFFFFFFFL
        bb.int // bit depth
        bb.int // parent
        bb.short // association type
        bb.int // association desc
        bb.int // sequence
        val fileName = readPtpString(bb)
        return PtpObjectInfo(handle, storageId, format, size, width, height, fileName)
    }

    private fun readPtpString(bb: ByteBuffer): String {
        if (bb.remaining() < 1) return ""
        val count = bb.get().toInt() and 0xFF
        if (count == 0) return ""
        val chars = count - 1
        val sb = StringBuilder(chars)
        for (i in 0 until chars) {
            if (bb.remaining() < 2) break
            sb.append(bb.short.toInt().toChar())
        }
        return sb.toString()
    }

    private fun startEventReader(input: DataInputStream) {
        val thread = Thread({
            try {
                while (!closed) {
                    val packet = readPacket(input)
                    when (packet.type) {
                        PKT_EVENT -> dispatchEvent(packet.payload)
                        PKT_PROBE_REQUEST -> sendProbeResponse()
                        else -> onLog("事件连接收到包类型 ${packet.type}")
                    }
                }
            } catch (e: Exception) {
                if (!closed) onLog("PTP/IP 事件连接结束：${e.message}")
            }
        }, "ptp-event")
        thread.isDaemon = true
        eventThread = thread
        thread.start()
    }

    private fun dispatchEvent(payload: ByteArray) {
        if (payload.size < 6) return
        val code = leShort(payload, 0)
        val tid = leInt(payload, 2).toLong() and 0xFFFFFFFFL
        onEvent(PtpEvent(code, tid, readParams(payload, 6)))
    }

    private fun sendProbeResponse() {
        runCatching {
            sendPacket(eventOut ?: return, PKT_PROBE_RESPONSE, ByteArray(0))
        }
    }

    private fun sendInitCommandRequest() {
        val guid = guidBytes(guidSeed)
        val payload = ByteArrayOutputStream().apply {
            write(guid)
            writePtpString(this, "LUT beta Android")
            writeLeInt(0x00010001)
        }.toByteArray()
        sendPacket(commandOut!!, PKT_INIT_COMMAND_REQUEST, payload)
    }

    private fun sendPacket(output: OutputStream, type: Int, payload: ByteArray) {
        val size = 8 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size)
            .putInt(type)
            .array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream): RawPacket {
        val header = ByteArray(8)
        input.readFully(header)
        val length = leInt(header, 0)
        if (length < 8 || length > MAX_PACKET) throw PtpException("非法 PTP/IP 包长度 $length")
        val type = leInt(header, 4)
        val payload = ByteArray(length - 8)
        if (payload.isNotEmpty()) input.readFully(payload)
        return RawPacket(type, payload)
    }

    override fun close() {
        closed = true
        runCatching { eventSocket?.close() }
        runCatching { commandSocket?.close() }
        eventThread?.interrupt()
        eventSocket = null
        commandSocket = null
        commandIn = null
        commandOut = null
        eventIn = null
        eventOut = null
    }

    private data class RawPacket(val type: Int, val payload: ByteArray)

    private fun guidBytes(seed: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return digest.copyOf(16)
    }

    private fun writePtpString(out: ByteArrayOutputStream, value: String) {
        val chars = value.take(39)
        out.write(chars.length + 1)
        for (ch in chars) {
            out.write(ch.code and 0xFF)
            out.write((ch.code ushr 8) and 0xFF)
        }
        out.write(0)
        out.write(0)
    }

    private fun readParams(payload: ByteArray, offset: Int): LongArray {
        val count = (payload.size - offset).coerceAtLeast(0) / 4
        val params = LongArray(count)
        for (i in 0 until count) {
            params[i] = leInt(payload, offset + i * 4).toLong() and 0xFFFFFFFFL
        }
        return params
    }

    private fun responseName(code: Int): String = when (code) {
        0x2001 -> "OK"
        0x2002 -> "GeneralError"
        0x2003 -> "SessionNotOpen"
        0x2004 -> "InvalidTransactionID"
        0x2005 -> "OperationNotSupported"
        0x2006 -> "ParameterNotSupported"
        0x2007 -> "IncompleteTransfer"
        0x2009 -> "InvalidStorageID"
        0x2013 -> "StoreNotAvailable"
        0x2019 -> "DeviceBusy"
        else -> "PTPResponse"
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(4, '0')
}
