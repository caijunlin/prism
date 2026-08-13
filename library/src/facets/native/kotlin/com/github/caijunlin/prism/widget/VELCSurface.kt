package com.github.caijunlin.prism.widget

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.caijunlin.prism.constant.HtmlAttribute
import com.github.caijunlin.prism.core.WebView
import com.github.caijunlin.prism.renderer.IVideoRenderClient
import com.github.caijunlin.prism.renderer.VLCRenderPool
import com.github.caijunlin.prism.vo.ElementRect
import org.json.JSONObject
import kotlin.math.ceil

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description :
 */
@SuppressLint("ViewConstructor")
class VELCSurface(
    val webView: WebView,
    attributes: Map<String, String> = emptyMap(),
    v: Boolean = false
) : FrameLayout(webView.context), TextureView.SurfaceTextureListener, IVideoRenderClient {

    private var _attributes = mutableMapOf<String, String>()

    val id: String get() = _attributes[HtmlAttribute.ID.key] ?: ""

    private val videoSrc: String get() = _attributes[HtmlAttribute.VIDEO_SRC.key] ?: ""

    private val videoType: String
        get() = _attributes[HtmlAttribute.VIDEO_TYPE.key] ?: ""

    private val videoData: String
        get() = _attributes[HtmlAttribute.VIDEO_DATA.key] ?: ""

    private val draggable: Int
        get() = _attributes[HtmlAttribute.DRAGGABLE.key]?.toIntOrNull() ?: 0

    private var rect: Rect? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var x5Surface: Surface? = null
    private var webViewRect = Rect()
    private var isVisibleAndActive = v
    private var decodingUrl: String? = null
    private var attachedUrl: String? = null

    @Volatile
    private var elementRect: ElementRect = ElementRect()

    private var dragShadowBitmap: Bitmap? = null

    private val textureView: TextureView = TextureView(context).apply {
        surfaceTextureListener = this@VELCSurface
        isOpaque = false
    }

    private val textView: TextView

    init {
        this._attributes.putAll(attributes)
        this.webViewRect = Rect(0, 0, webView.width, webView.height)

        addView(
            textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        textView = TextView(context).apply {
            typeface = Typeface.SANS_SERIF
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = false
        }
        addView(
            textView, LayoutParams(
                LayoutParams.MATCH_PARENT, 0
            ).apply {
                gravity = Gravity.BOTTOM
            })
        setBackgroundColor(Color.BLACK)
        visibility = GONE

        webView.addView(this)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        x5Surface = Surface(surface)

        val jsScript = """
            (function() {
                var videoNode = document.getElementById('$id');
                if (!videoNode || !videoNode.parentElement) return null;
                var nameNode = videoNode.parentElement.querySelector('.Video_name');
                if (!nameNode) return null;

                var style = window.getComputedStyle(nameNode);
                var rect = nameNode.getBoundingClientRect();

                var layoutW = parseFloat(style.width) || nameNode.offsetWidth || 1;
                var layoutH = parseFloat(style.height) || nameNode.offsetHeight || 1;

                var scaleX = rect.width / layoutW;
                var scaleY = rect.height / layoutH;

                function rgbaToHex(colorStr) {
                    var match = colorStr.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/);
                    if (!match) {
                        if (colorStr.startsWith('#') && colorStr.length === 9) {
                            return '#' + colorStr.substring(7, 9) + colorStr.substring(1, 7);
                        }
                        return colorStr; 
                    }
                    var r = parseInt(match[1]).toString(16).padStart(2, '0');
                    var g = parseInt(match[2]).toString(16).padStart(2, '0');
                    var b = parseInt(match[3]).toString(16).padStart(2, '0');
                    var a = Math.round((parseFloat(match[4] || '1')) * 255).toString(16).padStart(2, '0');
                    return '#' + a + r + g + b;
                }

                return JSON.stringify({
                    width: layoutW,
                    height: layoutH,
                    color: rgbaToHex(style.color),
                    fontSize: parseFloat(style.fontSize),
                    background: rgbaToHex(style.backgroundColor),
                    scaleX: scaleX,
                    scaleY: scaleY
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsScript) { result ->
            if (result.isNullOrEmpty() || result == "null") return@evaluateJavascript
            val jsonStr =
                result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val json = JSONObject(jsonStr)

            val fontSize = json.optDouble("fontSize", 14.0).toFloat()
            val fontColor = json.optString("color", "#FFFFFFFF")
            val background = json.optString("background", "#80000000")
            val webScaleX = json.optDouble("scaleX", 1.0).toFloat()
            val webScaleY = json.optDouble("scaleY", 1.0).toFloat()
            val density = webView.context.resources.displayMetrics.density
            val scaleY = if (webScaleY > 0) density / webScaleY else 1f
            val textViewHeight = json.optDouble("height", 0.0).toFloat()

            textView.post {
                textView.setTextColor(Color.parseColor(fontColor))
                textView.setBackgroundColor(Color.parseColor(background))
                val textSize = fontSize / scaleY
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
                textView.textScaleX = webScaleX / webScaleY
                textView.scaleX = 1f
                textView.scaleY = 1f
                val params = textView.layoutParams
                params.height = (textViewHeight * webScaleY).toInt()
                textView.layoutParams = params
                textView.requestLayout()
            }
        }

        syncRenderState()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val deadSurface = x5Surface
        x5Surface = null
        syncRenderState()
        deadSurface?.let {
            VLCRenderPool.releaseSurface(it)
            it.release()
        }
        return true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    private inline fun runOnUiThread(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            webView.post { action() }
        }
    }

    private fun syncRenderState() {
        val rawUrl = videoSrc.takeIf { it.isNotEmpty() }

        val effectiveUrl = if (isVisibleAndActive) rawUrl else null

        if (decodingUrl != effectiveUrl) {
            decodingUrl?.let { oldUrl ->
                Log.d("Prism", "Stop Decode Stream: $oldUrl $id")
                VLCRenderPool.stopDecodeTask(oldUrl, this)
            }
            effectiveUrl?.let { newUrl ->
                Log.d("Prism", "Start Decode Stream: $newUrl $id")
                VLCRenderPool.startDecodeTask(newUrl, this)
            }
            decodingUrl = effectiveUrl
        }

        val shouldBeVisible = effectiveUrl != null
        visibility = if (shouldBeVisible) VISIBLE else GONE
        val isSurfaceReady = x5Surface?.isValid == true && surfaceWidth > 0 && surfaceHeight > 0
        if (shouldBeVisible && isSurfaceReady) {
            if (attachedUrl != effectiveUrl) {
                attachedUrl?.let { oldAttachedUrl ->
                    VLCRenderPool.detachSurface(oldAttachedUrl, this, isClearUrl = false)
                }
                Log.d("Prism", "Attach Surface to Stream: $effectiveUrl  $id")
                VLCRenderPool.attachSurface(effectiveUrl, this)
                attachedUrl = effectiveUrl
            }
        } else {
            if (attachedUrl != null) {
                Log.d("Prism", "Detach Surface from Stream: $attachedUrl  $id")
                val isClear = effectiveUrl == null
                VLCRenderPool.detachSurface(attachedUrl!!, this, isClearUrl = isClear)
                attachedUrl = null
            }
        }
    }

    fun onRectChanged(rect: Rect) = runOnUiThread {
        if (this.rect != null && this.rect?.width() == rect.width() && this.rect?.height() == rect.height()) {
            return@runOnUiThread
        }
        this.rect = rect
        WidgetManager.getBoundingClientRect(webView, id) { xa, ya, wf, hf, _, _ ->
            val sx = 1.0
            val sy = 1.0
            elementRect.labelW = wf.toInt()
            elementRect.labelH = hf.toInt()
            elementRect.scaleX = sx
            elementRect.scaleY = sy

            val newWidth = ceil(wf * sx).toInt()
            val newHeight = ceil(hf * sy).toInt()
            if (newWidth <= 0 || newHeight <= 0) return@getBoundingClientRect
            val isSizeChanged = (surfaceWidth != newWidth || surfaceHeight != newHeight)
            surfaceWidth = newWidth
            surfaceHeight = newHeight

            val params = (layoutParams ?: ViewGroup.LayoutParams(width, height)).also {
                it.width = newWidth
                it.height = newHeight
            }
            layoutParams = params
            x = xa.toInt().toFloat()
            y = ya.toInt().toFloat()

            syncRenderState()
            if (isSizeChanged && attachedUrl != null) {
                VLCRenderPool.resizeClient(this@VELCSurface)
            }
        }
    }

    fun onSetAttribute(key: String, value: String): Boolean {
        _attributes[key] = value
        if (key == HtmlAttribute.VIDEO_SRC.key) {
            syncRenderState()
        } else if (key == HtmlAttribute.VIDEO_DATA.key) {
            if (videoData.isNotEmpty()) {
                textView.text = JSONObject(videoData).optString("name", "")
            }
        } else if (key == HtmlAttribute.DRAGGABLE.key) {
            if (draggable == 1 && value.toIntOrNull() == 0) {
                webView.cancelDragAndDrop()
            }
        }
        return true
    }

    override fun onVisibilityChanged(v: Boolean) = runOnUiThread {
        isVisibleAndActive = v
        syncRenderState()
    }

    override fun onVideoDropped(centerX: Float, centerY: Float, width: Int, height: Int) {
    }

    override fun onDestroy() = runOnUiThread {
        _attributes[HtmlAttribute.VIDEO_SRC.key] = ""
        syncRenderState()
        x5Surface?.let {
            VLCRenderPool.releaseSurface(it)
            it.release()
        }
        WidgetManager.removeWidget(id)
        textureView.surfaceTextureListener = null
        x5Surface = null
        dragShadowBitmap = null
        visibility = GONE
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun updateDragShadowBitmap(bitmap: Bitmap) {
        dragShadowBitmap = bitmap
    }

    override fun getDragShadowBitmap(): Bitmap? = dragShadowBitmap

    override fun getElementRect(): ElementRect = elementRect

    override fun getElementId(): String = id

    override fun isSameId(id: String): Boolean = this.id == id

    override fun isSameVideoType(videoType: String): Boolean = this.videoType == videoType

    override fun getSurface(): Surface? = x5Surface

    override fun getSurfaceWidth(): Int = surfaceWidth

    override fun getSurfaceHeight(): Int = surfaceHeight
}

