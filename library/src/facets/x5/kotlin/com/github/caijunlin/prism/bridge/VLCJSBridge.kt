package com.github.caijunlin.prism.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.renderer.VLCRenderPool

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
        Log.d("Prism", "printVLC")
        VLCRenderPool.printVLC()
    }

}
