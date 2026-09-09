# R8 full-mode keep rules for the release build (ARCHITECTURE.md §18).
#
# Only three things in this app defeat static analysis: Media3 loads its
# extension renderers by reflection, kotlinx.serialization generates synthetic
# serializer classes, and the GL layer calls into native code. Everything else
# (our own state machine, the pure upnp module, vrcore math) is reachable
# normally and must be allowed to shrink.

# ---------------------------------------------------------------------------
# AndroidX Media3 / ExoPlayer
# ---------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

# ---------------------------------------------------------------------------
# Media3 FFmpeg decoder extension (docs/FORMAT_SUPPORT_PLAN.md §4.5, §8.5)
# ---------------------------------------------------------------------------
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(android.os.Handler,
                  androidx.media3.exoplayer.audio.AudioRendererEventListener,
                  androidx.media3.exoplayer.audio.AudioSink);
}
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}

# ---------------------------------------------------------------------------
# LibVLC (docs/FORMAT_SUPPORT_PLAN.md §5, §8.6)
#
# libvlcjni.so resolves Java classes, fields and methods BY NAME through JNI.
# Any rename or removal is a native crash with no Java stack trace.
# ---------------------------------------------------------------------------
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
-keepclasseswithmembernames class org.videolan.libvlc.** {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Playback module
# ---------------------------------------------------------------------------
-keep class com.daydreamvr.playback.** { *; }
-keep interface com.daydreamvr.playback.** { *; }
-keepclassmembers class com.daydreamvr.playback.** { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

# Keep every generated $$serializer and the Companion that hands it out.
-keepclassmembers class **$$serializer { *; }

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Our persisted blobs specifically (SettingsStore / ServerStore).
-keep @kotlinx.serialization.Serializable class com.daydreamvr.player.data.** { *; }

-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# OpenGL ES / EGL
# ---------------------------------------------------------------------------
# GLSurfaceView.Renderer is a framework interface (kept by the SDK rules); our
# renderers are wired by direct reference, not reflection. Native-bound members
# and the EGL/GL10 interop types just need to be left alone.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn javax.microedition.khronos.**
-keep class javax.microedition.khronos.** { *; }

# ---------------------------------------------------------------------------
# Kotlin / coroutines housekeeping
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# UPnP / Networking / XML
# ---------------------------------------------------------------------------
-keep class com.daydreamvr.upnp.** { *; }
-keep interface com.daydreamvr.upnp.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**
