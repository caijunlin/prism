package com.github.caijunlin.prism.vo

import com.github.caijunlin.prism.renderer.IVideoRenderClient

/**
 * @author : caijunlin
 * @date   : 2026/3/19
 * @description   : 拖拽会话状态
 */
data class DragSessionState(

    /**
     * 渲染器
     */
    val client: IVideoRenderClient,

    /**
     * 宽
     */
    val width: Int,

    /**
     * 高
     */
    val height: Int,

    /**
     * 触摸偏移X
     */
    val touchOffsetX: Float,

    /**
     * 触摸偏移Y
     */
    val touchOffsetY: Float,

    /**
     * 最后的视觉X
     */
    var lastVisualX: Float,

    /**
     * 最后的视觉Y
     */
    var lastVisualY: Float

)