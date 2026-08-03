package com.github.caijunlin.prism.renderer

import android.graphics.Bitmap
import android.view.Surface
import com.github.caijunlin.prism.vo.ElementRect

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 视频渲染客户端接口用于取代繁琐的参数传递，并接收底层引擎异步反馈的真实业务状态。
 */
interface IVideoRenderClient {

    /** 获取元素的唯一 ID */
    fun getElementId(): String

    /**
     * 判定 ID 是否相同（内部处理空安全与逻辑对比）
     */
    fun isSameId(id: String): Boolean

    /**
     * 判定视频业务类型是否相同
     */
    fun isSameVideoType(videoType: String): Boolean

    /**
     * 获取缓存的布局缩放数据，消除拖拽时的异步查询等待
     * @return 实际标签的宽高缩放数据
     */
    fun getElementRect(): ElementRect

    /**
     * 获取目标物理画布
     * @return 原生 Surface 对象
     */
    fun getSurface(): Surface?

    /**
     * 获取画布当前物理宽度
     * @return 宽度像素值
     */
    fun getSurfaceWidth(): Int

    /**
     * 获取画布当前物理高度
     * @return 高度像素值
     */
    fun getSurfaceHeight(): Int

    /**
     * 更新最新的拖拽专用缩略图
     * @param bitmap 缩略图
     */
    fun updateDragShadowBitmap(bitmap: Bitmap)

    /**
     * 获取最新的缩略图 (0延迟)
     * @return 位图
     */
    fun getDragShadowBitmap(): Bitmap?

    /**
     * 当该视频控件被拖拽并成功落地时，由全局管理器直接回调此方法
     * @param centerX 落点中心 X 坐标（相对于 WebView）
     * @param centerY 落点中心 Y 坐标（相对于 WebView）
     * @param width   拖拽阴影宽度
     * @param height  拖拽阴影高度
     */
    fun onVideoDropped(centerX: Float, centerY: Float, width: Int, height: Int)

    fun onVisibilityChanged(v: Boolean)

    fun onDestroy()

}
