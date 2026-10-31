# MAXNET5G VPN ProGuard / R8 Security & Serialization Rules
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Keep Room Entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep interface com.example.data.local.** { *; }

# Keep Core Models & JSON Serialization
-keep class com.example.domain.model.** { *; }
-keep class com.example.core.xray.** { *; }
-keep class com.example.core.singbox.** { *; }

# Strip verbose debug logs in release builds to prevent credential leakage
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
