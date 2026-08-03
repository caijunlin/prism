package com.github.caijunlin.prism.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.caijunlin.prism.callback.Callback
import com.github.caijunlin.prism.util.LicenseManager
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.export.external.interfaces.IAuthRequestCallback
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsFramework
import com.tencent.smtt.sdk.X5Downloader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author : caijunlin
 * @date   : 2026/03/31
 * @description : X5内核生命周期管理类 (三段式流水线架构)
 */
object KernelManager {

    private val lock = Any()
    private var isFinished = false
    private var isSuccess = false
    private var cachedIsX5Core = false
    private var cachedErrCode = 0
    private var cachedErrMsg: String? = null
    private val callbacks = CopyOnWriteArrayList<Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var onLoad = false

    // 标记是否已经完成了全局基础配置，防止重试时重复配置
    @Volatile
    private var isEnvironmentPrepared = false

    fun registerCallback(callback: Callback) {
        var shouldDispatchNow = false
        var currentIsSuccess = false
        var currentIsX5Core = false
        var currentErrCode = 0
        var currentErrMsg: String? = null

        synchronized(lock) {
            if (isFinished) {
                shouldDispatchNow = true
                currentIsSuccess = isSuccess
                currentIsX5Core = cachedIsX5Core
                currentErrCode = cachedErrCode
                currentErrMsg = cachedErrMsg
            } else {
                if (!callbacks.contains(callback)) {
                    callbacks.add(callback)
                }
            }
        }

        if (shouldDispatchNow) {
            mainHandler.post {
                if (currentIsSuccess) callback.onSuccess(currentIsX5Core)
                else callback.onFailed(currentErrCode, currentErrMsg)
            }
        }
    }

    fun unregisterCallback(callback: Callback) {
        synchronized(lock) { callbacks.remove(callback) }
    }

    /**
     * 流水线总调度：初始化内核入口
     */
    @Synchronized
    fun initKernel(context: Context, authCode: String) {
        if (onLoad) return
        onLoad = true
        synchronized(lock) {
            isFinished = false
            isSuccess = false
        }

        // 第一阶段：全局环境与参数配置
        prepareEnvironment(context, authCode)

        // 检查授权状态进行分流
        val hasLocalAuth = LicenseManager.isAuthorized(context, authCode)
        if (hasLocalAuth) {
            // 已有授权：跳过安装，直接进入第三阶段
            doAuthAndInit(context)
        } else {
            // 无授权/首次：进入第二阶段 (安装 -> 鉴权)
            installAndAuth(context, authCode)
        }
    }

    /**
     * 环境与参数配置
     */
    private fun prepareEnvironment(context: Context, authCode: String) {
        if (isEnvironmentPrepared) return
        val map = HashMap<String, Any>()
        map[TbsCoreSettings.MULTI_PROCESS_ENABLE] = TbsCoreSettings.Render.MULTI_PROCESS_OPEN
        QbSdk.initTbsSettings(map)
        TbsFramework.setUp(context, authCode)
        QbSdk.usePrivateCDN(QbSdk.PrivateCDNMode.STANDARD_IMPL)
        isEnvironmentPrepared = true
    }

    /**
     * 执行内核安装
     */
    private fun installAndAuth(context: Context, authCode: String) {
        val kernelVersion = 48445
        val version = QbSdk.getTbsVersion(context)
        Log.d("VLCDecoder", "cv=$version iv=$kernelVersion")
        /** 判断是否需要安装或更新内核 */
        val needInstallOrUpdateX5 = version != kernelVersion

        if (needInstallOrUpdateX5) {
            // 创建下载器实例
            val downloader = object : X5Downloader(context) {
                override fun onFinished() {
                    Log.d("VLCDecoder", "Inst done, prep auth")
                    doAuthAndInit(context)
                }

                override fun onFailed(code: Int, msg: String?) {
                    Log.e("VLCDecoder", "Inst fail: $msg $code")
                    dispatchFailed(context, code, msg)
                }
            }

            val kernelFile = "tbs_core_0${kernelVersion}_arm64-v8a.tbs"
            downloader.setTargetX5Version(kernelVersion)
            val outFile = File(context.cacheDir, kernelFile)

            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(kernelFile).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("VLCDecoder", "Copy done")
            } else {
                Log.d("VLCDecoder", "Cache exist, skip copy")
            }
            downloader.installX5(outFile)
        } else {
            // 重置本地缓存并去鉴权
            LicenseManager.resetAuth(context, authCode)
            doAuthAndInit(context)
        }
    }

    /**
     * 鉴权与引擎唤醒
     */
    private fun doAuthAndInit(context: Context) {
        TbsFramework.authenticateX5(true, object : IAuthRequestCallback {
            override fun onResponse(license: String?) {
                QbSdk.preInit(context, object : QbSdk.PreInitCallback {
                    override fun onCoreInitFinished() {
                        Log.d("VLCDecoder", "Core init done")
                    }

                    override fun onViewInitFinished(isX5Core: Boolean) {
                        Log.d("VLCDecoder", "View init done, x5: $isX5Core")
                        dispatchSuccess(context, isX5Core)
                    }
                })
            }

            override fun onFailed(code: Int, msg: String?) {
                Log.e("VLCDecoder", "Auth fail: $msg $code")
                dispatchFailed(context, code, msg)
            }
        })
    }

    private fun dispatchSuccess(context: Context, isX5Core: Boolean) {
        val callbacksToNotify: List<Callback>
        synchronized(lock) {
            isFinished = true
            isSuccess = true
            onLoad = false
            cachedIsX5Core = isX5Core

            callbacksToNotify = callbacks.toList()
        }
        LicenseManager.saveAuthorizedState(context, isX5Core)
        VLCEngineManager.init(context)
        mainHandler.post { callbacksToNotify.forEach { it.onSuccess(isX5Core) } }
    }

    private fun dispatchFailed(context: Context, code: Int, msg: String?) {
        val callbacksToNotify: List<Callback>
        synchronized(lock) {
            isFinished = true
            isSuccess = false
            onLoad = false
            cachedErrCode = code
            cachedErrMsg = msg

            callbacksToNotify = callbacks.toList()
        }
        LicenseManager.saveAuthorizedState(context, false)
        mainHandler.post { callbacksToNotify.forEach { it.onFailed(code, msg) } }
    }

    fun release(context: Context) {
        QbSdk.clearAllWebViewCache(context, true)
        synchronized(lock) {
            callbacks.clear()
            isFinished = false
            isSuccess = false
            onLoad = false
            isEnvironmentPrepared = false
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

}