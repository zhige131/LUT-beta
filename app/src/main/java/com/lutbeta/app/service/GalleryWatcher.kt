package com.lutbeta.app.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional fallback path: watches MediaStore for newly added camera photos,
 * for people who prefer Sony's Creators' App / Transfer & Tagging auto transfer.
 */
class GalleryWatcher(
    private val context: Context,
    private val incomingDir: File,
    private val sonyOnly: Boolean,
    private val scope: CoroutineScope,
    private val onNewPhoto: (File) -> Unit
) {
    private val resolver = context.contentResolver
    private var observer: ContentObserver? = null
    private var scanJob: Job? = null
    private val busy = AtomicBoolean(false)
    private var lastId: Long = 0

    fun start() {
        if (observer != null) return
        if (!hasPermission()) return
        lastId = currentMaxId()
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleScan()
            }
        }
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        observer = contentObserver
    }

    fun stop() {
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
        observer = null
        scanJob?.cancel()
        scanJob = null
    }

    private fun scheduleScan() {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            delay(900)
            scan()
        }
    }

    private fun scan() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Images.Media._ID} > ?"
            resolver.query(collection, projection, selection, arrayOf(lastId.toString()), "${MediaStore.Images.Media._ID} ASC")
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        lastId = maxOf(lastId, id)
                        val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                        val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                        val relative = cursor.getString(pathCol) ?: ""
                        if (!mime.startsWith("image/")) continue
                        if (relative.contains("LUT Beta", ignoreCase = true)) continue
                        if (!isSupported(name)) continue
                        val uri = ContentUris.withAppendedId(collection, id)
                        importOne(uri, id, name)
                    }
                }
        } catch (_: Exception) {
        } finally {
            busy.set(false)
        }
    }

    private fun importOne(uri: Uri, id: Long, displayName: String) {
        try {
            if (sonyOnly && !isSonyCamera(uri)) return
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(incomingDir, "gallery_${id}_$safeName")
            if (target.exists()) return
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return
            onNewPhoto(target)
        } catch (_: Exception) {
        }
    }

    private fun isSonyCamera(uri: Uri): Boolean {
        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
                val sony = make.contains("SONY", ignoreCase = true)
                val a7c2 = model.contains("7CM2", ignoreCase = true) ||
                    model.contains("7C II", ignoreCase = true) ||
                    model.contains("A7C2", ignoreCase = true) ||
                    model.contains("ILCE-7C", ignoreCase = true)
                sony && a7c2
            } ?: false
        }.getOrDefault(false)
    }

    private fun currentMaxId(): Long {
        return runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun hasPermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSupported(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".heic") || lower.endsWith(".heif")
    }
}
