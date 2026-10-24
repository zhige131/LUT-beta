package com.lutbeta.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreSaver {
    const val ALBUM = "Pictures/LUT Beta"
    const val ORIGINAL_ALBUM = "Pictures/LUT Beta/Originals"

    fun saveJpeg(
        context: Context,
        file: File,
        displayName: String,
        relativePath: String = ALBUM,
        dateTakenMillis: Long? = null
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (dateTakenMillis != null && dateTakenMillis > 0) {
                put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMillis)
            }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun externalAlbumExists(): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LUT Beta")
        return dir.exists()
    }
}
