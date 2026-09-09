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
# DefaultRenderersFactory instantiates extension renderers via
# Class.forName(...).getConstructor(...) — keep their constructors and names.
# (Media3 also ships consumer rules; these cover the ffmpeg audio extension
# named explicitly in ExoVideoPlayer's EXTENSION_RENDERER_MODE_PREFER path.)
-keepnames class androidx.media3.** { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keepclassmembers class * extends androidx.media3.exoplayer.Renderer {
    <init>(...);
}
# Media3 parcelables / flag enums touched reflectively by the session code.
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

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
