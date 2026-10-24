package com.lutbeta.app

import com.lutbeta.app.ptp.PtpIpClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Protocol-level test against a local PTP/IP mock. It does not pretend to be a
 * camera; it only verifies that the client emits/parses the real PTP/IP
 * handshake and Sony SDIO content-transfer commands correctly.
 */
class PtpIpClientTest {

    @Test
    fun handshakeAndContentTransferStorageQuery() {
        val failure = AtomicReference<Throwable?>(null)
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(name = "ptp-mock") {
            try {
                val command = server.accept()
                val commandIn = DataInputStream(command.getInputStream())
                val commandOut = DataOutputStream(command.getOutputStream())
                val init = readPacket(commandIn)
                check(init.first == 1) { "expected InitCommandRequest" }
                val friendly = "LUT beta".toByteArray(Charsets.UTF_16LE)
                val ack = ByteBuffer.allocate(4 + 16 + friendly.size + 2 + 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(7) // connection number
                    .put(ByteArray(16))
                    .put(friendly)
                    .putShort(0)
                    .putInt(0x00010001)
                    .array()
                sendPacket(commandOut, 2, ack)

                val event = server.accept()
                val eventIn = DataInputStream(event.getInputStream())
                val eventOut = DataOutputStream(event.getOutputStream())
                val eventInit = readPacket(eventIn)
                check(eventInit.first == 3) { "expected InitEventRequest" }
                sendPacket(eventOut, 4, ByteArray(0))

                while (true) {
                    val packet = readPacket(commandIn)
                    if (packet.first != 6) continue
                    val payload = packet.second
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    bb.int // data phase
                    val opcode = bb.short.toInt() and 0xFFFF
                    val tid = bb.int
                    val response = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(0x2001)
                        .putInt(tid)
                        .array()
                    sendPacket(commandOut, 7, response)
                    when (opcode) {
                        0x9201, 0x9202 -> {
                            val data = if (opcode == 0x9202) {
                                byteArrayOf(0x2C, 0x01) // SDIO v3
                            } else {
                                byteArrayOf(0x00)
                            }
                            sendPacket(
                                commandOut,
                                9,
                                ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .putLong(data.size.toLong())
                                    .array()
                            )
                            sendPacket(
                                commandOut,
                                10,
                                ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .put(data)
                                    .array()
                            )
                            sendPacket(
                                commandOut,
                                12,
                                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .array()
                            )
                        }
                        0x1004 -> {
                            val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(1)
                                .putInt(0x00010001)
                                .array()
                            sendPacket(
                                commandOut,
                                9,
                                ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .putLong(data.size.toLong())
                                    .array()
                            )
                            sendPacket(
                                commandOut,
                                10,
                                ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .put(data)
                                    .array()
                            )
                            sendPacket(
                                commandOut,
                                12,
                                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(tid)
                                    .array()
                            )
                        }
                    }
                }
            } catch (_: EOFException) {
                // normal client close
            } catch (e: Exception) {
                failure.set(e)
            } finally {
                runCatching { server.close() }
            }
        }

        val client = PtpIpClient(
            host = "127.0.0.1",
            network = null,
            guidSeed = "unit-test",
            port = server.localPort
        )
        try {
            client.open(3000)
            client.sdioOpenSession()
            client.sdioAuthenticate()
            client.setContentsTransferMode(true)
            assertArrayEquals(longArrayOf(0x00010001L), client.getStorageIds())
        } finally {
            client.close()
            server.close()
            serverThread.join(3_000)
        }
        failure.get()?.let { throw AssertionError("mock server failed", it) }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(8)
        input.readFully(header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        val type = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val payload = ByteArray(length - 8)
        if (payload.isNotEmpty()) input.readFully(payload)
        return type to payload
    }

    private fun sendPacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeIntLE(8 + payload.size)
        out.writeIntLE(type)
        out.write(payload)
        out.flush()
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
