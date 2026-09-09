package com.daydreamvr.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import android.os.Handler
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

class FfmpegExtensionTest {

    @Test
    fun ffmpegLibraryClassIsPresentOnClasspath() {
        val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun ffmpegAudioRendererClassIsPresentWithExpectedConstructor() {
        val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        assertThat(clazz).isNotNull()
        val constructor = clazz.getConstructor(
            Handler::class.java,
            AudioRendererEventListener::class.java,
            AudioSink::class.java,
        )
        assertThat(constructor).isNotNull()
    }
}
