# fclcore 模块混淆保留规则（GPL-3.0 vendored from FCL-Team/FoldCraftLauncher）
# Gson 反序列化模型类
-keep class com.tungsten.fclcore.** { *; }
-keep class com.tungsten.fclauncher.utils.FCLPath { *; }
-keep class com.tungsten.fcl.FCLApp { *; }
-keep class com.mio.** { *; }

# Gson 反射需要保留注解字段名
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# 第三方库
-dontwarn org.glavo.chardet.**
-dontwarn org.jenkinsci.constant_pool_scanner.**
-dontwarn com.github.junrar.**
-dontwarn fi.iki.elonen.**
-dontwarn com.sun.nio.zipfs.**
-dontwarn org.jetbrains.annotations.**
-dontwarn androidx.**