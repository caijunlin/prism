package com.github.caijunlin.prism.gesture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.createBitmap
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.renderer.IVideoRenderClient
import com.github.caijunlin.prism.vo.DragSessionState
import kotlin.math.abs

/**
 * @author : caijunlin
 * @date   : 2026/03/18
 * @description : 拖拽手势处理类（支持防抖/立即启动双模式兼容机制）
 */
class VideoGestureHelper(
    private val client: IVideoRenderClient,
    private val webView: WebView
) {

    /** 记录手指按下时的绝对 X 坐标 */
    private var downX = 0f

    /** 记录手指按下时的绝对 Y 坐标 */
    private var downY = 0f

    /** 拖拽开始时获取的视频快照缓冲 */
    private var dragSnapshot: Bitmap? = null

    /** 系统触发拖拽的最小滑动物理像素值（防抖阈值） */
    private val touchSlop = ViewConfiguration.get(webView.context).scaledTouchSlop

    /** 是否已经成功触发了系统拖拽分发的标记 */
    private var hasTriggeredDrag = false

    /**
     * 判断是否为 Android 12 (API 31) 之前的旧版本。
     * 旧版系统 WebView 抢夺事件严重，需要立即触发拖拽。
     */
    private val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /**
     * 处理 WebView 传递过来的触控事件
     * @param event 触摸事件
     */
    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hasTriggeredDrag = false

                // 宣告父容器（WebView）不要拦截后续的 MOVE 事件
                webView.requestDisallowInterceptTouchEvent(true)

                // 针对 Android 11 及以下的设备，按下瞬间直接强杀 WebView 事件，立即启动拖拽
                if (isPreS) {
                    hasTriggeredDrag = true
                    triggerDrag()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 针对 Android 12 及以上的设备，走标准防抖逻辑，防止误触
                if (!isPreS && !hasTriggeredDrag) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)

                    // 只要有一个方向的移动距离大于系统防抖阈值，判定为拖拽意图
                    if (dx > touchSlop || dy > touchSlop) {
                        hasTriggeredDrag = true
                        triggerDrag()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasTriggeredDrag = false
                // 手指抬起或事件取消，恢复 WebView 的正常事件拦截机制
                webView.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    /**
     * 触发最终的系统级拖拽逻辑
     */
    private fun triggerDrag() {
        val rect = client.getElementRect()
        val bitmap = client.getDragShadowBitmap()
        val width = rect.labelW
        val height = rect.labelH

        // 无效尺寸或位图已回收则终止拖拽分发
        if (width <= 0 || height <= 0 || bitmap == null || bitmap.isRecycled) return
        val bw = bitmap.width
        val bh = bitmap.height
        // 按需复用或创建快照位图（硬拷贝截断底层 EGL 读写冲突，防撕裂）
        if (dragSnapshot == null || dragSnapshot!!.width != bw || dragSnapshot!!.height != bh) {
            dragSnapshot?.recycle()
            dragSnapshot = createBitmap(bw, bh)
        }
        val canvas = Canvas(dragSnapshot!!)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // 缩放计算触控点的相对偏移量，确保拖拽阴影的中心与手指贴合
        val touchX = downX / rect.scaleX
        val touchY = downY / rect.scaleY
        val touchPointX = touchX.toInt().coerceIn(0, width)
        val touchPointY = touchY.toInt().coerceIn(0, height)
        val dragBuilder =
            BitmapDragShadowBuilder(dragSnapshot, touchPointX, touchPointY, width, height)

        // 构建当前拖拽会话的全局状态缓存
        VLCDragManager.activeSession = DragSessionState(
            client = client,
            width = width,
            height = height,
            touchOffsetX = touchPointX.toFloat(),
            touchOffsetY = touchPointY.toFloat(),
            lastVisualX = touchPointX.toFloat(),
            lastVisualY = touchPointY.toFloat()
        )

        // 启动 Android 原生跨域（Global）拖拽机制
        val isSuccess = webView.startDragAndDrop(
            null,
            dragBuilder,
            null,
            View.DRAG_FLAG_GLOBAL
        )

        // 如果系统拒绝拖拽请求，回滚清空缓存状态
        if (!isSuccess) {
            VLCDragManager.activeSession = null
        }
    }

    /**
     * 销毁引用与快照内存
     */
    fun destroy() {
        VLCDragManager.activeSession = null
        dragSnapshot?.recycle()
        dragSnapshot = null
    }

}