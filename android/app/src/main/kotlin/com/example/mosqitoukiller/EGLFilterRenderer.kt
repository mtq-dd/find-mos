// EGLFilterRenderer.kt
// Native GPU 滤镜渲染器
// 架构：相机 → inputSurfaceTexture → OpenGL ES 处理 → outputSurface(Flutter Texture) → 显示

package com.example.mosqitoukiller

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class EGLFilterRenderer {

    // ==================== GLSL 着色器源码 ====================

    companion object {
        // 顶点着色器（所有滤镜共用）
        private const val VERTEX_SHADER_SRC = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 原色（直接显示）
        private const val FRAG_ORIGINAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // 热成像 Ironbow 调色板
        private const val FRAG_THERMAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                vec3 c;
                if (lum < 0.25) {
                    c = vec3(0.0, 0.0, 0.5 + lum * 8.0);
                } else if (lum < 0.5) {
                    c = vec3(0.0, (lum - 0.25) * 4.0, 1.0);
                } else if (lum < 0.75) {
                    float t = (lum - 0.5) * 4.0;
                    c = vec3(t, 1.0, 1.0 - t);
                } else {
                    float t = (lum - 0.75) * 4.0;
                    c = vec3(1.0, 1.0 - t * 0.5, t * 0.5);
                }
                gl_FragColor = vec4(c, color.a);
            }
        """

        // 边缘检测 Sobel 算子
        private const val FRAG_EDGE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uResolution;
            varying vec2 vTexCoord;
            void main() {
                vec2 texel = 1.0 / uResolution;
                float p00 = dot(texture2D(uTexture, vTexCoord + vec2(-texel.x, -texel.y)).rgb, vec3(0.299,0.587,0.114));
                float p01 = dot(texture2D(uTexture, vTexCoord + vec2(0.0,      -texel.y)).rgb, vec3(0.299,0.587,0.114));
                float p02 = dot(texture2D(uTexture, vTexCoord + vec2( texel.x, -texel.y)).rgb, vec3(0.299,0.587,0.114));
                float p10 = dot(texture2D(uTexture, vTexCoord + vec2(-texel.x,  0.0)).rgb,      vec3(0.299,0.587,0.114));
                float p12 = dot(texture2D(uTexture, vTexCoord + vec2( texel.x,  0.0)).rgb,      vec3(0.299,0.587,0.114));
                float p20 = dot(texture2D(uTexture, vTexCoord + vec2(-texel.x,  texel.y)).rgb, vec3(0.299,0.587,0.114));
                float p21 = dot(texture2D(uTexture, vTexCoord + vec2(0.0,       texel.y)).rgb, vec3(0.299,0.587,0.114));
                float p22 = dot(texture2D(uTexture, vTexCoord + vec2( texel.x,  texel.y)).rgb, vec3(0.299,0.587,0.114));
                float gx = -p00 - 2.0*p10 - p20 + p02 + 2.0*p12 + p22;
                float gy = -p00 - 2.0*p01 - p02 + p20 + 2.0*p21 + p22;
                float edge = sqrt(gx*gx + gy*gy);
                edge = clamp(edge, 0.0, 1.0);
                gl_FragColor = vec4(vec3(edge), 1.0);
            }
        """

        // 反色
        private const val FRAG_INVERT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(1.0 - color.rgb, color.a);
            }
        """

        private val FRAG_SOURCES = arrayOf(FRAG_ORIGINAL, FRAG_THERMAL, FRAG_EDGE, FRAG_INVERT)
    }

    // ==================== EGL / OpenGL 状态 ====================

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var inputTextureId = 0   // 相机帧纹理 ID
    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer

    // 输入 SurfaceTexture（相机输出到这个）
    private var inputSurfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    // 专用渲染线程：所有 EGL/OpenGL 操作必须在该线程执行
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val renderRunnable = Runnable { doRenderFrame() }

    // 用于安全在渲染线程重建 program 的标志
    @Volatile
    private var needsRecreateProgram = false

    // 缓存 locations，避免每帧查询
    private var aPosLocation = -1
    private var aTexLocation = -1
    private var uTexLocation = -1
    private var uResLocation = -1

    // 当前滤镜模式（setter 只标记需要重建，而不直接做 GL 调用）
    var filterMode = 0
        set(value) {
            if (field != value) {
                field = value
                needsRecreateProgram = true
            }
        }

    var width = 320
    var height = 240

    var initialized = false
        private set

    // ==================== 初始化 ====================

    init {
        val vertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        )
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }
    }

    /**
     * 初始化 EGL 和 OpenGL，创建输入 SurfaceTexture
     * 必须在渲染线程调用（由 startCameraStream 在相机线程通过 handler post）
     */
    fun init(outputSurface: Surface, w: Int, h: Int): Boolean {
        width = w
        height = h

        // 在调用线程初始化 EGL（调用者必须是渲染线程）
        val ok = initEGL(outputSurface)
        if (!ok) return false

        initialized = true
        Log.i("EGLFilter", "EGL initialized ok on thread=${Thread.currentThread().name}, filterMode=$filterMode")
        return true
    }

    /**
     * 在渲染线程执行 EGL 初始化
     */
    private fun initEGL(outputSurface: Surface): Boolean {
        // 1. 获取 EGLDisplay
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e("EGLFilter", "eglGetDisplay failed")
            return false
        }

        // 2. 初始化 EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e("EGLFilter", "eglInitialize failed")
            return false
        }

        // 3. 选择 EGL Config
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val numConfigs = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            Log.e("EGLFilter", "eglChooseConfig failed")
            return false
        }
        val config = configs[0]!!

        // 4. 创建 EGLContext
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e("EGLFilter", "eglCreateContext failed")
            return false
        }

        // 5. 创建 EGLSurface（绑定到输出 Surface）
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e("EGLFilter", "eglCreateWindowSurface failed")
            return false
        }

        // 6. 绑定上下文
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e("EGLFilter", "eglMakeCurrent failed")
            return false
        }

        // 7. 创建 OpenGL 纹理（用于输入 SurfaceTexture）
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 8. 创建输入 SurfaceTexture
        inputSurfaceTexture = SurfaceTexture(inputTextureId)
        inputSurfaceTexture!!.setDefaultBufferSize(width, height)
        inputSurface = Surface(inputSurfaceTexture)

        // 9. 编译着色器程序
        if (!compileProgram(filterMode)) {
            Log.e("EGLFilter", "compileProgram failed")
            return false
        }

        return true
    }

    /**
     * 启动专用渲染线程，所有 EGL/OpenGL 操作在该线程执行
     * 必须先调用此方法，再调用 init()
     */
    fun startRenderThread(): Boolean {
        renderThread = HandlerThread("EGLRender").also {
            it.start()
            renderHandler = Handler(it.looper)
        }
        CrashHandler.appendRuntime("EGL", "renderThread started: ${renderThread?.name}")
        return true
    }

    /**
     * 在渲染线程执行 init（通过 handler post）
     */
    fun initOnRenderThread(outputSurface: Surface, w: Int, h: Int): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        renderHandler?.post {
            result = init(outputSurface, w, h)
            latch.countDown()
        }
        try {
            latch.await()
        } catch (e: InterruptedException) {
            return false
        }
        return result
    }

    private fun compileProgram(mode: Int): Boolean {
        val fragSrc = FRAG_SOURCES.getOrNull(mode) ?: FRAG_SOURCES[0]

        // 编译顶点着色器
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, VERTEX_SHADER_SRC)
        GLES20.glCompileShader(vs)
        val status = IntArray(1)
        GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("EGLFilter", "Vertex shader compile error: " + GLES20.glGetShaderInfoLog(vs))
            GLES20.glDeleteShader(vs)
            return false
        }

        // 编译片段着色器
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs, fragSrc)
        GLES20.glCompileShader(fs)
        GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("EGLFilter", "Fragment shader compile error: " + GLES20.glGetShaderInfoLog(fs))
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return false
        }

        // 链接程序
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("EGLFilter", "Program link error: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            GLES20.glDeleteProgram(program)
            program = 0
            return false
        }

        // 可以删掉 shader 了（已经链接进 program）
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        // 缓存 attribute/uniform locations，减少每帧查询
        aPosLocation = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexLocation = GLES20.glGetUniformLocation(program, "uTexture")
        uResLocation = GLES20.glGetUniformLocation(program, "uResolution")

        return true
    }

    // ==================== 渲染 ====================

    private var renderFrameCount = 0

    /**
     * 当有新相机帧时调用：只发送信号到渲染线程，不做 GL 操作
     */
    fun onFrameAvailable() {
        if (!initialized) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "onFrameAvailable but not initialized")
            return
        }
        // 将渲染任务 post 到渲染线程，避免跨线程调用 GL
        renderHandler?.removeCallbacks(renderRunnable)
        renderHandler?.post(renderRunnable)
    }

    /**
     * 在渲染线程执行：读取相机帧 → GL 处理 → 输出到 Flutter Texture
     */
    private fun doRenderFrame() {
        if (!initialized) return
        renderFrameCount++

        // 绑定 EGL 上下文
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "eglMakeCurrent failed frame=$renderFrameCount")
            return
        }

        if (renderFrameCount == 1 || renderFrameCount == 30 || renderFrameCount % 100 == 0) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "rendering frame=$renderFrameCount w=$width h=$height")
        }

        // 更新输入纹理（相机帧 → OpenGL 纹理）
        inputSurfaceTexture?.updateTexImage()

        // 如果主线程标记了需要重建，由渲染线程在这里完成删除与重建
        if (needsRecreateProgram) {
            needsRecreateProgram = false

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
                aPosLocation = -1
                aTexLocation = -1
                uTexLocation = -1
                uResLocation = -1
            }

            if (!compileProgram(filterMode)) {
                com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "compileProgram failed")
                return
            }
        }

        if (program == 0) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "Skip frame: program == 0")
            return
        }

        // 设置视口并清屏
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 使用程序
        GLES20.glUseProgram(program)

        // 获取或使用缓存的 locations
        val aPos = if (aPosLocation >= 0) aPosLocation else GLES20.glGetAttribLocation(program, "aPosition").also { aPosLocation = it }
        val aTex = if (aTexLocation >= 0) aTexLocation else GLES20.glGetAttribLocation(program, "aTexCoord").also { aTexLocation = it }
        val uTex = if (uTexLocation >= 0) uTexLocation else GLES20.glGetUniformLocation(program, "uTexture").also { uTexLocation = it }
        val uRes = if (uResLocation >= 0) uResLocation else GLES20.glGetUniformLocation(program, "uResolution").also { uResLocation = it }

        // 顶点属性
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(uTex, 0)

        // 分辨率（边缘检测用）
        if (uRes >= 0) {
            GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        // 交换缓冲区（输出到 Flutter Texture）
        val swapResult = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!swapResult) {
            val err = EGL14.eglGetError()
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "eglSwapBuffers failed err=$err, frame=$renderFrameCount")
        } else if (renderFrameCount % 100 == 0) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "swapOK frame=$renderFrameCount")
        }
        val glErr = GLES20.glGetError()
        if (glErr != GLES20.GL_NO_ERROR) {
            com.example.mosqitoukiller.CrashHandler.appendRuntime("EGL", "glError=$glErr frame=$renderFrameCount")
        }
    }

    // ==================== 释放 ====================

    fun release() {
        initialized = false
        // 在渲染线程释放所有 GL/EGL 资源（GL 操作不能跨线程）
        val latch = java.util.concurrent.CountDownLatch(1)
        renderHandler?.post {
            releaseEGL()
            latch.countDown()
        }
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {}

        renderHandler?.removeCallbacks(renderRunnable)
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null

        aPosLocation = -1
        aTexLocation = -1
        uTexLocation = -1
        uResLocation = -1
        needsRecreateProgram = false
    }

    private fun releaseEGL() {
        if (program != 0) {
            try {
                GLES20.glDeleteProgram(program)
            } catch (_: Throwable) {}
            program = 0
        }
        if (inputTextureId != 0) {
            try {
                val tex = intArrayOf(inputTextureId)
                GLES20.glDeleteTextures(1, tex, 0)
            } catch (_: Throwable) {}
            inputTextureId = 0
        }
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { inputSurfaceTexture?.release() } catch (_: Throwable) {}
        inputSurfaceTexture = null
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (_: Throwable) {}
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            try {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            } catch (_: Throwable) {}
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Throwable) {}
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
