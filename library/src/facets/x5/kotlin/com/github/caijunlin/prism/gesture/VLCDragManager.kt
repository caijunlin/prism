package com.github.caijunlin.prism.gesture

import android.view.DragEvent
import com.github.caijunlin.prism.vo.DragSessionState

/**
 * @author : caijunlin
 * @date   : 2026/03/18
 * @description : 全局视频拖拽状态中枢
 */
object VLCDragManager {

    var activeSession: DragSessionState? = null

    fun processDragEvent(event: DragEvent): Boolean {
        val state = activeSession ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_LOCATION -> {
                state.lastVisualX = event.x
                state.lastVisualY = event.y
                true
            }

            DragEvent.ACTION_DROP -> {
                val imgTopLeftX = state.lastVisualX - state.touchOffsetX
                val imgTopLeftY = state.lastVisualY - state.touchOffsetY
                val centerX = imgTopLeftX + (state.width / 2f)
                val centerY = imgTopLeftY + (state.height / 2f)
                state.client.onVideoDropped(centerX, centerY, state.width, state.height)
                true
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                activeSession = null
                true
            }

            else -> true
        }
    }
}