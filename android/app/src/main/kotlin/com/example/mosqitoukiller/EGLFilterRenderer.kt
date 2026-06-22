// EGLFilterRenderer.kt
// Native GPU 滤镜渲染器
// 架构：相机 → inputSurfaceTexture → OpenGL ES 处理 → outputSurface(Flutter Texture) → 显示

package com.example.mosqitoukiller

import android.graphics.SurfaceTexture
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

    // 当前滤镜模式
    var filterMode = 0
        set(value) {
            if (field != value) {
                field = value
                if (program != 0) {
                    GLES20.glDeleteProgram(program)
                    program = 0
                }
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
     * @param outputSurface 输出 Surface（Flutter Texture 的 Surface）
     */
    fun init(outputSurface: Surface, w: Int, h: Int): Boolean {
        width = w
        height = h

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
        // 使用外部纹理 target，因为 SurfaceTexture 通常对外部 OES 纹理进行更新（相机等）
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]
        // 绑定到 GL_TEXTURE_EXTERNAL_OES
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

        initialized = true
        Log.i("EGLFilter", "EGL initialized ok, filterMode=$filterMode")
        return true
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
        return true
    }

    // ==================== 渲染 ====================

    /**
     * 当有新相机帧时调用：把输入纹理处理后渲染到输出 Surface
     */
    fun onFrameAvailable() {
        if (!initialized) return

        // 绑定 EGL 上下文 —— 必须先绑定，updateTexImage() 需要当前的 GL context
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // 更新输入纹理（相机帧 → OpenGL 纹理）
        inputSurfaceTexture?.updateTexImage()

        // 如果 program 被删除（例如在 setter 切换滤镜时），在渲染线程/有 GL context 的时候尝试重新编译
        if (program == 0) {
            if (!compileProgram(filterMode)) {
                Log.e("EGLFilter", "compileProgram failed in onFrameAvailable for filterMode=$filterMode")
                return
            }
        }

        // 设置视口
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 使用程序
        GLES20.glUseProgram(program)

        // 顶点属性
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 纹理
        val uTex = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // 使用外部纹理 target
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(uTex, 0)

        // 分辨率（边缘检测用）
        val uRes = GLES20.glGetUniformLocation(program, "uResolution")
        if (uRes >= 0) {
            GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 可选：禁用顶点属性以清理状态
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        // 交换缓冲区（输出到 Flutter Texture）
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // ==================== 释放 ====================

    fun release() {
        initialized = false
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (inputTextureId != 0) {
            val tex = intArrayOf(inputTextureId)
            GLES20.glDeleteTextures(1, tex, 0)
            inputTextureId = 0
        }
        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
