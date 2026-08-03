package com.github.caijunlin.prism.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import android.view.DragEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import com.github.caijunlin.prism.bridge.VLCJSBridge
import com.github.caijunlin.prism.callback.WebViewClient
import com.github.caijunlin.prism.gesture.VLCDragManager
import com.github.caijunlin.prism.widget.VLCVideoSurface
import com.github.caijunlin.prism.widget.WidgetManager
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidget
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClient
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClientFactory
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.export.external.interfaces.WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.CookieManager
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.WebView as OWebView
import com.tencent.smtt.sdk.WebViewClient as OWebViewClient

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 专为监控与播流面板定制的 X5 WebView
 */
@SuppressLint("ViewConstructor")
class WebView(
    context: Context,
    private val widgetTag: String
) : OWebView(context), IEmbeddedWidgetClientFactory {

    /** 标记是否已经向当前内核环境注入过监控脚本 */
    private var isObserverInjected = false

    /** 缓存外部注册X5WebViewClient 列表 */
    private val webViewClients = mutableListOf<WebViewClient>()

    init {
        setupView()
        setupWebViewClient()
    }

    /** 控制“不可滚动”的面板特性 */
    var isScroll: Boolean = false
        set(value) {
            field = value
            isVerticalScrollBarEnabled = value
            isHorizontalScrollBarEnabled = value
            view.overScrollMode = if (value) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER
        }

    @Keep
    fun addWebViewClient(client: WebViewClient) {
        if (!webViewClients.contains(client)) {
            webViewClients.add(client)
        }
    }

    @Deprecated(
        message = "Forbidden. Use addWebViewClient().",
        level = DeprecationLevel.ERROR
    )
    override fun setWebViewClient(client: OWebViewClient?) {
        throw UnsupportedOperationException("Forbidden. Use addWebViewClient().")
    }

    /**
     * 统一的初始化入口，确保在 widgetTag 被赋值之后才执行
     */
    private fun setupView() {
        setBackgroundColor(0)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        VLCEngineManager.init(context)
        addJavascriptInterface(VLCJSBridge(this), "VLCJSBridge")

        initWebSettings()
        initCookieManager()

        // 此时注册同层渲染，拿到的必定是最准确的widgetTag
        initEmbeddedWidget()
        this.setOnDragListener { _, _ -> false }
    }

    /**
     * 初始化主分发器，内部循环调用缓存 list
     */
    private fun setupWebViewClient() {
        super.setWebViewClient(object : OWebViewClient() {

            override fun onPageStarted(view: OWebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val currentView = view ?: this@WebView
                val currentUrl = url ?: ""

                webViewClients.forEach {
                    it.onPageStarted(currentView, currentUrl, favicon)
                }
                WidgetManager.onDestroy()
            }

            override fun onPageFinished(view: OWebView?, url: String?) {
                if (!isObserverInjected) {
                    isObserverInjected = true
                    injectTagObserver()
                }
                super.onPageFinished(view, url)

                val currentView = view ?: this@WebView
                val currentUrl = url ?: ""

                webViewClients.forEach {
                    it.onPageFinished(currentView, currentUrl)
                }
            }

            override fun shouldOverrideUrlLoading(view: OWebView?, url: String?): Boolean {
                val currentView = view ?: this@WebView
                val currentUrl = url ?: ""

                for (client in webViewClients) {
                    if (client.shouldOverrideUrlLoading(currentView, currentUrl)) {
                        return true
                    }
                }
                return super.shouldOverrideUrlLoading(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: OWebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (view != null && request != null) {
                    for (client in webViewClients) {
                        if (client.shouldOverrideUrlLoading(view, request)) {
                            return true
                        }
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldInterceptRequest(
                view: OWebView?,
                url: String?
            ): WebResourceResponse? {
                val currentView = view ?: this@WebView
                val currentUrl = url ?: ""

                for (client in webViewClients) {
                    val response = client.shouldInterceptRequest(currentView, currentUrl)
                    if (response != null) return response
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun shouldInterceptRequest(
                view: OWebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (view != null && request != null) {
                    for (client in webViewClients) {
                        val response = client.shouldInterceptRequest(view, request)
                        if (response != null) return response
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: OWebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)

                val currentView = view ?: this@WebView
                val currentDesc = description ?: ""
                val currentUrl = failingUrl ?: ""

                webViewClients.forEach {
                    it.onReceivedError(currentView, errorCode, currentDesc, currentUrl)
                }
            }

            override fun onReceivedError(
                view: OWebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (view != null && request != null && error != null) {
                    webViewClients.forEach {
                        it.onReceivedError(view, request, error)
                    }
                }
            }

            override fun onReceivedSslError(
                view: OWebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                super.onReceivedSslError(view, handler, error)
                if (view != null && handler != null && error != null) {
                    webViewClients.forEach {
                        it.onReceivedSslError(view, handler, error)
                    }
                }
            }
        })
    }

    /**
     * 配置 Web 设置参数
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebSettings() {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            setAllowFileAccessFromFileURLs(true)
            setAllowUniversalAccessFromFileURLs(true)
            allowContentAccess = true
            loadWithOverviewMode = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "utf-8"
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
    }

    /**
     * 初始化 Cookie 管理器
     */
    private fun initCookieManager() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.flush()
    }

    /**
     * 注册同层渲染组件
     */
    private fun initEmbeddedWidget() {
        val tags = arrayOf(widgetTag)
        val success = x5WebViewExtension?.registerEmbeddedWidget(tags, this) ?: false
        Log.d(
            "VLCDecoder",
            "TBS:${QbSdk.getTbsVersion(context)} X5:${isX5Core} Init:${QbSdk.isTbsCoreInited()} FrcSys:${QbSdk.getIsSysWebViewForcedByOuter()} Reg:$success Tag:$widgetTag"
        )
    }

    override fun loadUrl(url: String) {
        this.isObserverInjected = false
        runOnMainThread {
            super.loadUrl(url)
        }
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        this.isObserverInjected = false
        runOnMainThread {
            super.loadUrl(url, additionalHttpHeaders)
        }
    }

    /**
     * 安全地在主线程执行操作
     */
    private fun runOnMainThread(action: () -> Unit) {
        // 判断当前是否已经在主线程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action() // 已经在主线程，直接执行
        } else {
            post { action() } // 在子线程，post 到主线程执行
        }
    }

    /**
     * 创建同层渲染客户端
     * @param tagName 标签名
     * @param attributes 属性集合
     * @param widget 内嵌组件接口
     * @return 渲染客户端实例
     */
    override fun createWidgetClient(
        tagName: String,
        attributes: Map<String, String>,
        widget: IEmbeddedWidget
    ): IEmbeddedWidgetClient? {
        return when (tagName.lowercase()) {
            widgetTag.lowercase() -> {
                VLCVideoSurface(
                    this,
                    widgetTag,
                    attributes
                )
            }

            else -> null
        }
    }

    /**
     * 处理点击事件
     * @return 是否处理
     */
    override fun performClick(): Boolean {
        return super.performClick()
    }

    /**
     * 触摸事件分发处理
     * @param event 动作事件
     * @return 是否消费
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        // 如果禁止滚动且是滑动操作，则不处�?
        if (!isScroll && event.action == MotionEvent.ACTION_MOVE) {
            return false
        }
        return super.onTouchEvent(event)
    }

    /**
     * 处理滚动位置
     * @param x 坐标
     * @param y 坐标
     */
    override fun scrollTo(x: Int, y: Int) {
        if (isScroll) {
            super.scrollTo(x, y)
        } else {
            super.scrollTo(0, 0)
        }
    }

    /**
     * 拖拽事件分发
     * @param event 拖拽事件
     * @return 是否消费
     */
    override fun dispatchDragEvent(event: DragEvent): Boolean {
        // 优先由自定义处理器执行
        if (VLCDragManager.processDragEvent(event)) {
            return true
        }
        return super.dispatchDragEvent(event)
    }

    /**
     * 注入监控脚本
     *
     */
    private fun injectTagObserver() {
        val tagName = widgetTag.lowercase()
        val jsCode = """
            (function() {
                if (window._surfaceObserved) return;
                window._surfaceObserved = true;
                var observer = new IntersectionObserver(function(entries) {
                    entries.forEach(function(entry) {
                        if (window.VLCBridge && entry.target.id) {
                            window.VLCBridge.notifyX5SurfaceVisibility(entry.target.id, entry.isIntersecting);
                        }
                    });
                }, {
                    root: null,
                    threshold: 0.01
                });
                function observeTags() {
                    var elements = document.getElementsByTagName('$tagName');
                    for (var i = 0; i < elements.length; i++) {
                        if (!elements[i]._surfaceObserved) {
                            elements[i]._surfaceObserved = true;
                            observer.observe(elements[i]);
                        }
                    }
                }
                observeTags();
                var mutationObserver = new MutationObserver(observeTags);
                mutationObserver.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        post {
            evaluateJavascript(jsCode, null)
        }
    }

    /**
     * 销毁 WebView 资源
     */
    override fun destroy() {
        this.isObserverInjected = false
        this.clearAnimation()
        super.setWebViewClient(null)
        webViewClients.clear()
        this.webChromeClient = null
        this.stopLoading()
        this.clearHistory()
        this.clearCache(true)
        this.clearFormData()
        this.clearMatches()
        this.removeAllViews()
        this.clearSslPreferences()
        this.clearDisappearingChildren()
        super.destroy()
    }
}
