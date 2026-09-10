# VLC Fallback-Engine Wrong-Projection Fix

## Root Cause (confirmed via code read, not guesswork)

`playback/src/main/java/com/daydreamvr/playback/vlc/VlcVideoPlayer.kt`:

```kotlin
private var surfaceWidth = DEFAULT_SURFACE_WIDTH   // placeholder, e.g. 1920x1080
private var surfaceHeight = DEFAULT_SURFACE_HEIGHT

private fun attachSurface(mp: MediaPlayer, surface: Surface) {
    val vout = mp.getVLCVout()
    if (viewsAttached) vout.detachViews()
    vout.setVideoSurface(surface, null)
    vout.setWindowSize(surfaceWidth, surfaceHeight)   // called with the STALE/placeholder size —
                                                        // VLC hasn't parsed the stream yet
    vout.attachViews(
        object : IVLCVout.OnNewVideoLayoutListener {
            override fun onNewVideoLayout(
                vlcVout: IVLCVout, width: Int, height: Int,
                visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int,
            ) {
                if (width > 0 && height > 0) {
                    surfaceWidth = width
                    surfaceHeight = height
                    _snapshot.value = _snapshot.value.copy(videoWidth = width, videoHeight = height)
                    // BUG: never calls vout.setWindowSize(width, height) again here.
                    // VLC keeps rendering/scaling into the STALE window size for the
                    // rest of playback, so the frame content is letterboxed/padded/
                    // scaled incorrectly relative to what the shared GL mesh expects
                    // (mesh assumes Surface content fills edge-to-edge at the true
                    // frame aspect — true for ExoPlayer, false for VLC post-bug).
                }
            }
        },
    )
    viewsAttached = true
}
```

This only affects files that failed over to `VlcVideoPlayer` (Media3-hostile containers / unsupported
profiles) — never Media3-native playback, which explains why only the LibVLC fallback videos show
wrong/warped/seamed projections while everything else renders fine.

## Required Fix

In `onNewVideoLayout`, after updating `surfaceWidth`/`surfaceHeight`, call
`vlcVout.setWindowSize(width, height)` again so LibVLC re-scales its output against the *real*
decoded dimensions instead of sticking with the initial placeholder guess.

```kotlin
override fun onNewVideoLayout(
    vlcVout: IVLCVout, width: Int, height: Int,
    visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int,
) {
    if (width > 0 && height > 0) {
        surfaceWidth = width
        surfaceHeight = height
        vlcVout.setWindowSize(width, height)   // <-- THE FIX: re-apply with real dimensions
        _snapshot.value = _snapshot.value.copy(videoWidth = width, videoHeight = height)
    }
}
```

## Verification Steps

1. Add/confirm a unit test in `playback/src/test/java/com/daydreamvr/playback/vlc/` (or extend an
   existing VlcVideoPlayer test if one exists) asserting that when `onNewVideoLayout` fires with a
   new width/height, `IVLCVout.setWindowSize` is invoked a second time with those exact values.
   Use Mockito to mock `IVLCVout` and verify the call — LibVLC/MediaPlayer itself cannot run in a
   JVM unit test, so mock the `IVLCVout` interface directly.
2. Run `./gradlew testDebugUnitTest --no-daemon` — must be 100% green, no regressions.
3. Run `./gradlew assembleRelease --no-daemon` to confirm the release build still compiles/links
   cleanly with the change.
4. Do NOT touch anything else — CylinderScreen/SphereScreen/ProjectionMode/shaders are NOT the
   root cause here and must not be modified. This is a single, surgical one-line-of-substance fix
   inside VlcVideoPlayer.kt's attach/layout-callback flow.

## Commit

Commit as a single focused commit, e.g.:
`fix(playback): re-apply VLC window size on real video layout, not just placeholder`

Include in the commit body: the mechanism (placeholder setWindowSize before parse, never
refreshed post-parse) and that it's scoped to only the VLC compatibility-engine fallback path,
not Media3 playback.
