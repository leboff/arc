package com.daydreamvr.player.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The three-column split is derived, not hand-placed: symmetric sidebars around a
 * fixed centre column, so the centre azimuth is exactly 0° and the columns tile
 * the texture with no gap and no overlap (UI_REDESIGN_REVIEWED_PLAN.md §2.3, M0).
 */
class BrowseLayoutTest {

    @Test
    fun columnsPlusGuttersTileTheTextureExactly() {
        assertThat(BrowseLayout.SIDEBAR_PX).isEqualTo(394)

        val left = BrowseLayout.leftX
        val centre = BrowseLayout.centreX
        val right = BrowseLayout.rightX

        // Contiguous, no gap, no overlap, covering [0, 1536).
        assertThat(left.first).isEqualTo(0)
        assertThat(centre.first).isEqualTo(left.last + 1 + BrowseLayout.GUTTER_PX)
        assertThat(right.first).isEqualTo(centre.last + 1 + BrowseLayout.GUTTER_PX)
        assertThat(right.last).isEqualTo(BrowseLayout.WIDTH_PX - 1)

        val covered = left.count() + BrowseLayout.GUTTER_PX + centre.count() +
            BrowseLayout.GUTTER_PX + right.count()
        assertThat(covered).isEqualTo(BrowseLayout.WIDTH_PX)
    }

    @Test
    fun columnCentreAzimuthsAreSymmetricAndCentreIsZero() {
        val leftCentre = BrowseLayout.azimuthDeg(BrowseLayout.SIDEBAR_PX / 2f)
        val midCentre = BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX / 2f)
        val rightCentre = BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX - BrowseLayout.SIDEBAR_PX / 2f)

        assertThat(leftCentre).isWithin(0.01f).of(-23.855f)
        assertThat(midCentre).isWithin(0.01f).of(0.0f)
        assertThat(rightCentre).isWithin(0.01f).of(23.855f)
    }

    @Test
    fun panelEdgesSitAtPlusMinus32Degrees() {
        assertThat(BrowseLayout.azimuthDeg(0f)).isWithin(0.01f).of(-32.086f)
        assertThat(BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX.toFloat())).isWithin(0.01f).of(32.086f)
    }

    @Test
    fun heightsAreIsotropicWithWidths() {
        assertThat(BrowseLayout.WIDTH_PX / BrowseLayout.WIDTH_M)
            .isWithin(0.5f)
            .of(BrowseLayout.HEIGHT_PX / BrowseLayout.HEIGHT_M)
        assertThat(DockLayout.WIDTH_PX / DockLayout.WIDTH_M)
            .isWithin(0.5f)
            .of(DockLayout.HEIGHT_PX / DockLayout.HEIGHT_M)
    }
}
