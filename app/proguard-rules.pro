# ---------------------------------------------------------------------------
# 高德地图 SDK（11.3.100）
#
# SDK 以 classes.jar 形式提供，没有 consumer rules，且内部大量使用反射 + JNI 回调。
# 不 keep 的话表现是：编译通过、启动正常，一打开地图就白屏或直接崩在 native 层。
# ---------------------------------------------------------------------------
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**

# 高德 jar 里 location 模块引用了未随包发布的 GNSS 类，
# 不抑制的话 R8 会直接判构建失败（不是"警告"级别的失败，是整个 minify 任务挂掉）。
-dontwarn com.amap.ams.**
-dontwarn com.amap.location.**

# JNI 通过类名/方法名查找，必须保留 native 方法所在类
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Room
# 生成的 _Impl 类由 Room 反射实例化；Room 2.8 自带规则，这里补一条兜底。
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# ---------------------------------------------------------------------------
# Kotlin 协程 / OkHttp
# OkHttp 4 与 Okio 都带 consumer rules，这里只压掉可选依赖的警告。
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
