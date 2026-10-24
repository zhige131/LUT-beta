package com.lutbeta.app

import com.lutbeta.app.core.CubeLut
import com.lutbeta.app.core.LutParseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CubeLutTest {
    private fun writeCube(text: String): File {
        val file = Files.createTempFile("lutbeta", ".cube").toFile()
        file.writeText(text)
        return file
    }

    @Test
    fun parsesIdentityCube() {
        val text = buildString {
            appendLine("TITLE \"identity\"")
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0 0 0")
            appendLine("DOMAIN_MAX 1 1 1")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                appendLine("$r $g $b")
            }
        }
        val lut = CubeLut.parse(writeCube(text))
        assertEquals(2, lut.size)
        assertEquals(24, lut.data.size)
        val out = lut.sample(0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 0.0001f)
        val mid = lut.sample(0.25f, 0.5f, 0.75f)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), mid, 0.0001f)
    }

    @Test
    fun supportsDomainRange() {
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0 0 0")
            appendLine("DOMAIN_MAX 2 2 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                appendLine("$r $g $b")
            }
        }
        val lut = CubeLut.parse(writeCube(text))
        val out = lut.sample(1f, 1f, 1f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), out, 0.0001f)
    }

    @Test
    fun rejectsWrongDataCount() {
        val text = "LUT_3D_SIZE 2\n0 0 0\n1 1 1\n"
        val error = assertThrows(LutParseException::class.java) {
            CubeLut.parse(writeCube(text))
        }
        assertEquals(true, error.message!!.contains("数据量不正确"))
    }

    @Test
    fun rejectsOneDimensionalLut() {
        val text = "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n"
        assertThrows(LutParseException::class.java) {
            CubeLut.parse(writeCube(text))
        }
    }

    @Test
    fun binaryRoundTrip() {
        val values = FloatArray(2 * 2 * 2 * 3) { it / 10f }
        val lut = CubeLut(2, values)
        val file = Files.createTempFile("lutbeta", ".lut").toFile()
        lut.writeTo(file)
        val loaded = CubeLut.readFrom(file)
        assertEquals(2, loaded.size)
        assertArrayEquals(values, loaded.data, 0f)
    }
}
