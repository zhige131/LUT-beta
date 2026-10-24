package com.lutbeta.app.core

import android.content.Context
import android.content.SharedPreferences

data class AppSettings(
    val ftpPort: Int = 2121,
    val ftpUser: String = "lutbeta",
    val ftpPassword: String = "a7c2",
    val quality: Int = 95,
    /** 0 means keep the original resolution. */
    val maxDimension: Int = 0,
    val keepOriginal: Boolean = false,
    val watchGallery: Boolean = false,
    val sonyOnly: Boolean = true,
    val selectedLutId: String? = null,
    val lutStrength: Float = 1f,
    val serviceEnabled: Boolean = false,
    /** 0 = 灰片, 1 = 灰片1 */
    val previewBaseIndex: Int = 0,
    /** 0 = 手机热点 FTP, 1 = 同一 Wi-Fi FTP, 2 = 相机 Wi-Fi Direct + PTP/IP */
    val cameraMode: Int = 0,
    val ptpSsid: String = "",
    val ptpCameraId: String = "",
    val ptpModel: String = ""
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lut_beta_settings", Context.MODE_PRIVATE)

    fun get(): AppSettings = AppSettings(
        ftpPort = prefs.getInt("ftp_port", 2121),
        ftpUser = prefs.getString("ftp_user", "lutbeta") ?: "lutbeta",
        ftpPassword = prefs.getString("ftp_password", "a7c2") ?: "a7c2",
        quality = prefs.getInt("quality", 95),
        maxDimension = prefs.getInt("max_dimension", 0),
        keepOriginal = prefs.getBoolean("keep_original", false),
        watchGallery = prefs.getBoolean("watch_gallery", false),
        sonyOnly = prefs.getBoolean("sony_only", true),
        selectedLutId = prefs.getString("selected_lut", null),
        lutStrength = prefs.getFloat("lut_strength", 1f),
        serviceEnabled = prefs.getBoolean("service_enabled", false),
        previewBaseIndex = prefs.getInt("preview_base", 0).coerceIn(0, 1),
        cameraMode = prefs.getInt("camera_mode", 0).coerceIn(0, 2),
        ptpSsid = prefs.getString("ptp_ssid", "") ?: "",
        ptpCameraId = prefs.getString("ptp_camera_id", "") ?: "",
        ptpModel = prefs.getString("ptp_model", "") ?: ""
    )

    fun update(block: (AppSettings) -> AppSettings): AppSettings {
        val old = get()
        val new = block(old)
        prefs.edit()
            .putInt("ftp_port", new.ftpPort)
            .putString("ftp_user", new.ftpUser)
            .putString("ftp_password", new.ftpPassword)
            .putInt("quality", new.quality)
            .putInt("max_dimension", new.maxDimension)
            .putBoolean("keep_original", new.keepOriginal)
            .putBoolean("watch_gallery", new.watchGallery)
            .putBoolean("sony_only", new.sonyOnly)
            .putString("selected_lut", new.selectedLutId)
            .putFloat("lut_strength", new.lutStrength)
            .putBoolean("service_enabled", new.serviceEnabled)
            .putInt("preview_base", new.previewBaseIndex)
            .putInt("camera_mode", new.cameraMode)
            .putString("ptp_ssid", new.ptpSsid)
            .putString("ptp_camera_id", new.ptpCameraId)
            .putString("ptp_model", new.ptpModel)
            .apply()
        return new
    }
}
