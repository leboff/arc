package com.daydreamvr.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The media domain must carry no `android.*` type (R5 bans `Bitmap`, R6 bans
 * `Uri`). This walks every property of every sealed subtype and fails on the
 * first Android reference (UI_REDESIGN_REVIEWED_PLAN.md §7.2, M3 acceptance 4).
 */
class MediaModelPurityTest {

    private val roots = listOf(
        MediaRef::class.java,
        MediaKey::class.java,
        MediaSource::class.java, MediaSource.Upnp::class.java,
        MediaSource.Local::class.java, MediaSource.Favourites::class.java,
        MediaNode::class.java, MediaNode.Folder::class.java, MediaNode.Video::class.java,
        PlaybackRef::class.java, PlaybackRef.Upnp::class.java, PlaybackRef.Local::class.java,
    )

    @Test
    fun noPropertyTypeIsAndroid() {
        val offenders = mutableListOf<String>()
        for (c in roots) {
            for (f in c.declaredFields) {
                val name = f.type.name
                if (name.startsWith("android.")) offenders += "${c.simpleName}.${f.name}: $name"
                // Also catch generic element types like List<android.net.Uri>.
                (f.genericType as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.forEach { arg ->
                    if (arg.typeName.startsWith("android.")) offenders += "${c.simpleName}.${f.name}<$arg>"
                }
            }
        }
        assertThat(offenders).isEmpty()
    }
}
