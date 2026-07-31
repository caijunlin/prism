# 隐藏真实的源文件名
-renamesourcefileattribute SourceFile

# 移除行号信息
-keepattributes Exceptions,InnerClasses,Signature,Deprecated

# 保留 Kotlin 的元数据，确保 Kotlin 协程、高阶函数、默认参数在外部能正常调用
-keep class kotlin.Metadata { *; }

## 只保留 X5Kit 的类名，以及它暴露给外部的 public 方法和变量
#-keep class com.caijunlin.vlcdecoder.X5Kit {
#    public <methods>;
#    public <fields>;
#}
#
## callback 包下的接口和类：同样只保留 public 的部分
#-keep class com.caijunlin.vlcdecoder.callback.** {
#    public <methods>;
#    public <fields>;
#}
#
## X5WebView 这个保持你的原样，写得很精准，只暴露构造函数
#-keep class com.caijunlin.vlcdecoder.core.X5WebView {
#    public <init>(...);
#}

-dontwarn java.lang.invoke.StringConcatFactory
