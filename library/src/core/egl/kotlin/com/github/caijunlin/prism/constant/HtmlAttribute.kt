package com.github.caijunlin.prism.constant

/**
 * @author : caijunlin
 * @date   : 2026/3/27
 * @description   : HTML 及自定义组件标签属性定义，这里统一使用小写
 */
enum class HtmlAttribute(val key: String) {

    /**
     * 标签id
     */
    ID("id"),

    /**
     * 视频源
     */
    VIDEO_SRC("video-src"),

    /**
     * 视频类型分为player和source
     */
    VIDEO_TYPE("videoType".lowercase()),

    /**
     * 播放数据JSON格式字符串
     */
    VIDEO_DATA("videoData".lowercase()),

    /**
     * 1标示可拖拽
     */
    DRAGGABLE("_draggable".lowercase());
}