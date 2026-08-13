# ====================================================================
# 此规则会自动应用到集成 Prism SDK 的宿主 App 构建过程中
# ====================================================================

# 保留对外暴露的所有 Callback 回调接口与数据模型
-keep public class com.github.caijunlin.prism.callback.** {
    public <methods>;
    public <fields>;
}

# 保留自定义 WebView/View，保证宿主通过 XML 布局或代码调用时不被剥离/改名
-keep class com.github.caijunlin.prism.core.WebView {
    public <init>(...);
    public <methods>;
}

# 保留 Prism SDK 对外暴露的所有公共 Class 及 Public/Protected 属性与方法
-keep public class com.github.caijunlin.prism.** {
    public protected <methods>;
    public protected <fields>;
}

# 保留底层 C++ (JNI) 需要硬编码查找的核心数据类和播放控制类
-keep class org.videolan.libvlc.AWindow { *; }
-keep class org.videolan.libvlc.AWindow$* { *; }
-keep class org.videolan.libvlc.util.AndroidUtil { *; }
-keep class org.videolan.libvlc.util.** { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }
-keep class org.videolan.libvlc.LibVLC { *; }
-keep class org.videolan.libvlc.Media { *; }
-keep class org.videolan.libvlc.Media$* { *; }
-keep class org.videolan.libvlc.MediaPlayer { *; }
-keep class org.videolan.libvlc.MediaPlayer$* { *; }
-keep class org.videolan.libvlc.RendererItem { *; }
-keep class org.videolan.libvlc.Dialog { *; }
-keep class org.videolan.libvlc.Dialog$* { *; }
-keep class org.videolan.libvlc.MediaDiscoverer { *; }
-keep class org.videolan.libvlc.RendererDiscoverer { *; }
-keep class org.videolan.libvlc.MediaList { *; }
-keep class org.videolan.libvlc.VLCObject { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }

# 保护所有包含了 native 方法的类和 native 方法本身不被混淆
-keepclasseswithmembernames class org.videolan.libvlc.** {
    native <methods>;
}

# 腾讯 X5 WebView (TBS) 混淆规则
-keep class com.tencent.smtt.** { *; }
-keep class com.tencent.tbs.** { *; }

-dontwarn dalvik.**
-dontwarn org.videolan.**
-dontwarn com.tencent.smtt.**
-dontwarn com.github.caijunlin.prism.**