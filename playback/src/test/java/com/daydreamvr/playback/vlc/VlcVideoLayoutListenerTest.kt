package com.daydreamvr.playback.vlc

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * Guards the fallback-engine projection fix: once LibVLC reports the real decoded
 * dimensions via [IVLCVout.OnNewVideoLayoutListener.onNewVideoLayout], the
 * listener must re-apply them with [IVLCVout.setWindowSize] instead of leaving
 * VLC scaling into the placeholder window set before the stream was parsed.
 */
class VlcVideoLayoutListenerTest {

    @Test
    fun onNewVideoLayout_reappliesRealDimensionsToWindowSize() {
        val vout = RecordingVout()
        val reported = mutableListOf<Pair<Int, Int>>()
        val listener = newVideoLayoutListener { w, h -> reported += w to h }

        listener.onNewVideoLayout(vout, 3840, 2160, 3840, 2160, 1, 1)

        assertThat(vout.windowSizes).containsExactly(3840 to 2160)
        assertThat(reported).containsExactly(3840 to 2160)
    }

    @Test
    fun onNewVideoLayout_ignoresDegenerateLayout() {
        val vout = RecordingVout()
        val reported = mutableListOf<Pair<Int, Int>>()
        val listener = newVideoLayoutListener { w, h -> reported += w to h }

        listener.onNewVideoLayout(vout, 0, 0, 0, 0, 0, 0)
        listener.onNewVideoLayout(vout, 1920, 0, 1920, 0, 1, 1)

        assertThat(vout.windowSizes).isEmpty()
        assertThat(reported).isEmpty()
    }

    /** Records only what the listener touches; every other [IVLCVout] call is a no-op. */
    private class RecordingVout : IVLCVout {
        val windowSizes = mutableListOf<Pair<Int, Int>>()

        override fun setWindowSize(width: Int, height: Int) {
            windowSizes += width to height
        }

        override fun setVideoView(videoView: SurfaceView?) = Unit
        override fun setVideoView(videoView: TextureView?) = Unit
        override fun setVideoSurface(videoSurface: Surface?, surfaceHolder: SurfaceHolder?) = Unit
        override fun setVideoSurface(videoSurface: SurfaceTexture?) = Unit
        override fun setSubtitlesView(subtitlesView: SurfaceView?) = Unit
        override fun setSubtitlesView(subtitlesView: TextureView?) = Unit
        override fun setSubtitlesSurface(subtitlesSurface: Surface?, surfaceHolder: SurfaceHolder?) = Unit
        override fun setSubtitlesSurface(subtitlesSurface: SurfaceTexture?) = Unit
        override fun attachViews(onNewVideoLayoutListener: IVLCVout.OnNewVideoLayoutListener?) = Unit
        override fun attachViews() = Unit
        override fun detachViews() = Unit
        override fun areViewsAttached() = false
        override fun addCallback(callback: IVLCVout.Callback?) = Unit
        override fun removeCallback(callback: IVLCVout.Callback?) = Unit
        override fun sendMouseEvent(action: Int, button: Int, x: Int, y: Int) = Unit
    }
}
