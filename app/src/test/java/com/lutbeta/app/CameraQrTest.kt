package com.lutbeta.app

import com.lutbeta.app.core.CameraQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraQrTest {
    @Test
    fun parsesA7cIIW01Payload() {
        val raw = """
            W01:
            S: DIRECT-j7U1:ILCE-7CM2
            P: zKabmS47
            C: ILCE-7CM2
            M: 3490EAFAF701
        """.trimIndent()

        val info = CameraQrParser.parse(raw)
        requireNotNull(info)
        assertEquals("DIRECT-j7U1:ILCE-7CM2", info.ssid)
        assertEquals("zKabmS47", info.password)
        assertEquals("ILCE-7CM2", info.model)
        assertEquals("3490EAFAF701", info.cameraId)
    }

    @Test
    fun parsesStandardWifiQr() {
        val info = CameraQrParser.parse(
            "WIFI:T:WPA;S:DIRECT-j7U1:ILCE-7CM2;P:zKabmS47;;"
        )
        requireNotNull(info)
        assertEquals("DIRECT-j7U1:ILCE-7CM2", info.ssid)
        assertEquals("zKabmS47", info.password)
    }

    @Test
    fun rejectsMacQrAndUnrelatedText() {
        assertNull(CameraQrParser.parse("W01:\nM: 3490EAFAF701"))
        assertNull(CameraQrParser.parse("https://example.com/not-a-camera"))
        assertNull(CameraQrParser.parse("WIFI:T:WPA;S:HomeWiFi;P:secret;;"))
    }
}
