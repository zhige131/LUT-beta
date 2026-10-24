package com.lutbeta.app.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A parsed 3D .cube LUT. Data is stored red-fastest:
 * index = ((blue * size + green) * size + red) * 3
 */
class CubeLut(
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f)
) {
    init {
        require(size in 2..129) { "LUT 尺寸超出范围：$size" }
        require(data.size == size * size * size * 3) { "LUT 数据量不匹配" }
    }

    fun sample(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val n = size
        val last = n - 1
        val tr = ((r - domainMin[0]) / span(domainMax[0], domainMin[0])).coerceIn(0f, 1f) * last
        val tg = ((g - domainMin[1]) / span(domainMax[1], domainMin[1])).coerceIn(0f, 1f) * last
        val tb = ((b - domainMin[2]) / span(domainMax[2], domainMin[2])).coerceIn(0f, 1f) * last

        val r0 = floor(tr).toInt().coerceIn(0, last)
        val g0 = floor(tg).toInt().coerceIn(0, last)
        val b0 = floor(tb).toInt().coerceIn(0, last)
        val r1 = min(r0 + 1, last)
        val g1 = min(g0 + 1, last)
        val b1 = min(b0 + 1, last)
        val fr = tr - r0
        val fg = tg - g0
        val fb = tb - b0

        var rr = 0f
        var gg = 0f
        var bb = 0f

        for (db in 0..1) {
            val wb = if (db == 0) 1f - fb else fb
            val bi = if (db == 0) b0 else b1
            if (wb == 0f) continue
            for (dg in 0..1) {
                val wg = if (dg == 0) 1f - fg else fg
                val gi = if (dg == 0) g0 else g1
                if (wg == 0f) continue
                for (dr in 0..1) {
                    val wr = if (dr == 0) 1f - fr else fr
                    val ri = if (dr == 0) r0 else r1
                    if (wr == 0f) continue
                    val w = wb * wg * wr
                    val idx = ((bi * n + gi) * n + ri) * 3
                    rr += data[idx] * w
                    gg += data[idx + 1] * w
                    bb += data[idx + 2] * w
                }
            }
        }
        out[0] = rr
        out[1] = gg
        out[2] = bb
        return out
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(size)
            for (i in 0..2) out.writeFloat(domainMin[i])
            for (i in 0..2) out.writeFloat(domainMax[i])
            out.writeInt(data.size)
            for (v in data) out.writeFloat(v)
        }
    }

    companion object {
        private const val MAGIC = 0x4C555442 // LUTB
        private const val VERSION = 1

        fun readFrom(file: File): CubeLut {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC) { "不是有效的 LUT beta 缓存" }
                val version = input.readInt()
                require(version == VERSION) { "不支持的 LUT 缓存版本：$version" }
                val size = input.readInt()
                val dMin = FloatArray(3) { input.readFloat() }
                val dMax = FloatArray(3) { input.readFloat() }
                val count = input.readInt()
                require(count in 0..(129 * 129 * 129 * 3)) { "LUT 缓存损坏" }
                val data = FloatArray(count) { input.readFloat() }
                return CubeLut(size, data, dMin, dMax)
            }
        }

        fun parse(file: File): CubeLut = file.bufferedReader().use { reader ->
            var size = -1
            var oneDSize = -1
            var sawThreeD = false
            var inputRange: Pair<Float, Float>? = null
            val dMin = floatArrayOf(0f, 0f, 0f)
            val dMax = floatArrayOf(1f, 1f, 1f)
            val values = FloatBuilder(33 * 33 * 33 * 3)
            var lineNo = 0

            while (true) {
                val raw = reader.readLine() ?: break
                lineNo++
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase()
                when {
                    upper.startsWith("TITLE") -> Unit
                    upper.startsWith("LUT_3D_SIZE") -> {
                        size = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull()
                            ?: throw LutParseException("第 $lineNo 行：LUT_3D_SIZE 无效")
                        if (size !in 2..129) throw LutParseException("不支持的 LUT 尺寸：$size（支持 2–129）")
                        sawThreeD = true
                    }
                    upper.startsWith("LUT_1D_SIZE") -> {
                        oneDSize = line.substringAfter("LUT_1D_SIZE").trim().toIntOrNull() ?: -1
                    }
                    upper.startsWith("DOMAIN_MIN") -> parseTriple(line, "DOMAIN_MIN", dMin, lineNo)
                    upper.startsWith("DOMAIN_MAX") -> parseTriple(line, "DOMAIN_MAX", dMax, lineNo)
                    upper.startsWith("LUT_3D_INPUT_RANGE") || upper.startsWith("LUT_1D_INPUT_RANGE") -> {
                        val parts = line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.drop(1)
                        if (parts.size >= 2) {
                            val lo = parts[0].toFloatOrNull()
                            val hi = parts[1].toFloatOrNull()
                            if (lo != null && hi != null && lo.isFinite() && hi.isFinite() && hi > lo) {
                                inputRange = lo to hi
                            }
                        }
                    }
                    else -> {
                        val parts = line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                        if (parts.size >= 3) {
                            for (i in 0..2) {
                                val v = parts[i].toFloatOrNull()
                                    ?: throw LutParseException("第 $lineNo 行：无法解析数值“${parts[i]}”")
                                if (!v.isFinite()) {
                                    throw LutParseException("第 $lineNo 行：数值不是有限数")
                                }
                                values.add(v)
                            }
                        }
                    }
                }
            }

            if (!sawThreeD || size <= 0) {
                if (oneDSize > 0) {
                    throw LutParseException("这是 1D LUT，LUT beta 需要 3D .cube（含 LUT_3D_SIZE）")
                }
                throw LutParseException("没有找到 LUT_3D_SIZE，可能不是 3D .cube 文件")
            }
            if (oneDSize > 0) {
                throw LutParseException("检测到 1D+3D 复合 LUT，暂不支持；请导出为纯 3D .cube")
            }
            val expected = size * size * size * 3
            if (values.size != expected) {
                throw LutParseException("数据量不正确：需要 $expected 个数值，实际 ${values.size} 个")
            }
            inputRange?.let { range ->
                for (i in 0..2) {
                    dMin[i] = range.first
                    dMax[i] = range.second
                }
            }
            for (i in 0..2) {
                if (dMax[i] <= dMin[i]) throw LutParseException("DOMAIN_MAX 必须大于 DOMAIN_MIN")
            }
            return CubeLut(size, values.toArray(), dMin, dMax)
        }

        private fun parseTriple(line: String, key: String, target: FloatArray, lineNo: Int) {
            val parts = line.substringAfter(key).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 3) throw LutParseException("第 $lineNo 行：$key 需要 3 个数值")
            for (i in 0..2) {
                target[i] = parts[i].toFloatOrNull()
                    ?: throw LutParseException("第 $lineNo 行：$key 数值无效")
            }
        }

        private fun span(maxV: Float, minV: Float): Float = max(1e-6f, maxV - minV)

        private class FloatBuilder(initialCapacity: Int) {
            private var data = FloatArray(initialCapacity.coerceAtLeast(16))
            var size = 0
                private set

            fun add(value: Float) {
                if (size == data.size) data = data.copyOf(data.size * 2)
                data[size++] = value
            }

            fun toArray(): FloatArray = data.copyOf(size)
        }
    }
}

class LutParseException(message: String) : Exception(message)
