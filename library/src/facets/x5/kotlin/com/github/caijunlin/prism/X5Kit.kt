package com.github.caijunlin.prism

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.Keep
import com.github.caijunlin.prism.callback.Callback
import com.github.caijunlin.prism.core.KernelManager
import com.github.caijunlin.prism.renderer.VLCRenderPool
import com.github.caijunlin.prism.util.LicenseManager
import com.github.caijunlin.prism.widget.WidgetManager

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 对外暴露的函数
 */
@Keep
object X5Kit {

    const val LICENSE_KEY: String = "com.github.caijunlin.x5.license"

    /**
     * 初始化引擎
     * @param context 上下文
     */
    @Keep
    @JvmStatic
    fun init(
        context: Context
    ) {
        try {
            val authCode = getAuthCode(context)
            if (authCode.isEmpty()) {
                Log.e("VLCDecoder", "AuthCode empty")
                return
            }
            // 授权开启内核
            KernelManager.initKernel(context, authCode)
            Log.d(
                "VLCDecoder",
                "Init Success: auth=***${
                    authCode.drop(authCode.length / 3).dropLast(authCode.length / 3)
                }***"
            )
        } catch (e: Exception) {
            Log.e("VLCDecoder", "Init Failed: ${e.message}")
        }
    }

    fun getAuthCode(context: Context): String {
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            return appInfo.metaData?.getString(LICENSE_KEY, "") ?: ""
        } catch (e: Exception) {
            Log.e("VLCDecoder", "Failed to get meta-data: $LICENSE_KEY", e)
        }
        return ""
    }

    /**
     * 注册内核监听回调
     * @param callback 回调接口
     */
    @Keep
    @JvmStatic
    fun registerCallback(callback: Callback) {
        try {
            KernelManager.registerCallback(callback)
            Log.d("VLCDecoder", "Callback registered.")
        } catch (e: Exception) {
            Log.e("VLCDecoder", "Register callback error: ${e.message}")
        }
    }

    /**
     * 注销内核监听回调
     * 在组件（如 Activity/Fragment）销毁时调用，防止回调持有 Context 导致内存泄漏
     * @param callback 回调接口
     */
    @Keep
    @JvmStatic
    fun unregisterCallback(callback: Callback) {
        try {
            KernelManager.unregisterCallback(callback)
            Log.d("VLCDecoder", "Callback unregistered.")
        } catch (e: Exception) {
            Log.e("VLCDecoder", "Unregister callback error: ${e.message}")
        }
    }

    /**
     * 获取当前鉴权码的授权状态
     * @param context 上下文
     * @return 授权状态
     */
    @Keep
    @JvmStatic
    fun isAuthorized(
        context: Context
    ): Boolean {
        val authCode = getAuthCode(context)
        if (authCode.isEmpty()) {
            Log.e("VLCDecoder", "AuthCode empty")
            return false
        }
        return LicenseManager.isAuthorized(context, authCode)
    }

    /**
     * 软释放：关闭当前工程（或退出当前浏览器页面）时调用。
     */
    @Keep
    @JvmStatic
    fun releaseRender() {
        try {
            WidgetManager.clearAll()
            VLCRenderPool.releaseWorkspace()
            Log.d("VLCDecoder", "Soft release done.")
        } catch (e: Exception) {
            Log.e("VLCDecoder", "Soft release error: ${e.message}")
        }
    }

    /**
     * 终极核平指令：彻底退出 App 时调用。
     * @param context 上下文
     */
    @Keep
    @JvmStatic
    fun releaseAll(context: Context) {
        try {
            WidgetManager.clearAll()
            VLCRenderPool.release()
            KernelManager.release(context)
            Log.d("VLCDecoder", "All resources released.")
        } catch (e: Exception) {
            Log.e("VLCDecoder", "ReleaseAll error: ${e.message}")
        }
    }

}