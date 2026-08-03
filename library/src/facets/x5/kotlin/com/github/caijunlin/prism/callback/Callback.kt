package com.github.caijunlin.prism.callback

/**
 * @author : caijunlin
 * @date   : 2026/03/18 15:32
 * @description : 外部统一的内核初始化状态回调抽象类
 */
abstract class Callback {

    /**
     * 初始化成功回调
     * @param success 是否初始化成功
     */
    open fun onSuccess(success: Boolean) {
        done()
    }

    /**
     * 初始化失败回调
     * @param code 错误码
     * @param msg 错误信息
     */
    open fun onFailed(code: Int, msg: String?) {
        done()
    }

    /**
     * 最终完成
     */
    open fun done() {}

}