package com.github.caijunlin.prism.widget

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.github.caijunlin.prism.constant.HtmlAttribute
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.gesture.VideoGestureHelper
import com.github.caijunlin.prism.renderer.IVideoRenderClient
import com.github.caijunlin.prism.renderer.VLCRenderPool
import com.github.caijunlin.prism.vo.ElementRect
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClient
import kotlin.math.ceil

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 视频组件渲染表面，负责对接 X5 内核与底层渲染引擎
 */
class VLCVideoSurface(
    /** 宿主 WebView */
    var webView: WebView,
    /** HTML 标签名 */
    private val tagName: String,
    /** 初始属性集合 */
    attributes: Map<String, String>
) : IVideoRenderClient, IEmbeddedWidgetClient {

    /** 实时属性缓存（与前端数据同步更新的最精准单向真相源） */
    private var _attributes = attributes.toMutableMap()

    /** 组件 ID */
    val id: String get() = _attributes[HtmlAttribute.ID.key] ?: ""

    /** 视频源 URL */
    private val videoSrc: String get() = _attributes[HtmlAttribute.VIDEO_SRC.key] ?: ""

    /** 视频业务类型 */
    private val videoType: String
        get() = _attributes[HtmlAttribute.VIDEO_TYPE.key] ?: ""

    /** 业务透传数据 */
    private val videoData: String
        get() = _attributes[HtmlAttribute.VIDEO_DATA.key] ?: ""

    /** 是否支持拖拽标识 */
    private val draggable: Int
        get() = _attributes[HtmlAttribute.DRAGGABLE.key]?.toIntOrNull() ?: 0

    /** 记录组件在 WebView 中的位置矩形 */
    private var rect: Rect? = null

    /** 物理画布宽 */
    private var surfaceWidth: Int = 0

    /** 物理画布高 */
    private var surfaceHeight: Int = 0

    /** X5 回调提供的底层原生硬件加速 Surface */
    private var x5Surface: Surface? = null

    /** 宿主 WebView 全局的交互约束有效区域 */
    private var webViewRect = Rect(0, 0, webView.width, webView.height)

    /** 手势处理助手：承接手势分发并启动拖拽引擎 */
    private var gestureHelper: VideoGestureHelper = VideoGestureHelper(this, webView)

    /** 组件当前的全局显示活跃状态 */
    private var isVisibleAndActive = true

    /** 当前已经向底层提交了开启解流任务的 URL */
    private var decodingUrl: String? = null

    /** 当前 Surface 实际挂载渲染画面的 URL */
    private var attachedUrl: String? = null

    /** JS 跨端同步拿到的精准 HTML 元素矩形度量数据 */
    @Volatile
    private var elementRect: ElementRect = ElementRect()

    /** 用于拖拽阴影生成的快照位图缓存 */
    private var dragShadowBitmap: Bitmap? = null

    // 像React Diff算法一样，自动比对状态并执行最小化指令，完美解决并发与节流！
    private fun syncRenderState() {
        val targetUrl = videoSrc.takeIf { it.isNotEmpty() }

        // 独立管理解流任务生命周期
        // 只要 URL 存在，哪怕 Surface 没好，宽高为 0，也立即后台开始解流！
        if (decodingUrl != targetUrl) {
            decodingUrl?.let { oldUrl ->
                Log.d("Prism", "Stop Decode Stream: $oldUrl")
                VLCRenderPool.stopDecodeTask(oldUrl, this)
            }
            targetUrl?.let { newUrl ->
                Log.d("Prism", "Start Decode Stream: $newUrl")
                VLCRenderPool.startDecodeTask(newUrl, this)
            }
            decodingUrl = targetUrl
        }

        // 独立管理画布挂载生命周期
        // 必须满足 URL存在 + Surface有效 + 尺寸合法 + 组件活跃，才能将画面挂载上屏！
        val isSurfaceReady = x5Surface?.isValid == true && surfaceWidth > 0 && surfaceHeight > 0
        val shouldAttach = targetUrl != null && isSurfaceReady && isVisibleAndActive

        if (shouldAttach) {
            // 如果计算出的目标结果是“应该挂载”
            if (attachedUrl != targetUrl) {
                // 如果当前画布上还残留着别的旧视频，先安全卸载旧画面
                attachedUrl?.let { oldAttachedUrl ->
                    VLCRenderPool.detachSurface(oldAttachedUrl, this, isClearUrl = false)
                }
                Log.d("Prism", "Attach Surface to Stream: $targetUrl")
                VLCRenderPool.attachSurface(targetUrl, this)
                attachedUrl = targetUrl
            }
        } else {
            // 如果计算出的目标结果是“不该挂载”（如隐藏、尺寸变0、Surface销毁等）
            if (attachedUrl != null) {
                Log.d("Prism", "Detach Surface from Stream: $attachedUrl")
                // 若是因为 URL 被强行清空导致的卸载，传 true 触发彻底清屏洗黑残影
                val isClear = targetUrl == null
                VLCRenderPool.detachSurface(attachedUrl!!, this, isClearUrl = isClear)
                attachedUrl = null
            }
        }
    }

    /**
     * X5 Surface 被系统创建分配成功时的核心生命周期回调
     * @param surface 新生渲染表面
     */
    override fun onSurfaceCreated(surface: Surface?) {
        if (surface == null) return
        x5Surface = surface
        // 将自身实例注入 Widget 字典提供外部调度查找支持
        WidgetManager.cacheWidget(id, this)
        syncRenderState()
    }

    /**
     * X5 Surface 因外部不可抗力或者节点销毁导致的死亡毁减回调
     * @param surface 待报废渲染表面
     */
    override fun onSurfaceDestroyed(surface: Surface?) {
        val deadSurface = x5Surface
        x5Surface = null // 破坏挂载条件
        syncRenderState()
        deadSurface?.let { VLCRenderPool.releaseSurface(it) }
    }

    /**
     * 组件在物理区域因外部前端 CSS 触发 Resize 所导致的位置及尺寸改变回调
     * @param rect 最新矩形包围盒区域
     */
    override fun onRectChanged(rect: Rect?) {
        if (rect == null) return
        if (this.rect != null && this.rect?.width() == rect.width() && this.rect?.height() == rect.height()) {
            return
        }
        // 在主线程异步执行 JS 桥接，获取精准的前端节点缩放比例和元素信息并同步至内部属性池
        this.rect = rect
        webView.post {
            WidgetManager.getBoundingClientRect(webView, id) { _, _, w, h, sx, sy ->
                elementRect.labelW = w.toInt()
                elementRect.labelH = h.toInt()
                elementRect.scaleX = sx
                elementRect.scaleY = sy

                val newWidth = ceil(w * sx).toInt()
                val newHeight = ceil(h * sy).toInt()
                if (newWidth <= 0 || newHeight <= 0) return@getBoundingClientRect
                val isSizeChanged = (surfaceWidth != newWidth || surfaceHeight != newHeight)
                surfaceWidth = newWidth
                surfaceHeight = newHeight
                // 触发状态核对（如果这是第一次拿到底层宽高，它会自动触发画布挂载）
                syncRenderState()
                // 如果已经处于挂载状态且发生了尺寸微调，通知底层 OpenGL 调整视口
                if (isSizeChanged && attachedUrl != null) {
                    VLCRenderPool.resizeClient(this@VLCVideoSurface)
                }
            }
        }
    }

    /**
     * 处理响应来自前端对本 HTML 同层标签发起的各类自定义属性变更通知
     * @param p0 被变动的属性特征键 (Key)
     * @param p1 被赋入的新值 (Value)
     * @return 标记当前事件已被底层主动消费完毕
     */
    override fun onSetAttribute(p0: String?, p1: String?): Boolean {
        if (p0 == null) return false
        val value = p1 ?: ""
        _attributes[p0] = value
        if (p0 == HtmlAttribute.VIDEO_SRC.key) {
            syncRenderState() // 只要改了源，状态机自动判断是解流、切流、还是挂载！
        } else if (p0 == HtmlAttribute.DRAGGABLE.key) {
            if (draggable == 1 && value.toInt() == 0) {
                webView.cancelDragAndDrop()
            }
        }
        return true
    }

    /**
     * 接收由高级天眼组件(IntersectionObserver)分发过来的元素级可见性跳变通知
     * @param v 是否在屏幕内真正以 CSS 物理形式可见
     */
    override fun onVisibilityChanged(v: Boolean) {
        isVisibleAndActive = v
        syncRenderState() // 如果隐藏，自动破坏挂载条件卸载画面，但保留解流
    }

    override fun onActive() {
        isVisibleAndActive = true
        syncRenderState()
    }

    override fun onDeactive() {
        isVisibleAndActive = false
        syncRenderState()
    }

    /**
     * 组件遭遇全局销毁，此时清理一切可能产生内存引用的附属字典关联
     */
    override fun onDestroy() {
        _attributes[HtmlAttribute.VIDEO_SRC.key] = "" // 将目标源置空，破坏一切条件
        syncRenderState() // 自动触发彻底解绑和停止解流
        x5Surface?.let { VLCRenderPool.releaseSurface(it) }
        WidgetManager.removeWidget(this.id)
        gestureHelper.destroy()
        x5Surface = null
        dragShadowBitmap = null
    }

    override fun onRequestRedraw() {}

    /**
     * 处理上层 WebView 透传并转交此处的硬件触摸分发事件
     * @param event 触摸动作及坐标元数据
     * @return 是否在此节点强制消费事件
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && videoSrc.isNotEmpty() && draggable == 1) {
            gestureHelper.onTouchEvent(event)
        }
        return true
    }

    /**
     * 处理外部视频模块成功在此宿主容器内部发生拖拽抛落着陆时的行为判断逻辑
     */
    override fun onVideoDropped(centerX: Float, centerY: Float, width: Int, height: Int) {
        val cx = centerX.toInt()
        val cy = centerY.toInt()

        // 边界防护：若落点超出父级 WebView 本身的视窗合法管辖区，直接忽略无视
        if (!webViewRect.contains(cx, cy)) return

        when (videoType) {
            "source" -> {
                // 如果当前拖起物是一个源端件，则需利用坐标体系向下穿透寻靶，找寻其底下的合适承载者
                WidgetManager.getWidgetAt(webView, tagName, centerX, centerY) { hitWidget ->
                    if (hitWidget?.isSameVideoType("player") ?: false) {
                        WidgetManager.triggerSetVideoSource(
                            webView, hitWidget.getElementId(), videoData
                        )
                    }
                }
            }

            "player" -> {
                // 如果当前拖起的是一个播放窗实体件，我们需要计算其相对四个边界值的游移距离进行越界抛弃判定
                val left = rect?.left ?: 0
                val top = rect?.top ?: 0
                val right = left + width
                val bottom = top + height
                // 拖出原本规划在自身归属位置的安全包围盒区域，即被视为强行丢弃卸载该路流画面
                if (!(cx in left..<right && cy >= top && cy < bottom)) {
                    WidgetManager.triggerRemoveVideoSource(webView, this.id)
                }
            }
        }
    }

    /**
     * 更新拖拽阴影位图缓存池数据
     * @param bitmap 实时计算抓取的 FBO 位图
     */
    override fun updateDragShadowBitmap(bitmap: Bitmap) = run { dragShadowBitmap = bitmap }
    override fun getDragShadowBitmap(): Bitmap? = dragShadowBitmap
    override fun getElementRect(): ElementRect = elementRect
    override fun getElementId(): String = id
    override fun isSameId(id: String): Boolean = this.id == id
    override fun isSameVideoType(videoType: String): Boolean = this.videoType == videoType
    override fun getSurface(): Surface? = x5Surface
    override fun getSurfaceWidth(): Int = surfaceWidth
    override fun getSurfaceHeight(): Int = surfaceHeight

}
