package com.github.caijunlin.prism.gles

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.github.caijunlin.prism.core.VLCEngineManager
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 单个视频流的解码核心抽象基类。负责维护单路 VLC 播放器实例与共享的生命周期监控逻辑。
 */
class DecoderStream(
    /** 视频流地址 */
    val url: String,
    private val eglCore: EGLCore,
    private val renderHandler: Handler,
    private val mediaOptions: ArrayList<String>
) : SurfaceTexture.OnFrameAvailableListener {

    var oesTextureId = -1
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set
    var fboId = -1
        private set
    var tex2DId = -1
        private set
    private var decodeSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    val transformMatrix = FloatArray(16)
    var hasFirstFrame = false

    @Volatile
    var hasNewFboData = false
    val maxWidth = 1280
    val maxHeight = 720
    var videoWidth = maxWidth
    var videoHeight = maxHeight

    @Volatile
    private var retryCount = 0
    private val maxRetryLimit = 5

    @Volatile
    var isDecoding = false
        private set

    val displayWindows = CopyOnWriteArrayList<DisplayWindow>()

    @Volatile
    private var startPlayTimeMs: Long = 0L

    var miniFboId = -1
        private set
    var miniTex2DId = -1
        private set
    var miniWidth = 320
        private set
    var miniHeight = 180
        private set

    var miniBitmap: Bitmap? = null

    @Volatile
    var lastWatchdogMark: Long = 0L

    /** 帧刷新通讯员，底层出帧时主动通知 RenderNode 唤醒 VSync */
    var onFrameUpdateListener: (() -> Unit)? = null

    private val frameInterval: Long get() = 1000L / 25
    private var lastFboUpdateTimeMs = 0L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDecoding) {
                if (!hasFirstFrame) {
                    if (System.currentTimeMillis() - startPlayTimeMs > 15000L) {
                        Log.e(
                            "Prism",
                            "Watchdog Bite! 15s timeout waiting for FIRST frame: $url"
                        )
                        retryPlay()
                        return
                    }
                } else {
                    if (System.currentTimeMillis() - lastWatchdogMark > 5000L) {
                        Log.e("Prism", "Watchdog Bite! Video completely frozen for 5s: $url")
                        retryPlay()
                        return
                    }
                }
            }
            renderHandler.postDelayed(this, 3000L)
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (!isDecoding) return
        try {
            if (!eglCore.makeCurrentMain()) {
                return
            }
            st.updateTexImage()
            lastWatchdogMark = System.currentTimeMillis()
            st.getTransformMatrix(transformMatrix)

            if (!hasFirstFrame) {
                checkAndUpdateResolution()
                hasFirstFrame = true
            }

            if (hasNewFboData) {
                return
            }

            val currentTime = System.currentTimeMillis()
            if (lastFboUpdateTimeMs > 0) {
                if (currentTime - lastFboUpdateTimeMs < frameInterval) {
                    return
                }
            }
            lastFboUpdateTimeMs = currentTime

            eglCore.drawOESToFBO(fboId, oesTextureId, transformMatrix, videoWidth, videoHeight)
            hasNewFboData = true

            // 画面画入显存后，立刻唤醒 RenderNode 准备上屏！
            onFrameUpdateListener?.invoke()

        } catch (e: Exception) {
            Log.e("Prism", "OES Fast Consume failed: ${e.message}")
        }
    }

    private fun getSafeResolution(originalWidth: Int, originalHeight: Int): Pair<Int, Int> {
        if (originalWidth <= 0 || originalHeight <= 0) return Pair(maxWidth, maxHeight)
        var safeW = originalWidth
        var safeH = originalHeight
        if (safeW > maxWidth || safeH > maxHeight) {
            val scale = minOf(maxWidth.toFloat() / safeW, maxHeight.toFloat() / safeH)
            safeW = (safeW * scale).toInt()
            safeH = (safeH * scale).toInt()
        }
        return Pair(safeW, safeH)
    }

    private fun createMedia(vlc: LibVLC): Media {
        val media = Media(vlc, url.toUri())
        mediaOptions.forEach { media.addOption(it) }
        return media
    }

    fun start() {
        val fboData = eglCore.createFBO(videoWidth, videoHeight)
        fboId = fboData[0]
        tex2DId = fboData[1]

        miniWidth = maxOf(1, videoWidth / 4)
        miniHeight = maxOf(1, videoHeight / 4)
        val miniFboData = eglCore.createFBO(miniWidth, miniHeight)
        miniFboId = miniFboData[0]
        miniTex2DId = miniFboData[1]

        miniBitmap = createBitmap(miniWidth, miniHeight, Bitmap.Config.ARGB_8888)

        eglCore.makeCurrentMain()
        val initBitmap = miniBitmap
        if (initBitmap != null) {
            eglCore.captureMiniFrameFast(
                fboId, videoWidth, videoHeight,
                miniFboId, miniWidth, miniHeight, initBitmap
            )
        }

        oesTextureId = eglCore.generateOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener(this@DecoderStream, renderHandler)
        }
        decodeSurface = Surface(surfaceTexture)

        VLCEngineManager.libVLC?.let { vlc ->
            mediaPlayer = MediaPlayer(vlc)
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            media.release()

            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> retryPlay()
                    MediaPlayer.Event.Playing -> {
                        isDecoding = true
                        retryCount = 0
                        startPlayTimeMs = System.currentTimeMillis()
                        lastWatchdogMark = System.currentTimeMillis()
                    }

                    MediaPlayer.Event.EncounteredError -> {
                        retryCount++
                        if (retryCount <= maxRetryLimit) {
                            renderHandler.postDelayed({ retryPlay() }, 2000L)
                        } else {
                            renderHandler.post { releaseVLCOnly() }
                        }
                    }

                    MediaPlayer.Event.Stopped -> isDecoding = false
                }
            }
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()

            startPlayTimeMs = System.currentTimeMillis()
            renderHandler.postDelayed(watchdogRunnable, 3000L)
        }
    }

    fun clearFBOToBlack() {
        if (fboId != -1) {
            eglCore.makeCurrentMain()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            if (miniFboId != -1) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, miniFboId)
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun retryPlay() {
        isDecoding = false
        hasFirstFrame = false
        clearFBOToBlack()
        clearAllWindowsToTransparent()

        if (retryCount >= maxRetryLimit) {
            releaseVLCOnly()
            return
        }

        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        VLCEngineManager.libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()

            startPlayTimeMs = System.currentTimeMillis()
            lastWatchdogMark = System.currentTimeMillis()
            retryCount++
        }
    }

    fun isEligibleForCapture(): Boolean {
        return isDecoding && hasFirstFrame && url.isNotEmpty() && displayWindows.any { it.isActive }
    }

    fun suspendPlayback() {
        if (!isDecoding) return
        renderHandler.removeCallbacks(watchdogRunnable)
        val length = mediaPlayer?.length ?: 0L
        val liveStream = url.startsWith("rtsp", ignoreCase = true) ||
                url.startsWith("rtmp", ignoreCase = true) || length == 0L
        if (liveStream) mediaPlayer?.stop() else mediaPlayer?.pause()
        isDecoding = false
    }

    fun resumePlayback() {
        if (mediaPlayer?.isPlaying == true) return
        mediaPlayer?.play()
        isDecoding = true
        lastWatchdogMark = System.currentTimeMillis()
        renderHandler.postDelayed(watchdogRunnable, 3000L)
    }

    fun checkAndUpdateResolution() {
        val track = mediaPlayer?.currentVideoTrack ?: return
        val (realW, realH) = getSafeResolution(track.width, track.height)

        if (realW > 0 && realH > 0 && (realW != videoWidth || realH != videoHeight)) {
            videoWidth = realW
            videoHeight = realH
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"

            eglCore.deleteFBO(fboId, tex2DId)
            val newFboData = eglCore.createFBO(videoWidth, videoHeight)
            fboId = newFboData[0]
            tex2DId = newFboData[1]
            surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)

            eglCore.deleteFBO(miniFboId, miniTex2DId)
            miniWidth = maxOf(1, videoWidth / 4)
            miniHeight = maxOf(1, videoHeight / 4)
            val newMiniFboData = eglCore.createFBO(miniWidth, miniHeight)
            miniFboId = newMiniFboData[0]
            miniTex2DId = newMiniFboData[1]

            miniBitmap?.recycle()
            miniBitmap = createBitmap(miniWidth, miniHeight, Bitmap.Config.ARGB_8888)

            displayWindows.forEach { it.isDirty = true }
        }
    }

    private fun clearAllWindowsToTransparent() {
        if (displayWindows.isEmpty()) return
        displayWindows.forEach { window ->
            if (window.x5Surface.isValid && window.isActive) {
                if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                    eglCore.clearCurrentSurface(window.physicalW, window.physicalH)
                    eglCore.swapBuffers(window.eglSurface)
                }
            }
        }
        eglCore.makeCurrentMain()
    }

    private fun releaseVLCOnly() {
        isDecoding = false
        hasFirstFrame = false
        clearFBOToBlack()
        clearAllWindowsToTransparent()
        renderHandler.removeCallbacks(watchdogRunnable)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.vlcVout?.detachViews()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("Prism", "Release VLC failed: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    fun release() {
        renderHandler.removeCallbacks(watchdogRunnable)
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        decodeSurface?.release()
        surfaceTexture?.release()

        eglCore.deleteTexture(oesTextureId)
        eglCore.deleteFBO(fboId, tex2DId)
        eglCore.deleteFBO(miniFboId, miniTex2DId)

        miniBitmap?.recycle()
        miniBitmap = null
        onFrameUpdateListener = null

        Log.d("Prism", "Release stream: $url")
    }
}