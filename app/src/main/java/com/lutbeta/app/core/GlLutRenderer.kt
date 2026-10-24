package com.lutbeta.app.core

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Off-screen OpenGL ES 3.0 renderer. A 3D texture is used for hardware-trilinear
 * LUT interpolation, which keeps a 33 MP photo fast on a phone.
 */
class GlLutRenderer(
    private val lut: CubeLut,
    private val strength: Float
) : Closeable {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var imageTexture = 0
    private var lutTexture = 0
    private var renderTexture = 0
    private var framebuffer = 0
    private var vao = 0
    private var vbo = 0

    private var uImage = -1
    private var uLut = -1
    private var uStrength = -1
    private var uDomainMin = -1
    private var uDomainMax = -1

    private var pixelBuffer: ByteBuffer? = null
    private var pixelInts = IntArray(0)

    init {
        initEgl()
        initGl()
    }

    fun render(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, renderTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, renderTexture, 0
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "无法创建图像帧缓冲"
        }

        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLES30.glUniform1i(uImage, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniform1f(uStrength, strength.coerceIn(0f, 1f))
        GLES30.glUniform3f(uDomainMin, lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
        GLES30.glUniform3f(uDomainMax, lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        val size = w * h * 4
        var buffer = pixelBuffer
        if (buffer == null || buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            pixelBuffer = buffer
        }
        buffer.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "读取渲染结果失败（GL $error）" }
        buffer.position(0)

        // Convert RGBA bytes to ARGB ints explicitly instead of relying on the
        // platform's internal Bitmap byte order. This keeps colors correct on
        // every GPU driver.
        val count = w * h
        if (pixelInts.size < count) pixelInts = IntArray(count)
        var index = 0
        for (i in 0 until count) {
            val r = buffer.get(index).toInt() and 0xFF
            val g = buffer.get(index + 1).toInt() and 0xFF
            val b = buffer.get(index + 2).toInt() and 0xFF
            pixelInts[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            index += 4
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixelInts, 0, w, 0, 0, w, h)
        return out
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "设备不支持 EGL" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "无法初始化 EGL" }

        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "设备不支持 OpenGL ES 3.0"
        }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "无法创建 OpenGL 3.0 上下文" }
        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法创建离屏渲染表面" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "无法激活 OpenGL 上下文"
        }
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uImage = GLES30.glGetUniformLocation(program, "uImage")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uStrength = GLES30.glGetUniformLocation(program, "uStrength")
        uDomainMin = GLES30.glGetUniformLocation(program, "uDomainMin")
        uDomainMax = GLES30.glGetUniformLocation(program, "uDomainMax")

        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        imageTexture = ids[0]
        renderTexture = ids[1]
        for (tex in ids) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        framebuffer = fbos[0]

        lutTexture = buildLutTexture()

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices); position(0) }

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun buildLutTexture(): Int {
        val n = lut.size
        val bytes = ByteArray(n * n * n * 3)
        var j = 0
        for (i in 0 until n * n * n) {
            bytes[j++] = (lut.data[i * 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes[j++] = (lut.data[i * 3 + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes[j++] = (lut.data[i * 3 + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.position(0)

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "上传 LUT 失败（GL $error）" }
        return tex
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vertex)
        GLES30.glAttachShader(prog, fragment)
        GLES30.glBindAttribLocation(prog, 0, "aPos")
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("着色器链接失败：$log")
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("着色器编译失败：$log")
        }
        return shader
    }

    override fun close() {
        runCatching {
            if (program != 0) GLES30.glDeleteProgram(program)
            if (imageTexture != 0 || renderTexture != 0 || lutTexture != 0) {
                GLES30.glDeleteTextures(3, intArrayOf(imageTexture, renderTexture, lutTexture), 0)
            }
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        }
        runCatching {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
        }
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x0040

        private const val VERTEX_SHADER = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uImage;
uniform sampler3D uLut;
uniform float uStrength;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;

void main() {
    vec3 src = texture(uImage, vUv).rgb;
    vec3 span = max(uDomainMax - uDomainMin, vec3(0.000001));
    vec3 coord = clamp((src - uDomainMin) / span, 0.0, 1.0);
    vec3 mapped = texture(uLut, coord).rgb;
    vec3 result = mix(src, mapped, clamp(uStrength, 0.0, 1.0));
    fragColor = vec4(result, 1.0);
}
"""
    }
}
