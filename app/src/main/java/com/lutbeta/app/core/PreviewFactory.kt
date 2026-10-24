package com.lutbeta.app.core

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Builds LUT previews from the two bundled real S-Log3 reference frames. */
object PreviewFactory {
    const val BASE_STUDIO = "preview_studio.jpg"   // 灰片
    const val BASE_OUTDOOR = "preview_outdoor.jpg" // 灰片1

    private const val W = 480
    private const val H = 300
    private const val PREVIEW_WIDTH = 720
    private const val PREVIEW_HEIGHT = 405

    /** Generates both example frames with the exact same trilinear LUT sampling used by the app. */
    fun writePreviews(
        assets: AssetManager,
        lut: CubeLut,
        studioTarget: File,
        outdoorTarget: File
    ) {
        writeOne(assets, BASE_STUDIO, lut, studioTarget)
        writeOne(assets, BASE_OUTDOOR, lut, outdoorTarget)
    }

    fun writePreview(assets: AssetManager, lut: CubeLut, target: File) {
        writeOne(assets, BASE_STUDIO, lut, target)
    }

    /**
     * A fresh, unmodified copy of one of the two reference frames.
     * Used by the LUT library strength slider so it can render at any strength
     * without touching the saved JPEG previews.
     */
    fun loadBase(assets: AssetManager, index: Int): Bitmap? {
        val asset = if (index == 1) BASE_OUTDOOR else BASE_STUDIO
        return loadReference(assets, asset)
    }

    private fun writeOne(assets: AssetManager, assetName: String, lut: CubeLut, target: File) {
        target.parentFile?.mkdirs()
        val bitmap = loadReference(assets, assetName) ?: buildReference()
        applyLutInPlace(bitmap, lut, 1f)
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()
    }

    private fun loadReference(assets: AssetManager, assetName: String): Bitmap? {
        return runCatching {
            assets.open(assetName).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()?.let { decoded ->
            if (decoded.width == PREVIEW_WIDTH && decoded.height == PREVIEW_HEIGHT) {
                decoded
            } else {
                val scaled = Bitmap.createScaledBitmap(decoded, PREVIEW_WIDTH, PREVIEW_HEIGHT, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        }
    }

    private fun buildReference(): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(
                    Color.rgb(17, 42, 54),
                    Color.rgb(64, 104, 112),
                    Color.rgb(213, 144, 86),
                    Color.rgb(48, 30, 27)
                ),
                floatArrayOf(0f, 0.42f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), sky)

        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                344f, 132f, 128f,
                intArrayOf(Color.argb(235, 255, 216, 154), Color.argb(0, 255, 170, 90)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(344f, 132f, 128f, sun)

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 238, 196) }
        c.drawCircle(344f, 132f, 22f, disc)

        val hills = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 24, 22) }
        val path = Path().apply {
            moveTo(0f, 226f)
            cubicTo(75f, 190f, 135f, 214f, 196f, 232f)
            cubicTo(270f, 252f, 344f, 198f, 480f, 222f)
            lineTo(W.toFloat(), H.toFloat())
            lineTo(0f, H.toFloat())
            close()
        }
        c.drawPath(path, hills)

        val water = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 229, 174, 122)
        }
        c.drawRect(300f, 245f, 388f, 249f, water)
        c.drawRect(316f, 257f, 372f, 260f, water)

        val chips = intArrayOf(
            Color.rgb(238, 198, 168),
            Color.rgb(190, 72, 66),
            Color.rgb(91, 137, 94),
            Color.rgb(73, 105, 151),
            Color.rgb(219, 181, 88),
            Color.rgb(138, 138, 138)
        )
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val startX = 26f
        val y = 258f
        for (i in chips.indices) {
            chipPaint.color = chips[i]
            rect.set(startX + i * 31f, y, startX + i * 31f + 24f, y + 24f)
            c.drawRoundRect(rect, 4f, 4f, chipPaint)
        }

        val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                W / 2f, H / 2f, W * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.argb(85, 0, 0, 0)),
                floatArrayOf(0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), vignette)
        return bitmap
    }

    /** Real-time editor preview: same trilinear sampling, applied to the whole cached frame. */
    fun applyLut(source: Bitmap, lut: CubeLut, strength: Float): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3)
        val s = strength.coerceIn(0f, 1f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r0 = ((p ushr 16) and 0xFF) / 255f
            val g0 = ((p ushr 8) and 0xFF) / 255f
            val b0 = (p and 0xFF) / 255f
            lut.sample(r0, g0, b0, out)
            val r = (r0 + (out[0].coerceIn(0f, 1f) - r0) * s)
            val g = (g0 + (out[1].coerceIn(0f, 1f) - g0) * s)
            val b = (b0 + (out[2].coerceIn(0f, 1f) - b0) * s)
            pixels[i] = (0xFF shl 24) or
                ((r * 255f).roundToInt() shl 16) or
                ((g * 255f).roundToInt() shl 8) or
                (b * 255f).roundToInt()
        }
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun applyLutInPlace(bitmap: Bitmap, lut: CubeLut, strength: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            lut.sample(
                ((p ushr 16) and 0xFF) / 255f,
                ((p ushr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f,
                out
            )
            val r = (out[0].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (out[1].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (out[2].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
