# Consumer ProGuard/R8 rules for the Mebius Android SDK.
# These are applied automatically to apps that depend on this library.

# --- Public Mebius API surface: keep names so reflection / KDoc-driven tools work. ---
-keep public class io.mebius.sdk.Mebius { public *; }
-keep public class io.mebius.sdk.MebiusClient { public *; }
-keep public class io.mebius.sdk.MebiusBroadcaster { public *; }
-keep public class io.mebius.sdk.MebiusPlayer { public *; }
-keep public class io.mebius.sdk.MebiusVideoView { public *; }
-keep public class io.mebius.sdk.PlaybackMode { *; }
-keep public class io.mebius.sdk.MebiusError { *; }
-keep public class io.mebius.sdk.MebiusError$* { *; }
-keep public interface io.mebius.sdk.MebiusClientListener { *; }
-keep public interface io.mebius.sdk.MebiusBroadcasterListener { *; }
-keep public interface io.mebius.sdk.MebiusPlayerListener { *; }
-keep public class io.mebius.sdk.** {
    public protected *;
}

# --- libwebrtc (org.webrtc) relies heavily on JNI; keep its classes. ---
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- androidx.media3 (ExoPlayer) ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin coroutines internals referenced via reflection.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
