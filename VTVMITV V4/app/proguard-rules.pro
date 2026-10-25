# --- Giữ lại Data Model để Gson không bị lỗi parse JSON cache ---
-keepclassmembers class com.xiaomi.vtvmitv.Channel { *; }
-keep class com.xiaomi.vtvmitv.Channel { *; }

# --- Cấu hình cho Gson ---
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }

# --- Cấu hình cho ExoPlayer / Media3 ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Cấu hình cho OkHttp & Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# --- Cấu hình cho Coil (Load ảnh/logo) ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- Cấu hình cho Jetpack Compose ---
-keepclassmembers class * extends androidx.compose.runtime.State { *; }

# --- Giữ nguyên toàn bộ ID Drawable ---
-keepclassmembers class **.R$* {
    public static <fields>;
}

# --- Cầu nối Launcher TV & Watch Next ---
-keep class com.xiaomi.vtvmitv.LauncherChannelBridge { *; }
-keep class androidx.tvprovider.media.tv.** { *; }

# --- Giữ lại Voice Suggestion Provider ---
-keep class com.xiaomi.vtvmitv.VoiceSuggestionProvider { *; }