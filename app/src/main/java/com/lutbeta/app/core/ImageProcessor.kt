package com.lutbeta.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ImageProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ImageProcessor {
    private const val TILE = 1024

    data class Result(val file: File, val width: Int, val height: Int)

    fun process(
        input: File,
        output: File,
        lut: CubeLut,
        strength: Float,
        maxDimension: Int,
        quality: Int
    ): Result {
        if (!input.exists()) throw ImageProcessingException("源文件不存在")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageProcessingException("无法识别图片格式")
        }

        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        val longest = max(srcW, srcH)
        val scale = if (maxDimension > 0 && longest > maxDimension) {
            maxDimension.toFloat() / longest
        } else 1f
        val outW = max(1, (srcW * scale).roundToInt())
        val outH = max(1, (srcH * scale).roundToInt())

        output.parentFile?.mkdirs()
        val renderer = try {
            GlLutRenderer(lut, strength)
        } catch (e: Exception) {
            throw ImageProcessingException("初始化 LUT 渲染失败：${e.message}", e)
        }

        try {
            val rendered: Bitmap = try {
                renderTiled(input, renderer, srcW, srcH, outW, outH, scale)
                    ?: renderFull(input, renderer, outW, outH, maxDimension)
            } catch (e: OutOfMemoryError) {
                throw ImageProcessingException("照片太大，内存不足；请关闭其他应用后重试。导出始终使用原图分辨率。", e)
            }

            try {
                FileOutputStream(output).use { out ->
                    if (!rendered.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), out)) {
                        throw ImageProcessingException("JPEG 编码失败")
                    }
                }
                copyExif(input, output)
                output.setLastModified(input.lastModified())
                return Result(output, rendered.width, rendered.height)
            } finally {
                rendered.recycle()
            }
        } finally {
            renderer.close()
        }
    }

    /**
     * Decodes only the small base frame for the realtime editor. The returned bitmap
     * already has EXIF orientation applied and is safe to reuse for every slider move.
     */
    fun decodePreviewBase(input: File, maxDimension: Int = 720): Bitmap {
        if (!input.exists()) throw ImageProcessingException("原片不存在")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageProcessingException("无法识别原片格式")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        var bitmap = BitmapFactory.decodeFile(
            input.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
        ) ?: throw ImageProcessingException("无法解码原片")
        if (max(bitmap.width, bitmap.height) > maxDimension) {
            val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val orientation = runCatching {
            ExifInterface(input.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orientPreview(bitmap, orientation)
    }

    /** Fast, downscaled preview used by the per-photo LUT editor. */
    fun renderPreview(
        input: File,
        lut: CubeLut,
        strength: Float,
        maxDimension: Int = 1440
    ): Bitmap {
        if (!input.exists()) throw ImageProcessingException("原片不存在")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageProcessingException("无法识别原片格式")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            input.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
        ) ?: throw ImageProcessingException("无法解码原片")
        var source = decoded
        if (max(source.width, source.height) > maxDimension) {
            val scale = maxDimension.toFloat() / max(source.width, source.height)
            source = Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).roundToInt()),
                max(1, (decoded.height * scale).roundToInt()),
                true
            )
            if (source !== decoded) decoded.recycle()
        }
        val orientation = runCatching {
            ExifInterface(input.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val renderer = GlLutRenderer(lut, strength)
        return try {
            val rendered = renderer.render(source)
            orientPreview(rendered, orientation)
        } finally {
            renderer.close()
            source.recycle()
        }
    }

    private fun orientPreview(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return runCatching {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            oriented
        }.getOrDefault(bitmap)
    }

    private fun renderTiled(
        input: File,
        renderer: GlLutRenderer,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        scale: Float
    ): Bitmap? {
        val decoder = try {
            BitmapRegionDecoder.newInstance(input.absolutePath, false)
        } catch (e: Exception) {
            null
        } ?: return null

        val output = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            decoder.recycle()
            throw e
        } catch (e: Exception) {
            decoder.recycle()
            throw ImageProcessingException("无法创建输出图片", e)
        }

        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
        }

        try {
            var y = 0
            while (y < outH) {
                val h = min(TILE, outH - y)
                var x = 0
                while (x < outW) {
                    val w = min(TILE, outW - x)
                    val left = floor(x / scale).toInt().coerceIn(0, srcW - 1)
                    val top = floor(y / scale).toInt().coerceIn(0, srcH - 1)
                    val right = ceil((x + w) / scale).toInt().coerceIn(left + 1, srcW)
                    val bottom = ceil((y + h) / scale).toInt().coerceIn(top + 1, srcH)
                    val rect = Rect(left, top, right, bottom)
                    val tile = decoder.decodeRegion(rect, options)
                        ?: throw ImageProcessingException("读取照片区域失败")
                    val processed = renderer.render(tile)
                    tile.recycle()
                    canvas.drawBitmap(
                        processed, null,
                        RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
                        paint
                    )
                    processed.recycle()
                    x += TILE
                }
                y += TILE
            }
            return output
        } catch (e: Throwable) {
            output.recycle()
            throw e
        } finally {
            decoder.recycle()
        }
    }

    private fun renderFull(
        input: File,
        renderer: GlLutRenderer,
        outW: Int,
        outH: Int,
        maxDimension: Int
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        var sample = 1
        while (maxDimension > 0 && max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val source = BitmapFactory.decodeFile(input.absolutePath, options)
            ?: throw ImageProcessingException("无法解码照片（可能是 HEIF/RAW 格式）")
        return try {
            if (source.width == outW && source.height == outH) {
                renderer.render(source)
            } else {
                val processed = renderer.render(source)
                if (processed.width == outW && processed.height == outH) {
                    processed
                } else {
                    val scaled = Bitmap.createScaledBitmap(processed, outW, outH, true)
                    if (scaled !== processed) processed.recycle()
                    scaled
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun copyExif(source: File, target: File) {
        runCatching {
            val src = ExifInterface(source.absolutePath)
            val dst = ExifInterface(target.absolutePath)
            for (tag in EXIF_TAGS) {
                val value = runCatching { src.getAttribute(tag) }.getOrNull()
                if (value.isNullOrEmpty()) continue
                runCatching { dst.setAttribute(tag, value) }
            }
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "LUT beta 1.0")
            dst.saveAttributes()
        }
    }

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )
}
