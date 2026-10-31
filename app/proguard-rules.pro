# MAXNET5G VPN ProGuard / R8 Security & Serialization Rules
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, *Annotation*
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.zxing.**

# Keep Application Entry Points, Activities, VPN Services, TileService, and BroadcastReceivers
-keep class com.example.MainActivity { *; }
-keep class com.example.vpn.** { *; }
-keep class com.example.worker.** { *; }

# Keep Room Database, Entities, and DAOs
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.data.local.** { *; }

# Keep Domain Models, Parser, and Core Engine Adapters
-keep class com.example.domain.model.** { *; }
-keep class com.example.core.** { *; }
-keep class com.example.parser.** { *; }
-keep class com.example.security.** { *; }

# Strip verbose debug logs in release builds to prevent credential leakage
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
