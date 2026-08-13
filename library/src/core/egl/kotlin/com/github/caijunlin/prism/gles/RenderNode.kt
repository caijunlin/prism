package com.github.caijunlin.prism.gles

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.github.caijunlin.prism.Constant
import com.github.caijunlin.prism.core.VLCDecoderManager
import com.github.caijunlin.prism.core.VLCEngineManager
import com.github.caijunlin.prism.renderer.IVideoRenderClient
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * @author : caijunlin
 * @description : 视频渲染管线节点。挂载系统 Choreographer 进行并发渲染与防闪烁透明透底分发。
 */
class RenderNode(
    val nodeName: String
) {

    val thread = object : HandlerThread(nodeName) {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            super.run()
        }
    }.apply { start() }

    val handler = Handler(thread.looper)

    private lateinit var decoderManager: VLCDecoderManager
    private val decodeRequesters = ConcurrentHashMap<String, MutableSet<IVideoRenderClient>>()
    val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()
    private val lastCaptureTimeMap = HashMap<String, Long>()
    lateinit var eglCore: EGLCore
    private var choreographer: Choreographer? = null

    /** 节流防抖：确保一帧内无论被请求多少次，只向 Choreographer 注册一次 */
    @Volatile
    private var isRenderRequested = false

    /** 扁平化数组缓存！彻底消灭每帧遍历 Map 产生的 Iterator 内存垃圾！ */
    private var activeStreamsCache = emptyArray<DecoderStream>()

    private val frameCallback = Choreographer.FrameCallback {
        isRenderRequested = false // 消费通行证
        doThrottledRender()       // 执行一次精确打击，绝不盲目递归！
    }

    init {
        handler.post {
            eglCore = EGLCore().apply { initEGL() }
            choreographer = Choreographer.getInstance()
            decoderManager = VLCDecoderManager(handler, eglCore) { streamUrl, core, h, opts ->
                DecoderStream(streamUrl, core, h, opts)
            }
        }
    }

    /**
     * 按需唤醒 VSync
     */
    private fun requestRender() {
        if (!isRenderRequested) {
            isRenderRequested = true
            handler.post { choreographer?.postFrameCallback(frameCallback) }
        }
    }

    /**
     * 同步更新扁平化数组，并将唤醒钩子挂载到流上
     */
    private fun updateStreamCache() {
        if (::decoderManager.isInitialized) {
            activeStreamsCache = decoderManager.streams.values.toTypedArray()
            activeStreamsCache.forEach { stream ->
                stream.onFrameUpdateListener = { requestRender() }
            }
        }
    }

    fun handleStartDecode(url: String, client: IVideoRenderClient, opts: ArrayList<String>) {
        if (!::decoderManager.isInitialized) return
        val requesters =
            decodeRequesters.getOrPut(url) { Collections.newSetFromMap(ConcurrentHashMap()) }
        requesters.add(client)

        decoderManager.getOrCreateStream(url, opts) ?: return
        decoderManager.resumeStreamIfNeeded(url)
        updateStreamCache() // 重建缓存数组
    }

    fun handleStopDecode(url: String, client: IVideoRenderClient) {
        if (!::decoderManager.isInitialized) return
        val requesters = decodeRequesters[url]
        requesters?.remove(client)

        if (requesters.isNullOrEmpty()) {
            decodeRequesters.remove(url)
            val stream = decoderManager.streams[url]
            stream?.clearFBOToBlack()
            stream?.hasFirstFrame = false

            decoderManager.triggerDelayedRelease(url)
            decoderManager.suspendStreamIfNeeded(url)
            updateStreamCache() // 重建缓存数组
            requestRender()     // 唤醒一次，推上黑屏
        }
    }

    fun handleAttachSurface(url: String, x5Surface: Surface, client: IVideoRenderClient) {
        if (!::decoderManager.isInitialized) return
        val stream = decoderManager.streams[url] ?: return

        var window = displayMap[x5Surface]
        if (window == null) {
            window = DisplayWindow(x5Surface, client)
            window.initEGLSurface(eglCore)
            // 首次绑定，取消 SwapInterval 以提速
            if (window.x5Surface.isValid && eglCore.makeCurrent(
                    window.eglSurface,
                    eglCore.eglContext
                )
            ) {
                eglCore.setSwapInterval(0)
                eglCore.makeCurrentMain()
            }
            displayMap[x5Surface] = window
        }

        window.physicalW = client.getSurfaceWidth()
        window.physicalH = client.getSurfaceHeight()
        window.isActive = true

        if (window.x5Surface.isValid && eglCore.makeCurrent(
                window.eglSurface,
                eglCore.eglContext
            )
        ) {
            eglCore.clearCurrentSurface(window.physicalW, window.physicalH)
            eglCore.swapBuffers(window.eglSurface)
            eglCore.makeCurrentMain()
        }

        window.isDirty = true
        if (!stream.displayWindows.contains(window)) {
            stream.displayWindows.add(window)
        }
        if (stream.miniBitmap != null) {
            window.clientRef.get()?.updateDragShadowBitmap(stream.miniBitmap!!)
        }
        requestRender() // 唤醒绘制
    }

    fun handleDetachSurface(url: String, x5Surface: Surface, isClearUrl: Boolean) {
        if (!::decoderManager.isInitialized) return
        val window = displayMap[x5Surface] ?: return
        val stream = decoderManager.streams[url]

        if (window.x5Surface.isValid && eglCore.makeCurrent(
                window.eglSurface,
                eglCore.eglContext
            )
        ) {
            eglCore.clearCurrentSurface(window.physicalW, window.physicalH)
            eglCore.swapBuffers(window.eglSurface)
        }
        eglCore.makeCurrentMain()
        window.isActive = false

        if (stream != null) {
            stream.displayWindows.remove(window)
            if (isClearUrl) stream.clearFBOToBlack()
        }
    }

    fun handleReleaseSurface(x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        if (window != null) {
            eglCore.makeCurrentMain()
            window.release(eglCore)
        }
    }

    fun handleResize(x5Surface: Surface, client: IVideoRenderClient) {
        if (!::decoderManager.isInitialized) return
        displayMap[x5Surface]?.let { window ->
            val newW = client.getSurfaceWidth()
            val newH = client.getSurfaceHeight()
            if (window.physicalW != newW || window.physicalH != newH) {
                window.physicalW = newW
                window.physicalH = newH
                window.isDirty = true
                requestRender() // 唤醒重绘
            }
        }
    }

    /**
     * 渲染
     */
    private fun doThrottledRender() {
        if (!::decoderManager.isInitialized) return
        var hasActiveDraws = false

        for (i in activeStreamsCache.indices) {
            val stream = activeStreamsCache[i]
            val needsRender = stream.hasNewFboData
            stream.hasNewFboData = false

            for (j in stream.displayWindows.indices) {
                val window = stream.displayWindows[j]
                if (!window.x5Surface.isValid || !window.isActive) continue

                if (needsRender || window.isDirty) {

                    // 没出首帧直接跳过！决不允许触碰昂贵的 makeCurrent！
                    if (!stream.hasFirstFrame) {
                        window.isDirty = false
                        continue
                    }
                    try {
                        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                            eglCore.drawTex2DScreen(
                                stream.tex2DId,
                                window.mvpMatrix,
                                window.physicalW,
                                window.physicalH
                            )
                            eglCore.swapBuffers(window.eglSurface)
                            window.isDirty = false
                            hasActiveDraws = true
                        }
                    } catch (e: Exception) {
                        Log.e(Constant.TAG, "Throttled Swap failed: ${e.message}")
                    }
                }
            }
        }
        if (hasActiveDraws) {
            processPeriodicCaptures()
            eglCore.makeCurrentMain() // 真正干活了才归位
        }
    }

    private fun scheduleCapture(stream: DecoderStream) {
        handler.post {
            if (stream.displayWindows.isEmpty()) return@post
            val targetBitmap = stream.miniBitmap ?: return@post
            val success = eglCore.captureMiniFrameFast(
                stream.fboId, stream.videoWidth, stream.videoHeight,
                stream.miniFboId, stream.miniWidth, stream.miniHeight,
                targetBitmap
            )
            if (success) {
                stream.displayWindows.forEach { window ->
                    window.clientRef.get()?.updateDragShadowBitmap(targetBitmap)
                }
            }
        }
    }

    private fun processPeriodicCaptures() {
        val currentTimeMs = System.currentTimeMillis()
        // 同理使用数组索引遍历
        for (i in activeStreamsCache.indices) {
            val stream = activeStreamsCache[i]
            if (!stream.isEligibleForCapture()) continue
            val lastCaptureTime = lastCaptureTimeMap[stream.url] ?: 0L
            if (currentTimeMs - lastCaptureTime >= 5000L) {
                lastCaptureTimeMap[stream.url] = currentTimeMs
                scheduleCapture(stream)
            }
        }
    }

    fun printNodeDiagnostics(nodeIndex: Int) {
        if (!::decoderManager.isInitialized || decoderManager.streams.isEmpty()) return
        Log.d(Constant.TAG, "------ Node-$nodeIndex ($nodeName) ------")
        decoderManager.streams.forEach { (url, stream) ->
            Log.d(Constant.TAG, "Stream URL: $url")
            Log.d(Constant.TAG, "|- Decoding: ${stream.isDecoding}")
            Log.d(Constant.TAG, "|- Active Surfaces: ${stream.displayWindows.size}")
            stream.displayWindows.forEachIndexed { _, window ->
                val surfaceHex = Integer.toHexString(window.x5Surface.hashCode())
                Log.d(
                    Constant.TAG,
                    "   |- @$surfaceHex Size: ${window.physicalW}x${window.physicalH} Active:${window.isActive}"
                )
            }
        }
    }

    fun clearWorkspace() {
        isRenderRequested = false
        choreographer?.removeFrameCallback(frameCallback)
        lastCaptureTimeMap.clear()
        decodeRequesters.clear()
        activeStreamsCache = emptyArray()
        eglCore.makeCurrentMain()

        if (::decoderManager.isInitialized) {
            decoderManager.releaseAll()
        }

        displayMap.values.forEach { it.release(eglCore) }
        displayMap.clear()
    }

    fun destroyNode() {
        clearWorkspace()
        VLCEngineManager.release()
        handler.post {
            eglCore.release()
            thread.quitSafely()
        }
    }
}