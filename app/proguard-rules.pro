# ── Kotlin / Coroutines ─────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**


# ── Room ─────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }
-dontwarn androidx.room.**

# ── Gson / Serialization ─────────────────────────────────────────────────
# Keep all data model classes — Gson uses reflection to access fields
-keep class com.m57.hermescontrol.data.model.** { *; }
-keep class com.m57.hermescontrol.data.ws.JsonRpc* { *; }
-keep class com.m57.hermescontrol.data.ws.WsEvent* { *; }
-keep class com.m57.hermescontrol.data.ws.WsMethods { *; }
-keep class com.m57.hermescontrol.ui.chat.ChatMessage { *; }
-keep class com.m57.hermescontrol.ui.chat.MessageRole { *; }
-keep class com.m57.hermescontrol.ui.chat.ToolStatus { *; }

# Gson itself
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# Generic type signatures used by Gson
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── OkHttp / Retrofit ────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ── AndroidX Security Crypto ─────────────────────────────────────────────
-dontwarn androidx.security.crypto.**

# ── Compose ───────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── BuildConfig ───────────────────────────────────────────────────────────
-keep class com.m57.hermescontrol.BuildConfig { *; }

# ── Navigation (NavKey Names) ─────────────────────────────────────────────
# Keep screen object class names because their simpleNames are used for bottom nav selection and storage.
-keep class * implements androidx.navigation3.runtime.NavKey { *; }

