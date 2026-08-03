package com.github.caijunlin.prism.gesture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.withScale

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 原生拖拽阴影构建器
 */
class BitmapDragShadowBuilder(
    /** 需要绘制的位图 */
    private val bitmap: Bitmap?,
    /** 触摸点的 X 坐标 */
    private val touchPointX: Int,
    /** 触摸点的 Y 坐标 */
    private val touchPointY: Int,
    /** 阴影的目标宽度 */
    private val targetWidth: Int,
    /** 阴影的目标高度 */
    private val targetHeight: Int
) : View.DragShadowBuilder() {

    /**
     * 提供阴影的度量信息
     * @param outShadowSize 输出阴影尺寸
     * @param outTouchPoint 输出触摸重心
     */
    override fun onProvideShadowMetrics(outShadowSize: Point, outTouchPoint: Point) {
        outShadowSize.set(targetWidth, targetHeight)
        outTouchPoint.set(touchPointX, touchPointY)
    }

    /**
     * 绘制阴影内容
     * @param canvas 画布
     */
    override fun onDrawShadow(canvas: Canvas) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            // 进行 Y 轴翻转绘制，修正坐标系差异
            canvas.withScale(1f, -1f, targetWidth / 2f, targetHeight / 2f) {
                val destRect = Rect(0, 0, targetWidth, targetHeight)
                drawBitmap(bmp, null, destRect, null)
            }
        }
    }

}