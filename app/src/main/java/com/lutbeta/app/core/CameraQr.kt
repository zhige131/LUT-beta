package com.lutbeta.app.core

/**
 * The A7C II "Wi-Fi Direct Info" QR is not the MAC-address QR.
 *
 * The camera prints a small, line-oriented payload:
 *
 * W01:
 * S: DIRECT-j7U1:ILCE-7CM2
 * P: zKabmS47
 * C: ILCE-7CM2
 * M: 3490EAFAF701
 *
 * S = SSID, P = password, C = model, M = camera id.
 * Some cameras/apps also emit a standard WiFi QR. Both are accepted, but the
 * W01 form is what the A7C II screen in this project was verified against.
 */
data class CameraQrInfo(
    val ssid: String,
    val password: String,
    val model: String?,
    val cameraId: String?,
    val raw: String
)

object CameraQrParser {

    fun parse(rawText: String): CameraQrInfo? {
        val raw = rawText.trim()
        if (raw.isBlank()) return null

        return parseW01(raw) ?: parseStandardWifi(raw)
    }

    private fun parseW01(raw: String): CameraQrInfo? {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null

        // Header can be W01: or W01. The first line must not be a field.
        val header = lines.first().removeSuffix(":").trim()
        if (!header.equals("W01", ignoreCase = true)) return null

        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().uppercase()
            // SSID itself contains a colon ("DIRECT-j7U1:ILCE-7CM2"), so only
            // split at the first colon and keep the rest verbatim.
            val value = line.substring(separator + 1).trim()
            if (key.length == 1 && value.isNotEmpty()) fields[key] = value
        }

        val ssid = fields["S"] ?: return null
        val password = fields["P"] ?: return null
        if (ssid.isBlank() || password.isBlank()) return null
        return CameraQrInfo(
            ssid = ssid,
            password = password,
            model = fields["C"]?.takeIf { it.isNotBlank() },
            cameraId = fields["M"]?.takeIf { it.isNotBlank() },
            raw = raw
        )
    }

    private fun parseStandardWifi(raw: String): CameraQrInfo? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val body = raw.substringAfter(':', "")
        val fields = linkedMapOf<String, String>()
        val current = StringBuilder()
        var escaped = false
        val parts = mutableListOf<String>()
        for (ch in body) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == ';' -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        for (part in parts) {
            val separator = part.indexOf(':')
            if (separator <= 0) continue
            val key = part.substring(0, separator).uppercase()
            fields[key] = part.substring(separator + 1)
        }
        val ssid = fields["S"] ?: return null
        val password = fields["P"] ?: return null
        if (ssid.isBlank() || password.isBlank()) return null
        // DIRECT-xxxx is the A7C II Wi-Fi Direct prefix. Reject an unrelated
        // Wi-Fi QR so we do not silently connect to the wrong network.
        if (!ssid.startsWith("DIRECT-", ignoreCase = true)) return null
        return CameraQrInfo(
            ssid = ssid,
            password = password,
            model = null,
            cameraId = null,
            raw = raw
        )
    }
}
