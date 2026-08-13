package com.github.caijunlin.prism.callback

import androidx.annotation.Keep
import android.webkit.ValueCallback as OValueCallback

/**
 * @author : caijunlin
 * @date   : 2026/8/13
 * @description   : 空实现
 */
@Keep
interface ValueCallback<T> : OValueCallback<T>