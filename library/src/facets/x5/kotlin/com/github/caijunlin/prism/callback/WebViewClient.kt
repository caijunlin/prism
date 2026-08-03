package com.github.caijunlin.prism.callback

import android.graphics.Bitmap
import com.github.caijunlin.prism.core.WebView
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.sdk.WebView as OWebView
import com.tencent.smtt.sdk.WebViewClient as OWebViewClient

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 定制的 WebViewClient，对外暴露强类型的 StreamWebView 回调
 */
open class WebViewClient : OWebViewClient() {

    /**
     * 统一的失败回调方法，供外部重写
     * @param view 当前的 StreamWebView 实例
     * @param errorMsg 错误描述信息
     */
    open fun onLoadFailed(view: WebView, errorMsg: String) {
    }

    /**
     * 页面开始加载，供外部重写
     * @param view 当前的 StreamWebView 实例
     * @param url 加载的地址
     * @param favicon 页面图标
     */
    open fun onPageStart(view: WebView, url: String, favicon: Bitmap?) {
    }

    /**
     * 页面加载完成，供外部重写
     * @param view 当前的 StreamWebView 实例
     * @param url 加载的地址
     */
    open fun onPageFinish(view: WebView, url: String) {
    }

    /**
     * 拦截 URL 加载
     * @param view WebView 实例
     * @param url 链接地址
     * @return 是否拦截
     */
    final override fun shouldOverrideUrlLoading(view: OWebView, url: String): Boolean {
        return false
    }

    /**
     * 页面开始加载监听
     * @param view WebView 实例
     * @param url 链接地址
     * @param favicon 图标
     */
    final override fun onPageStarted(view: OWebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // 尝试转换为 StreamWebView 并触发回调
        (view as? WebView)?.let { onPageStart(it, url, favicon) }
    }

    /**
     * 页面完成加载监听
     * @param view WebView 实例
     * @param url 链接地址
     */
    final override fun onPageFinished(view: OWebView, url: String) {
        super.onPageFinished(view, url)
        // 尝试转换为 StreamWebView 并触发回调
        (view as? WebView)?.let { onPageFinish(it, url) }
    }

    /**
     * 收到错误回调
     * @param view WebView 实例
     * @param errorCode 错误码
     * @param description 描述
     * @param failingUrl 失败的 URL
     */
    final override fun onReceivedError(
        view: OWebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        // 分发加载失败事件
        (view as? WebView)?.let { onLoadFailed(it, "Err $errorCode: $description") }
    }

    /**
     * SSL 错误处理
     * @param view WebView 实例
     * @param handler 处理器
     * @param error 错误详情
     */
    final override fun onReceivedSslError(
        view: OWebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        (view as? WebView)?.let { onLoadFailed(it, "SSL Err: ${error.primaryError}") }
        // 默认行为是取消加载。如果外部想要忽略证书错误，可以重写此方法并调用 handler.proceed()
        super.onReceivedSslError(view, handler, error)
    }
}