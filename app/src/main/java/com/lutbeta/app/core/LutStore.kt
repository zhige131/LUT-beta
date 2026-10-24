package com.lutbeta.app.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LutInfo(
    val id: String,
    val name: String,
    val originalFileName: String,
    val gridSize: Int,
    val importedAt: Long,
    val previewPath: String,
    val cubePath: String,
    val lutPath: String,
    val previewPathB: String = previewPath
) {
    fun previewFor(index: Int): String = if (index == 1) previewPathB else previewPath
}

class LutStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("lut_library", Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "luts").apply { mkdirs() }
    private val previews = File(root, "previews").apply { mkdirs() }

    private var cachedId: String? = null
    private var cachedLut: CubeLut? = null

    fun lutDir(): File = root

    fun list(): List<LutInfo> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                LutInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    originalFileName = o.optString("originalFileName", ""),
                    gridSize = o.optInt("gridSize", 0),
                    importedAt = o.optLong("importedAt", 0L),
                    previewPath = o.optString("previewPath", ""),
                    cubePath = o.optString("cubePath", ""),
                    lutPath = o.optString("lutPath", ""),
                    previewPathB = o.optString("previewPathB").takeIf { it.isNotBlank() }
                        ?: o.optString("previewPath", "")
                )
            }.filter { File(it.lutPath).exists() }.sortedByDescending { it.importedAt }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): LutInfo? {
        if (id == null) return null
        return list().firstOrNull { it.id == id }
    }

    fun load(id: String): CubeLut? {
        if (cachedId == id) return cachedLut
        val info = get(id) ?: return null
        val lut = runCatching { CubeLut.readFrom(File(info.lutPath)) }.getOrNull() ?: return null
        cachedId = id
        cachedLut = lut
        return lut
    }

    @Throws(Exception::class)
    fun import(resolver: ContentResolver, uri: Uri): LutInfo {
        val id = UUID.randomUUID().toString()
        val displayName = queryDisplayName(resolver, uri) ?: "LUT_${System.currentTimeMillis()}.cube"
        val cubeFile = File(root, "$id.cube")
        resolver.openInputStream(uri)?.use { input ->
            cubeFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw LutParseException("无法读取所选文件")
        return finishImport(id, displayName, cubeFile)
    }

    @Throws(Exception::class)
    fun importAsset(assetName: String, displayName: String): LutInfo {
        val id = UUID.randomUUID().toString()
        val cubeFile = File(root, "$id.cube")
        appContext.assets.open(assetName).use { input ->
            cubeFile.outputStream().use { output -> input.copyTo(output) }
        }
        return finishImport(id, displayName, cubeFile)
    }

    @Throws(Exception::class)
    private fun finishImport(id: String, displayName: String, cubeFile: File): LutInfo {
        if (cubeFile.length() > 80L * 1024 * 1024) {
            cubeFile.delete()
            throw LutParseException("LUT 文件超过 80 MB")
        }

        val lut = try {
            CubeLut.parse(cubeFile)
        } catch (e: Exception) {
            cubeFile.delete()
            throw e
        }

        val lutFile = File(root, "$id.lut")
        lut.writeTo(lutFile)
        val preview = File(previews, "${id}_studio.jpg")
        val previewB = File(previews, "${id}_outdoor.jpg")
        PreviewFactory.writePreviews(appContext.assets, lut, preview, previewB)

        val name = displayName
            .substringBeforeLast('.', displayName)
            .replace('_', ' ')
            .trim()
            .ifEmpty { "未命名 LUT" }

        val info = LutInfo(
            id = id,
            name = name.take(48),
            originalFileName = displayName,
            gridSize = lut.size,
            importedAt = System.currentTimeMillis(),
            previewPath = preview.absolutePath,
            cubePath = cubeFile.absolutePath,
            lutPath = lutFile.absolutePath,
            previewPathB = previewB.absolutePath
        )
        val items = list().toMutableList().apply { add(0, info) }
        save(items)
        return info
    }

    /** Rebuilds previews once after an upgrade so existing LUTs use the new real reference frame. */
    fun regeneratePreviewsIfNeeded(version: Int) {
        if (prefs.getInt("preview_version", 0) >= version) return
        val updated = list().map { info ->
            val lut = load(info.id) ?: return@map info
            val newPreview = File(previews, "${info.id}_v${version}_studio.jpg")
            val newPreviewB = File(previews, "${info.id}_v${version}_outdoor.jpg")
            val ok = runCatching {
                PreviewFactory.writePreviews(appContext.assets, lut, newPreview, newPreviewB)
            }.isSuccess
            if (ok) {
                if (info.previewPath != newPreview.absolutePath) {
                    runCatching { File(info.previewPath).delete() }
                }
                if (info.previewPathB != newPreviewB.absolutePath) {
                    runCatching { File(info.previewPathB).delete() }
                }
                info.copy(
                    previewPath = newPreview.absolutePath,
                    previewPathB = newPreviewB.absolutePath
                )
            } else {
                info
            }
        }
        save(updated)
        prefs.edit().putInt("preview_version", version).apply()
    }

    fun delete(id: String) {
        val info = get(id) ?: return
        runCatching { File(info.cubePath).delete() }
        runCatching { File(info.lutPath).delete() }
        runCatching { File(info.previewPath).delete() }
        runCatching { File(info.previewPathB).delete() }
        if (cachedId == id) {
            cachedId = null
            cachedLut = null
        }
        save(list().filterNot { it.id == id })
    }

    private fun save(items: List<LutInfo>) {
        val arr = JSONArray()
        items.forEach { info ->
            arr.put(JSONObject().apply {
                put("id", info.id)
                put("name", info.name)
                put("originalFileName", info.originalFileName)
                put("gridSize", info.gridSize)
                put("importedAt", info.importedAt)
                put("previewPath", info.previewPath)
                put("previewPathB", info.previewPathB)
                put("cubePath", info.cubePath)
                put("lutPath", info.lutPath)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
