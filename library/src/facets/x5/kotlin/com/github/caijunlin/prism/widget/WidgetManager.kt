package com.github.caijunlin.prism.widget

import android.util.Log
import com.github.caijunlin.prism.Constant
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.renderer.IVideoRenderClient
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : VLCVideoWidget 统一管理工具类。负责 Widget 的缓存、移除以及基于坐标的查询
 */
object WidgetManager {

    /** 线程安全的组件缓存池 */
    private val widgetCache = CopyOnWriteArrayList<IVideoRenderClient>()

    /**
     * 缓存 Widget。在 X5 回调创建 Widget (onWidgetCreate) 时调用
     * @param id HTML 中标签的唯一标识
     * @param widget 创建出来的 VLCVideoWidget 实例
     */
    fun cacheWidget(id: String, widget: IVideoRenderClient) {
        // 确保 ID 唯一性
        widgetCache.removeAll { it.isSameId(id) }
        widgetCache.add(widget)
        Log.d(Constant.TAG, "Cached widget with id: $id")
    }

    /**
     * 根据 ID 获取对应的 Widget 实例
     * @param id 元素的唯一标识
     * @return 匹配的 IVideoRenderClient 或 null
     */
    fun getWidget(id: String): IVideoRenderClient? {
        return widgetCache.find { it.isSameId(id) }
    }

    /**
     * 移除销毁的 Widget。在 X5 回调销毁 Widget (onWidgetDestroy) 时调用，防止内存泄漏
     * @param id 需要移除的标签的唯一标识
     */
    fun removeWidget(id: String) {
        val removed = widgetCache.removeAll { it.isSameId(id) }
        if (removed) {
            Log.d(
                Constant.TAG, "Removed widget with id: $id"
            )
        }
    }

    /**
     * 清空所有缓存 (在 StreamWebView 销毁或页面刷新时按需调用)
     */
    fun clearAll() {
        widgetCache.clear()
        Log.d(Constant.TAG, "Cleared all widget caches.")
    }

    /**
     * 销毁
     */
    fun onDestroy() {
        widgetCache.stream().forEach { it.onDestroy() }
    }

    /**
     * 通过 x, y 获取最顶层的 VLCVideoWidget
     * @param webView 承载的 X5 StreamWebView
     * @param tagName 目标标签名
     * @param x Android 触摸事件的物理 X 坐标 (绝对不能是除过 dpr 的值，传最原始的 MotionEvent.x)
     * @param y Android 触摸事件的物理 Y 坐标
     * @param callback 异步回调
     */
    fun getWidgetAt(
        webView: WebView,
        tagName: String,
        x: Float,
        y: Float,
        callback: (IVideoRenderClient?) -> Unit
    ) {
        // 拿到 Android 端 StreamWebView 的真实物理宽高
        val androidW = webView.width
        val androidH = webView.height

        // 极度防御：防止除数为 0 导致 JS 报错
        if (androidW == 0 || androidH == 0) {
            callback(null)
            return
        }

        // 注入 JS 脚本，根据物理坐标探测 Web 元素
        val jsCode = """
            (function(aX, aY, aW, aH) {
                var webW = document.documentElement.clientWidth || window.innerWidth;
                var webH = document.documentElement.clientHeight || window.innerHeight;
                var scaleX = webW / aW;
                var scaleY = webH / aH;
                var cssX = aX * scaleX;
                var cssY = aY * scaleY;
                var element = document.elementFromPoint(cssX, cssY);
                while(element && element !== document.body && element !== document.documentElement) {
                    if (element.tagName.toLowerCase() === '$tagName') {
                        return element.id; 
                    }
                    element = element.parentElement;
                }
                return null;
            })($x, $y, $androidW, $androidH);
        """.trimIndent()
        Log.d(
            Constant.TAG,
            "JS Code WidgetAt: x=$x y=$y androidW=$androidW androidH=$androidH tagName=$tagName"
        )
        webView.evaluateJavascript(jsCode) { result ->
            Log.d(Constant.TAG, "JS Result: $result")
            if (!result.isNullOrEmpty() && result != "null") {
                val id = result.replace("\"", "")
                val targetWidget = widgetCache.find { it.isSameId(id) }
                callback(targetWidget)
            } else {
                callback(null)
            }
        }
    }

    /**
     * 基础函数：向指定 ID 的 DOM 元素派发 CustomEvent 自定义事件
     * @param webView 承载 StreamWebView
     * @param elementId 目标元素 ID
     * @param eventName 事件名称
     * @param detailData 事件细节数据
     * @param onComplete 结果回调
     */
    private fun dispatchCustomEvent(
        webView: WebView,
        elementId: String,
        eventName: String,
        detailData: String = "null",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val jsCode = """
            (function() {
                var element = document.getElementById('$elementId');
                if (element) {
                    var customEvent = new CustomEvent('$eventName', {
                        detail: $detailData,
                        bubbles: true,
                        cancelable: true
                    });
                    element.dispatchEvent(customEvent);
                }
            })();
        """.trimIndent()
        Log.d(
            Constant.TAG,
            "JS Code DispatchCustomEvent: elementId=$elementId eventName=$eventName detailData=$detailData"
        )
        webView.evaluateJavascript(jsCode) { result ->
            Log.d(Constant.TAG, "JS Result: $result")
            val isSuccess = result?.replace("\"", "")?.replace("'", "") == "true"
            onComplete?.invoke(isSuccess)
        }
    }

    /**
     * 触发无参数的 remove-video-source 事件
     * @param webView 宿主
     * @param elementId 目标元素
     */
    fun triggerRemoveVideoSource(webView: WebView, elementId: String) {
        dispatchCustomEvent(webView, elementId, "remove-video-source") { _ ->
            Log.d(Constant.TAG, "Triggered remove-video-source successfully on $elementId")
        }
    }

    /**
     * 触发带参数的 set-video-source 事件
     * @param webView 宿主
     * @param elementId 目标元素
     * @param videoData 装载的数据内容
     */
    fun triggerSetVideoSource(webView: WebView, elementId: String, videoData: String) {
        val safeVideoData = JSONObject.quote(videoData)
        val detailObj = "{ videoData: $safeVideoData }"
        dispatchCustomEvent(webView, elementId, "set-video-source", detailObj)
        Log.d(Constant.TAG, "Triggered set-video-source on $elementId with data")
    }

    /**
     * 通过 id 获取标签的真实坐标与宽高，并计算拖拽滑动的缩放比
     * @param webView 宿主
     * @param elementId 元素ID
     * @param callback 回调(X坐标, Y坐标, 宽, 高, 缩放X, 缩放Y)
     */
    fun getBoundingClientRect(
        webView: WebView,
        elementId: String,
        callback: (Double, Double, Double, Double, Double, Double) -> Unit
    ) {
        val jsCode = """
            (function() {
                var element = document.getElementById('$elementId');
                if (!element) return null;
                var rect = element.getBoundingClientRect();
                var style = window.getComputedStyle(element);
                function getPreciseLayoutSize(element, style) {
                    let width = parseFloat(style.width);
                    let height = parseFloat(style.height);
                    if (isNaN(width) || isNaN(height)) {
                        return {
                            width: element.offsetWidth, 
                            height: element.offsetHeight 
                        };
                    }
                    if (style.boxSizing !== 'border-box') {
                        const getVal = (prop) => parseFloat(style[prop]) || 0;
                        width += getVal('paddingLeft') + getVal('paddingRight') + 
                                 getVal('borderLeftWidth') + getVal('borderRightWidth');
                        height += getVal('paddingTop') + getVal('paddingBottom') + 
                                  getVal('borderTopWidth') + getVal('borderBottomWidth');
                    }
                    return { width, height };
                }
                const preciseSize = getPreciseLayoutSize(element, style);
                const exactW = preciseSize.width;
                const exactH = preciseSize.height;
                var finalOffsetW = (exactW > 0) ? exactW : (element.offsetWidth || 1);
                var finalOffsetH = (exactH > 0) ? exactH : (element.offsetHeight || 1);
                return JSON.stringify({
                    x: rect.left,
                    y: rect.top,
                    width: rect.width,
                    height: rect.height,
                    offsetW: finalOffsetW,
                    offsetH: finalOffsetH
                });
            })();
            """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                if (result.isNullOrEmpty() || result == "null") {
                    callback(0.0, 0.0, 0.0, 0.0, 1.0, 1.0)
                    return@evaluateJavascript
                }

                val rectStr = result.removeSurrounding("\"").replace("\\\"", "\"")
                val rect = JSONObject(rectStr)

                // 解析新增的 x 和 y
                val physicalX = rect.optDouble("x", 0.0)
                val physicalY = rect.optDouble("y", 0.0)

                val physicalWidth = rect.optDouble("width", 0.0)
                val physicalHeight = rect.optDouble("height", 0.0)
                val offsetW = rect.optDouble("offsetW", 1.0)
                val offsetH = rect.optDouble("offsetH", 1.0)

                val webScaleX = physicalWidth / offsetW
                val webScaleY = physicalHeight / offsetH

                val density = webView.context.resources.displayMetrics.density
                val scaleX = if (webScaleX > 0) density / webScaleX else 1.0
                val scaleY = if (webScaleY > 0) density / webScaleY else 1.0
                callback(
                    physicalX,
                    physicalY,
                    physicalWidth,
                    physicalHeight,
                    scaleX,
                    scaleY
                )
            } catch (_: Exception) {
                callback(0.0, 0.0, 0.0, 0.0, 1.0, 1.0)
            }
        }
    }

    /**
     * 通过 id 控制播放器的可见性
     * @param id 元素的 ID
     * @param v 是否在屏幕内可见
     */
    fun onVisibilityChanged(id: String, v: Boolean) {
        widgetCache.find { it.isSameId(id) }?.onVisibilityChanged(v)
    }

}