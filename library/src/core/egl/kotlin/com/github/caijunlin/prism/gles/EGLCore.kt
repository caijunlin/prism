package com.github.caijunlin.prism.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 底层的 EGL14 渲染核心引擎。
 */
class EGLCore {
    /** 硬件设备的 EGL 显示链接句柄 */
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY

    /** 全局唯一的主渲染上下文环境 */
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    /** 描述图形表面像素格式与深度的配置对象 */
    private var eglConfig: EGLConfig? = null

    /** 用于维持 OpenGL 状态机的隐藏离屏虚拟表面对象 */
    var dummySurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    /** OES 绘制程序的标识符 */
    private var oesProgramId = 0
    /** OES 变换矩阵的位置 */
    private var uOesTransformMatrixLoc = -1
    /** OES MVP 矩阵的位置 */
    private var uOesMvpMatrixLoc = -1
    /** OES 纹理单元采样器的位置 */
    private var texOESLoc = -1

    /** 2D 纹理绘制程序的标识符 */
    private var tex2DProgramId = 0
    /** 2D MVP 矩阵的位置 */
    private var uTex2DMvpMatrixLoc = -1
    /** 2D 纹理单元采样器的位置 */
    private var tex2DLoc = -1

    /** 纯色填充程序的标识符 */
    private var solidColorProgramId = 0
    /** 颜色 Uniform 的位置 */
    private var uSolidColorLoc = -1

    /** 顶点坐标缓冲区 */
    private val vertexBuffer: FloatBuffer

    /** 单位矩阵常量 */
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    /** 常规截帧复用的直接内存缓冲 */
    private var captureBuffer: ByteBuffer? = null
    /** 截帧缓冲容量记录 */
    private var captureBufferCapacity = 0

    /** PBO (像素缓冲对象) 双缓冲句柄 */
    private val pboIds = IntArray(2)
    /** 当前让 GPU 异步写入的 PBO 索引 */
    private var pboWriteIndex = 0
    /** 当前由 CPU 取出画面映射的 PBO 索引 */
    private var pboReadIndex = 1
    /** PBO 当前被分配的显存容量 */
    private var pboCapacity = 0
    /** 是否为建立缓冲后的首次同步读取 */
    private var isFirstPboCapture = true

    init {
        // 定义矩形区域顶点坐标及其对应的 UV 坐标
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
    }

    /**
     * 初始化 EGL 硬件显示环境及着色器
     */
    fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )

        val bufferAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        dummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, bufferAttributes, 0)

        makeCurrentMain()

        // 编译并链接 OES 采样着色器
        oesProgramId = createOESProgram()
        uOesTransformMatrixLoc = GLES30.glGetUniformLocation(oesProgramId, "uTransformMatrix")
        uOesMvpMatrixLoc = GLES30.glGetUniformLocation(oesProgramId, "uMVPMatrix")
        texOESLoc = GLES30.glGetUniformLocation(oesProgramId, "texOES")

        // 编译并链接标准 2D 采样着色器
        tex2DProgramId = createTex2DProgram()
        uTex2DMvpMatrixLoc = GLES30.glGetUniformLocation(tex2DProgramId, "uMVPMatrix")
        tex2DLoc = GLES30.glGetUniformLocation(tex2DProgramId, "tex2D")

        // 编译纯色填充着色器
        solidColorProgramId = GLES30.glCreateProgram().also {
            val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).apply {
                GLES30.glShaderSource(
                    this,
                    "#version 300 es\nlayout(location = 0) in vec4 aPosition;\nvoid main() { gl_Position = aPosition; }"
                )
                GLES30.glCompileShader(this)
            }
            val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).apply {
                GLES30.glShaderSource(
                    this,
                    "#version 300 es\nprecision mediump float;\nuniform vec4 uColor;\nlayout(location = 0) out vec4 fragColor;\nvoid main() { fragColor = uColor; }"
                )
                GLES30.glCompileShader(this)
            }
            GLES30.glAttachShader(it, v)
            GLES30.glAttachShader(it, f)
            GLES30.glLinkProgram(it)
        }
        uSolidColorLoc = GLES30.glGetUniformLocation(solidColorProgramId, "uColor")
    }

    /**
     * 创建基于物理 Surface 的渲染表面
     */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val surfaceAttributes = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttributes, 0)
    }

    /**
     * 销毁渲染表面
     */
    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    /**
     * 将隐藏虚拟表面设为当前环境
     */
    fun makeCurrentMain(): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
    }

    /**
     * 将特定表面设为当前渲染上下文
     */
    fun makeCurrent(eglSurface: EGLSurface, eglCtx: EGLContext): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)
    }

    /**
     * 交换前后置缓冲区（上屏）
     */
    fun swapBuffers(eglSurface: EGLSurface) {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * 设置垂直同步间隔
     */
    fun setSwapInterval(interval: Int) {
        EGL14.eglSwapInterval(eglDisplay, interval)
    }

    /**
     * 创建帧缓冲对象(FBO)
     */
    fun createFBO(width: Int, height: Int): IntArray {
        val fbo = IntArray(1)
        val tex = IntArray(1)

        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, tex, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex[0],
            0
        )

        // 立刻将刚创建的 FBO 刷为纯黑实体底色
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(fbo[0], tex[0])
    }

    /**
     * 删除帧缓冲及关联纹理
     */
    fun deleteFBO(fboId: Int, tex2DId: Int) {
        if (fboId != -1) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (tex2DId != -1) GLES30.glDeleteTextures(1, intArrayOf(tex2DId), 0)
    }

    /**
     * 生成并配置 OES 纹理
     */
    fun generateOESTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        return oesTextureId
    }

    /**
     * 删除纹理
     */
    fun deleteTexture(textureId: Int) {
        if (textureId != -1) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    /**
     * 将 OES 纹理渲染至指定的 FBO 中 (逐帧硬绑防御性写法)
     */
    fun drawOESToFBO(
        fboId: Int,
        oesTextureId: Int,
        transformMatrix: FloatArray,
        width: Int,
        height: Int
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(oesProgramId)

        bindVertexData()

        GLES30.glUniformMatrix4fv(uOesTransformMatrixLoc, 1, false, transformMatrix, 0)
        GLES30.glUniformMatrix4fv(uOesMvpMatrixLoc, 1, false, identityMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(texOESLoc, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 将 2D 纹理渲染上屏 (逐帧硬绑防御性写法)
     */
    fun drawTex2DScreen(tex2DId: Int, mvpMatrix: FloatArray, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(tex2DProgramId)

        bindVertexData()

        GLES30.glUniformMatrix4fv(uTex2DMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2DId)
        GLES30.glUniform1i(tex2DLoc, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * 绑定通用的顶点和纹理坐标数据
     */
    private fun bindVertexData() {
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)
    }

    private fun createOESProgram(): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTextureUV;
            uniform mat4 uTransformMatrix;
            uniform mat4 uMVPMatrix; 
            out vec2 vTextureUV;
            void main() { 
                gl_Position = uMVPMatrix * aPosition; 
                vTextureUV = (uTransformMatrix * aTextureUV).xy; 
            }
        """
            ); GLES30.glCompileShader(it)
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTextureUV;
            uniform samplerExternalOES texOES;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = texture(texOES, vTextureUV); }
        """
            ); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(it, f); GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        }
    }

    private fun createTex2DProgram(): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTextureUV;
            uniform mat4 uMVPMatrix; 
            out vec2 vTextureUV;
            void main() { 
                gl_Position = uMVPMatrix * aPosition; 
                vTextureUV = aTextureUV.xy; 
            }
        """
            ); GLES30.glCompileShader(it)
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            precision mediump float;
            in vec2 vTextureUV;
            uniform sampler2D tex2D;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = texture(tex2D, vTextureUV); }
        """
            ); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(it, f); GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        }
    }

    /**
     * 将极其阻塞 CPU 的 glReadPixels 转化为显存到显存的异步 DMA 传输。
     */
    fun captureMiniFrameFast(
        srcFboId: Int, srcW: Int, srcH: Int,
        miniFboId: Int, miniW: Int, miniH: Int,
        bitmap: Bitmap
    ): Boolean {
        try {
            makeCurrentMain()
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFboId)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, miniFboId)
            // 执行硬件级别的像素快速缩放拷贝
            GLES30.glBlitFramebuffer(
                0, 0, srcW, srcH,
                0, 0, miniW, miniH,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_LINEAR
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, miniFboId)

            val requiredSize = miniW * miniH * 4

            // 如果缓冲池容量变化，重建 PBO 双缓冲显存池
            if (pboCapacity != requiredSize) {
                if (pboIds[0] != 0) GLES30.glDeleteBuffers(2, pboIds, 0)
                GLES30.glGenBuffers(2, pboIds, 0)

                // 分配显存
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[0])
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, requiredSize, null, GLES30.GL_DYNAMIC_READ)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[1])
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, requiredSize, null, GLES30.GL_DYNAMIC_READ)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

                pboCapacity = requiredSize
                isFirstPboCapture = true

                // 重建直读后备缓冲
                if (captureBuffer == null || captureBufferCapacity != requiredSize) {
                    captureBuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder())
                    captureBufferCapacity = requiredSize
                }
            }

            if (isFirstPboCapture) {
                // 首次截帧：为保证拖拽阴影立刻有数据，执行一次同步直读
                val buffer = captureBuffer!!
                buffer.clear()
                GLES30.glReadPixels(0, 0, miniW, miniH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                isFirstPboCapture = false
            } else {
                // 乒乓异步策略 (Ping-Pong Buffer)
                // 指挥 GPU 将画面塞进 writeIndex (CPU 不阻塞，立刻返回)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[pboWriteIndex])
                GLES30.glReadPixels(0, 0, miniW, miniH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)

                // 映射上一轮截帧时 GPU 早就准备好的 readIndex 数据到 CPU 内存
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[pboReadIndex])
                val mappedBuffer = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER, 0, requiredSize, GLES30.GL_MAP_READ_BIT
                )

                if (mappedBuffer != null) {
                    val byteBuffer = mappedBuffer as ByteBuffer
                    byteBuffer.order(ByteOrder.nativeOrder()).rewind()
                    bitmap.copyPixelsFromBuffer(byteBuffer) // 瞬间拷入 Bitmap
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER) // 安全解除映射
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

                // 交换索引，下一次的读写位置互换
                pboWriteIndex = (pboWriteIndex + 1) % 2
                pboReadIndex = (pboReadIndex + 1) % 2
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * 清空当前 Surface 内容并渲染特定底色
     */
    fun clearCurrentSurface(width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(solidColorProgramId)

        bindVertexData()

        GLES30.glUniform4f(uSolidColorLoc, 0.0f, 0.0f, 0.0f, 0.0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(0, 0, 1, 1)
        GLES30.glUniform4f(uSolidColorLoc, 0.0f, 0.0f, 0.0f, 0.01f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /**
     * 释放 EGL 核心持有的所有硬件资源
     */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            // 释放 PBO 显存
            if (pboIds[0] != 0) {
                GLES30.glDeleteBuffers(2, pboIds, 0)
                pboIds[0] = 0
                pboIds[1] = 0
            }

            if (oesProgramId != 0) GLES30.glDeleteProgram(oesProgramId)
            if (tex2DProgramId != 0) GLES30.glDeleteProgram(tex2DProgramId)
            if (solidColorProgramId != 0) GLES30.glDeleteProgram(solidColorProgramId)

            EGL14.eglDestroySurface(eglDisplay, dummySurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        dummySurface = EGL14.EGL_NO_SURFACE
        oesProgramId = 0
        tex2DProgramId = 0
        solidColorProgramId = 0

        // 彻底重置 PBO 状态机，防止复用实例时造成越界崩溃
        pboCapacity = 0
        pboWriteIndex = 0
        pboReadIndex = 1
        isFirstPboCapture = true

        captureBuffer?.clear()
        captureBuffer = null
        captureBufferCapacity = 0
    }
}