# Add project specific ProGuard rules here.
# Keep Media3 / FFmpeg extension entry points reachable from native code.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.media3ext.**
