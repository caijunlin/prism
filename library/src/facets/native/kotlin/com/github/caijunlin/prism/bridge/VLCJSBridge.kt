package com.github.caijunlin.prism.bridge

import android.graphics.Rect
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import com.github.caijunlin.prism.Constant
import com.github.caijunlin.prism.constant.HtmlAttribute
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.renderer.VLCRenderPool
import com.github.caijunlin.prism.widget.VELCSurface
import com.github.caijunlin.prism.widget.WidgetManager

/**
 * @author : caijunlin
 * @description : VLC 视频解码器专用的 JS 通信桥梁
 */
@Keep
class VLCJSBridge(
    private val webView: WebView
) {

    /**
     * 打印 VLC 运行时信息
     */
    @JavascriptInterface
    fun printVLC() {
        Log.d(Constant.TAG, "printVLC")
        VLCRenderPool.printVLC()
    }

    /**
     * 接收前端真实的可见性变化通知
     * @param id 元素的 ID
     * @param v 是否在屏幕内可见
     */
    @JavascriptInterface
    fun onVisibilityChanged(id: String, v: Boolean) {
        Log.d(Constant.TAG, "onVisibilityChanged id=$id visible=$v")
        if (id.isBlank()) return
        webView.post {
            WidgetManager.onVisibilityChanged(id, v)
        }
    }

    @JavascriptInterface
    fun onSurfaceCreated(id: String, v: Boolean) {
        Log.d(Constant.TAG, "onSurfaceCreated id=$id visible=$v")
        if (id.isBlank()) return
        webView.post {
            val surface = VELCSurface(
                webView = webView,
                attributes = mapOf(HtmlAttribute.ID.key to id),
                v = v
            )
            WidgetManager.cacheWidget(id, surface)
        }
    }

    @JavascriptInterface
    fun onSurfaceDestroyed(id: String) {
        Log.d(Constant.TAG, "onSurfaceDestroyed id=$id")
        if (id.isBlank()) return
        webView.post {
            WidgetManager.getWidget(id)?.let { widget ->
                (widget as? VELCSurface)?.onDestroy()
            }
        }
    }

    @JavascriptInterface
    fun onSetAttribute(id: String, k: String, v: String) {
        Log.d(Constant.TAG, "onSetAttribute id=$id key=$k value=$v")
        if (id.isBlank()) return
        webView.post {
            WidgetManager.getWidget(id)?.let { widget ->
                (widget as? VELCSurface)?.onSetAttribute(k, v)
            }
        }
    }

    @JavascriptInterface
    fun onRectChanged(id: String, x: Float, y: Float, w: Float, h: Float) {
        Log.d(Constant.TAG, "onRectChanged id=$id x=$x y=$y w=$w h=$h")
        if (id.isBlank()) return
        webView.post {
            WidgetManager.getWidget(id)?.let { widget ->
                (widget as? VELCSurface)?.onRectChanged(
                    Rect(
                        x.toInt(),
                        y.toInt(),
                        (x + w).toInt(),
                        (y + h).toInt()
                    )
                )
            }
        }
    }

}
