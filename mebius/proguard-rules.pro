# R8/ProGuard rules used when building the library itself in release mode.
# Consumer-facing rules live in consumer-rules.pro.

-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
