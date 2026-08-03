package com.github.caijunlin.prism.core

import android.os.Handler
import com.github.caijunlin.prism.gles.DecoderStream
import com.github.caijunlin.prism.gles.EGLCore
import java.util.concurrent.ConcurrentHashMap

/**
 * @author : caijunlin
 * @description : 视频流与解码器生命周期全局管理器。负责多路解码器的调度、缓存、软挂起与硬销毁防抖。
 */
class VLCDecoderManager(
    private val handler: Handler,
    private val eglCore: EGLCore,
    private val streamFactory: (String, EGLCore, Handler, ArrayList<String>) -> DecoderStream
) {

    /**
     * 全局最大解码路数限制。
     * 采用 JVM 级全局属性配置，默认值为 10。
     * 宿主可通过 System.setProperty("vlc.decoder.max_streams", "32") 在任意时刻全局无锁更改。
     */
    private val maxStreamLimit: Int
        get() = System.getProperty("vlc.decoder.max_streams", "10")?.toIntOrNull() ?: 10

    val streams = ConcurrentHashMap<String, DecoderStream>()

    /** 待释放任务字典，专门用于防抖延迟清理 */
    private val pendingReleaseTasks = ConcurrentHashMap<String, Runnable>()

    /**
     * 获取或创建一条流
     */
    fun getOrCreateStream(url: String, opts: ArrayList<String>): DecoderStream? {
        cancelDelayedRelease(url)
        var stream = streams[url]
        if (stream == null) {
            if (streams.size >= maxStreamLimit) return null
            stream = streamFactory(url, eglCore, handler, opts)
            stream.start()
            streams[url] = stream
        }
        return stream
    }

    /**
     * 挂起流（所有窗口均隐藏时触发）
     */
    fun suspendStreamIfNeeded(url: String) {
        val stream = streams[url] ?: return
        if (stream.displayWindows.none { it.isActive }) {
            stream.suspendPlayback()
        }
    }

    /**
     * 唤醒流
     */
    fun resumeStreamIfNeeded(url: String) {
        streams[url]?.resumePlayback()
    }

    /**
     * 触发防抖延迟销毁（对应硬销毁或彻底清空 URL）。
     * 当没有任何窗口订阅该流时，启动 1000ms 倒计时。
     * 给前端 DOM 树的重排、组件切换留下充足的缓冲时间。
     */
    fun triggerDelayedRelease(url: String, delayMs: Long = 1000L) {
        val stream = streams[url] ?: return
        // 双重保险：如果此时还有窗口在订阅，绝对不销毁
        if (stream.displayWindows.isNotEmpty()) return
        if (!pendingReleaseTasks.containsKey(url)) {
            val task = Runnable {
                val s = streams[url]
                // 倒计时结束，执行最终的“死刑”前再次确认没有窗口订阅
                if (s != null && s.displayWindows.isEmpty()) {
                    s.release() // 彻底释放底层的 LibVLC 引擎和 FBO 显存
                    streams.remove(url)
                }
                pendingReleaseTasks.remove(url)
            }
            pendingReleaseTasks[url] = task
            handler.postDelayed(task, delayMs)
        }
    }

    /**
     * 取消倒计时释放任务
     */
    private fun cancelDelayedRelease(url: String) {
        val task = pendingReleaseTasks.remove(url)
        if (task != null) {
            handler.removeCallbacks(task)
        }
    }

    /**
     * 清空工作区，暴力释放所有流 (App退出或页面整体销毁时调用)
     */
    fun releaseAll() {
        pendingReleaseTasks.values.forEach { handler.removeCallbacks(it) }
        pendingReleaseTasks.clear()
        streams.values.forEach { it.release() }
        streams.clear()
    }

}