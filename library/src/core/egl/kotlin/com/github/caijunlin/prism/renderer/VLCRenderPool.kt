package com.github.caijunlin.prism.renderer

import android.view.Surface
import com.github.caijunlin.prism.core.VLCEngineManager
import com.github.caijunlin.prism.gles.RenderNode

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 视频渲染全局指令路由池。
 * 现已升级为纯粹的“声明式指令下发中枢”，彻底剥离生命周期状态维护，完全由组件层的状态机单向驱动。
 */
object VLCRenderPool {

    /** 默认的媒体配置参数 */
    private val defaultMediaArgs = arrayListOf(
        ":network-caching=300",
        ":drop-late-frames",
        ":input-repeat=65535"
    )

    /** 延迟初始化的唯一核心渲染节点实例 */
    private val renderNode: RenderNode by lazy {
        RenderNode("Render Node")
    }

    /**
     * 后台纯解流指令
     * 请求底层立刻开启对应 URL 的网络拉流与硬件解码。
     * 即使当前没有任何画布，流也会在后台默默准备。
     * @param url 目标视频地址
     * @param client 请求发起的客户端
     * @param mediaOptions 媒体配置
     */
    fun startDecodeTask(
        url: String,
        client: IVideoRenderClient,
        mediaOptions: ArrayList<String> = defaultMediaArgs
    ) {
        if (url.isEmpty() || VLCEngineManager.libVLC == null) return
        renderNode.handler.post {
            renderNode.handleStartDecode(url, client, mediaOptions)
        }
    }

    /**
     * 停止解流指令
     * 宣告当前客户端不再需要该 URL 的流数据。
     * 若底层判定该流已无任何组件订阅，则自动销毁释放网络和解码器。
     * @param url 目标视频地址
     * @param client 请求撤销的客户端
     */
    fun stopDecodeTask(url: String, client: IVideoRenderClient) {
        if (url.isEmpty()) return
        renderNode.handler.post {
            renderNode.handleStopDecode(url, client)
        }
    }

    /**
     * 画布挂载指令
     * 将 HTML 的物理画布送入 GPU 渲染队列，与目标解码流强行接驳。
     * @param url 目标挂载的数据流地址
     * @param client 提供 Surface 画布的客户端
     */
    fun attachSurface(url: String, client: IVideoRenderClient) {
        val x5Surface = client.getSurface() ?: return
        renderNode.handler.post {
            renderNode.handleAttachSurface(url, x5Surface, client)
        }
    }

    /**
     * 画布卸载指令
     * 将物理画布从 GPU 渲染队列中踢出，停止画面更新。
     * @param url 之前挂载的数据流地址
     * @param client 拥有该画布的客户端
     * @param isClearUrl 是否因为业务彻底剥离了流地址而卸载（true 则将 FBO 黑底化防残影）
     */
    fun detachSurface(url: String, client: IVideoRenderClient, isClearUrl: Boolean) {
        val x5Surface = client.getSurface() ?: return
        renderNode.handler.post {
            renderNode.handleDetachSurface(url, x5Surface, isClearUrl)
        }
    }

    /**
     * 显存物理超度指令
     * 当 HTML 标签被销毁，X5 底层 Surface 灭亡时调用。
     * 彻底清理 EGLSurface 占用，防显存泄漏。
     * @param surface 彻底报废的物理表面
     */
    fun releaseSurface(surface: Surface) {
        renderNode.handler.post {
            renderNode.handleReleaseSurface(surface)
        }
    }

    /**
     * 视口刷新指令
     * 应对 HTML 标签高频宽度的形变。
     * @param client 发起形变的客户端
     */
    fun resizeClient(client: IVideoRenderClient) {
        val x5Surface = client.getSurface() ?: return
        renderNode.handler.post {
            renderNode.handleResize(x5Surface, client)
        }
    }

    /**
     * 打印 VLC 及 EGL 运行状态信息树
     */
    fun printVLC() {
        renderNode.handler.post { renderNode.printNodeDiagnostics(0) }
    }

    /**
     * 软释放所有工作流
     */
    fun releaseWorkspace() {
        renderNode.handler.post { renderNode.clearWorkspace() }
    }

    /**
     * 彻底销毁渲染池全部线程与资源
     */
    fun release() {
        renderNode.destroyNode()
    }
}