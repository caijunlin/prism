package com.github.caijunlin.prism.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Looper
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.annotation.Keep
import com.github.caijunlin.prism.bridge.VLCJSBridge
import com.github.caijunlin.prism.callback.WebViewClient
import com.github.caijunlin.prism.widget.WidgetManager
import android.webkit.WebView as OWebView
import android.webkit.WebViewClient as OWebViewClient

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description :
 */
@SuppressLint("ViewConstructor")
class WebView(
    context: Context,
    private val widgetTag: String
) : OWebView(context) {

    private var isObserverInjected = false

    private val webViewClients = mutableListOf<WebViewClient>()

    init {
        setupView()
        setupWebViewClient()
        applyScrollBehavior()
    }

    var isScroll: Boolean = false
        set(value) {
            field = value
            applyScrollBehavior()
        }

    private fun applyScrollBehavior() {
        isVerticalScrollBarEnabled = isScroll
        isHorizontalScrollBarEnabled = isScroll
        overScrollMode = if (isScroll) {
            OVER_SCROLL_IF_CONTENT_SCROLLS
        } else {
            OVER_SCROLL_NEVER
        }
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
    override fun setWebViewClient(client: OWebViewClient) {
        throw UnsupportedOperationException("Forbidden. Use addWebViewClient().")
    }

    private fun setupView() {
        setBackgroundColor(0)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        VLCEngineManager.init(context)
        addJavascriptInterface(VLCJSBridge(this), "VLCJSBridge")

        initWebSettings()
        initCookieManager()

        this.setOnDragListener { _, _ -> false }
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebSettings() {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            allowContentAccess = true
            loadWithOverviewMode = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "utf-8"
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
    }

    private fun initCookieManager() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.flush()
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

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            post { action() }
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        return super.onTouchEvent(event)
    }

    override fun scrollTo(x: Int, y: Int) {
        if (isScroll) {
            super.scrollTo(x, y)
        }
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        return if (isScroll) {
            super.overScrollBy(
                deltaX,
                deltaY,
                scrollX,
                scrollY,
                scrollRangeX,
                scrollRangeY,
                maxOverScrollX,
                maxOverScrollY,
                isTouchEvent
            )
        } else {
            false
        }
    }

    private fun injectTagObserver() {
        val tagName = widgetTag.lowercase()
        val jsCode = """
            (function () {
                if (window._surfaceObserved) return;
                window._surfaceObserved = true;
                var observer = new IntersectionObserver(function (entries) {
                    entries.forEach(function (entry) {
                        if (window.VLCJSBridge && entry.target.id) {
                            if (window.VLCJSBridge.onVisibilityChanged) {
                                window.VLCJSBridge.onVisibilityChanged(entry.target.id, entry.isIntersecting);
                            }
                        }
                    });
                }, {
                    root: null,
                    threshold: 0.01
                });
                var resizeObserver = new ResizeObserver(function (entries) {
                    entries.forEach(function (entry) {
                        var id = entry.target.id;
                        if (window.VLCJSBridge && window.VLCJSBridge.onRectChanged) {
                            var rect = entry.target.getBoundingClientRect();
                            window.VLCJSBridge.onRectChanged(id, rect.x, rect.y, rect.width, rect.height);
                        }
                    });
                });
            
                function observeNode(node) {
                    if (node._nodeObserved) return;
                    node._nodeObserved = true;
                    var id = node.id;
                    var initialVisible = false;
                    var rect = node.getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0 &&
                        rect.bottom > 0 && rect.right > 0 &&
                        rect.top < (window.innerHeight || document.documentElement.clientHeight) &&
                        rect.left < (window.innerWidth || document.documentElement.clientWidth)) {
                        initialVisible = true;
                    }
                    if (id && window.VLCJSBridge) {
                        if (window.VLCJSBridge.onSurfaceCreated) {
                            window.VLCJSBridge.onSurfaceCreated(id, initialVisible);
                        }
                        Array.from(node.attributes).forEach(function (attr) {
                            if (window.VLCJSBridge.onSetAttribute) {
                                window.VLCJSBridge.onSetAttribute(id, attr.name, attr.value);
                            }
                        });
                    }
                    observer.observe(node);
                    resizeObserver.observe(node);
                }
            
                function unobservedNode(node) {
                    if (!node.id) return;
                    var id = node.id;
                    if (window.VLCJSBridge && window.VLCJSBridge.onSurfaceDestroyed) {
                        window.VLCJSBridge.onSurfaceDestroyed(id);
                    }
                }
            
                function scanExistingTags() {
                    var elements = document.getElementsByTagName('$tagName');
                    for (var i = 0; i < elements.length; i++) {
                        observeNode(elements[i]);
                    }
                }
            
                var mutObserver = new MutationObserver(function (mutations) {
                    mutations.forEach(function (mutation) {
                        if (mutation.type === 'childList') {
                            mutation.addedNodes.forEach(function (node) {
                                if (node.tagName && node.tagName.toLowerCase() === '$tagName') {
                                    observeNode(node);
                                } else if (node.querySelectorAll) {
                                    node.querySelectorAll('$tagName').forEach(observeNode);
                                }
                            });
                            mutation.removedNodes.forEach(function (node) {
                                if (node.tagName && node.tagName.toLowerCase() === '$tagName') {
                                    unobservedNode(node);
                                } else if (node.querySelectorAll) {
                                    node.querySelectorAll('$tagName').forEach(unobservedNode);
                                }
                            });
                        }
                        if (mutation.type === 'attributes' && mutation.target.tagName && mutation.target.tagName.toLowerCase() === '$tagName') {
                            var id = mutation.target.id;
                            var attrName = mutation.attributeName;
                            var attrValue = mutation.target.getAttribute(attrName) || "";
                            if (window.VLCJSBridge && window.VLCJSBridge.onSetAttribute) {
                                window.VLCJSBridge.onSetAttribute(id, attrName, attrValue);
                            }
                        }
                    });
                });
                var mustConfig = {childList: true, subtree: true};
                mustConfig.attributes = true;
                mutObserver.observe(document.body, mustConfig);
                scanExistingTags();
            })();
        """.trimIndent()

        post {
            evaluateJavascript(jsCode, null)
        }
    }

    override fun destroy() {
        this.isObserverInjected = false
        this.clearAnimation()
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

