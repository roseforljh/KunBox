# ====================================================================
# KunBox ProGuard Configuration
# 遵循最小权限原则: 仅保留必要的类和成员,允许混淆提高安全性
# ====================================================================

# General rules - 保留调试信息以便问题诊断
-keepattributes Signature,Exceptions,*Annotation*,SourceFile,LineNumberTable

# SnakeYAML rules - YAML 解析库
-dontwarn java.beans.**

# ====================================================================
# Native Libraries (JNI/Gomobile)
# ====================================================================

# Go (Gomobile) - gomobile 生成的 Go-Java 绑定代码
# 必须保留所有成员,因为 Go 代码通过反射调用
-keep class go.** { *; }
-dontwarn go.**

# sing-box (libbox) - 核心 VPN 库
# 必须保留所有成员,JNI 调用需要完整的类/方法签名
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# ====================================================================
# Android Components (必须保留,系统通过反射调用)
# ====================================================================

# Application
-keep class com.kunk.singbox.SingBoxApplication { *; }

# MainActivity - 应用入口点
-keep class com.kunk.singbox.MainActivity { *; }

# Services - Android 系统通过 AndroidManifest 注册的服务
-keep class com.kunk.singbox.service.SingBoxService { *; }
-keep class com.kunk.singbox.service.ProxyOnlyService { *; }
-keep class com.kunk.singbox.ipc.SingBoxIpcService { *; }
-keep class com.kunk.singbox.service.VpnTileService { *; }

# BroadcastReceivers - 动态注册的 Receiver
-keep class com.kunk.singbox.service.SingBoxService$*Receiver { *; }

# WorkManager Workers - 后台任务调度
-keep class com.kunk.singbox.service.*Worker { *; }
-keep class com.kunk.singbox.worker.*Worker { *; }
-keepclassmembers class com.kunk.singbox.service.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class com.kunk.singbox.worker.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ====================================================================
# Data Models (JSON 序列化/反序列化)
# ====================================================================

# Sing-box 配置模型 - Gson 通过反射访问字段
# 保留所有字段名和 @SerializedName 注解,但允许混淆方法
-keep class com.kunk.singbox.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.kunk.singbox.model.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 禁止 repackage model 类，避免包名丢失导致的初始化错误
-keeppackagenames com.kunk.singbox.model.**

# TrafficRepository 数据类 - Gson 序列化需要保留字段名
-keep class com.kunk.singbox.repository.NodeTrafficStats {
    <fields>;
    <init>(...);
}
-keep class com.kunk.singbox.repository.DailyTrafficRecord {
    <fields>;
    <init>(...);
}
-keep class com.kunk.singbox.repository.TrafficSummary {
    <fields>;
    <init>(...);
}
-keep class com.kunk.singbox.repository.TrafficPeriod {
    <fields>;
    <init>(...);
}

# ====================================================================
# AIDL Interfaces (进程间通信)
# ====================================================================

# AIDL 生成的接口和 Stub 类必须完整保留
-keep interface com.kunk.singbox.ipc.** { *; }
-keep class com.kunk.singbox.ipc.**$Stub { *; }
-keep class com.kunk.singbox.ipc.**$Stub$Proxy { *; }

# ====================================================================
# Gson (JSON 序列化库)
# ====================================================================

-keepattributes Signature, EnclosingMethod, InnerClasses

# Gson 核心类
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 泛型类型支持
-keep public class * implements java.lang.reflect.Type

# ====================================================================
# Kotlin (协程、反射)
# ====================================================================

# Kotlin 协程内部类 - 状态机需要保留
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin 反射支持
-keep class kotlin.Metadata { *; }

# ====================================================================
# OkHttp / Retrofit (网络库)
# ====================================================================

# OkHttp 平台检测
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ====================================================================
# Suppress Warnings (已知安全的警告)
# ====================================================================

# Kotlin 内部类
-dontwarn d0.**

# Java 9+ 模块系统
-dontwarn java.lang.invoke.StringConcatFactory

# ====================================================================
# Optimization (优化选项)
# ====================================================================

# 启用代码优化(移除未使用代码)
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 允许重新打包类到顶级包(减小 APK 体积)
# 注意：排除关键包避免 Package.getName() 返回 null 导致崩溃
# 完全禁用 repackage 以彻底解决包名丢失问题（增加约1-2MB APK大小）
# -repackageclasses ''
# -keeppackagenames com.kunk.singbox.**
# -keeppackagenames com.google.gson.**
# -keeppackagenames kotlin.**
# -keeppackagenames kotlinx.**

# 允许属性优化
-allowaccessmodification
