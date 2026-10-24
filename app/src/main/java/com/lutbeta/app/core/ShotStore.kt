package com.lutbeta.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Shot(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val originalPath: String?,
    val outputUri: String? = null,
    val lutName: String?,
    val lutId: String? = null,
    val strength: Float = 1f,
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "done",
    val error: String? = null,
    val source: String = "ftp"
) {
    val isDone: Boolean get() = status == "done" && !outputUri.isNullOrBlank()
}

class ShotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shot_history", Context.MODE_PRIVATE)

    fun list(): List<Shot> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Shot(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    fileName = o.optString("fileName", "未命名"),
                    originalPath = o.optString("originalPath").takeIf { it.isNotBlank() },
                    outputUri = o.optString("outputUri").takeIf { it.isNotBlank() },
                    lutName = o.optString("lutName").takeIf { it.isNotBlank() },
                    lutId = o.optString("lutId").takeIf { it.isNotBlank() },
                    strength = o.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1f),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    createdAt = o.optLong("createdAt"),
                    status = o.optString("status", "done"),
                    error = o.optString("error").takeIf { it.isNotBlank() },
                    source = o.optString("source", "ftp")
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun add(shot: Shot) {
        val items = list().toMutableList()
        items.add(0, shot)
        save(items.take(120))
    }

    @Synchronized
    fun update(shot: Shot) {
        val items = list().toMutableList()
        val idx = items.indexOfFirst { it.id == shot.id }
        if (idx >= 0) items[idx] = shot else items.add(0, shot)
        save(items.take(120))
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        prefs.edit().putString("items", "[]").apply()
    }

    private fun save(items: List<Shot>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("fileName", s.fileName)
                put("originalPath", s.originalPath ?: "")
                put("outputUri", s.outputUri ?: "")
                put("lutName", s.lutName ?: "")
                put("lutId", s.lutId ?: "")
                put("strength", s.strength.toDouble())
                put("width", s.width)
                put("height", s.height)
                put("createdAt", s.createdAt)
                put("status", s.status)
                put("error", s.error ?: "")
                put("source", s.source)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
