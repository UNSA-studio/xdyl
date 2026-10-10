# ================= 本项目 ProGuard / R8 规则 =================
# 目标：安全压缩体积（删除未使用代码 + 混淆第三方库），不破坏运行

# 1) 保留应用自身全部代码（Gson 反射、ViewBinding、匿名内部类安全）
-keep class www.xdyl.hygge.com.** { *; }
-keep interface www.xdyl.hygge.com.** { *; }

# 2) 第三方库保留（避免反射/序列化被裁）
-keep class com.google.gson.** { *; }
-keep class dadb.** { *; }
-dontwarn dadb.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 3) 保留必要属性（注解/签名/内部类）
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# 4) Android 组件不被误删
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 5) 枚举 / Parcelable
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 6) 协程
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
