package com.daydreamvr.vrcore.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectionModeTest {

    @Test
    fun domePackingAndDetection() {
        for (token in listOf("SBS", "H-SBS", "HSBS")) {
            assertThat(ProjectionMode.detect("Beach_VR180_$token.mp4", 3840, 1920))
                .isEqualTo(ProjectionMode.EQUIRECT_180_SBS)
        }
        for (token in listOf("OU", "TB", "Half-OU")) {
            assertThat(ProjectionMode.detect("Beach 180 $token.mp4", 3840, 1920))
                .isEqualTo(ProjectionMode.EQUIRECT_180_TOPBOTTOM)
        }
        for (mode in ProjectionMode.entries.filter { it.domeFov != null }) {
            for (eye in Eye.entries) {
                val expected = when (mode.packing) {
                    StereoPacking.MONO -> ProjectionMode.FLAT
                    StereoPacking.SBS -> ProjectionMode.SBS_HALF
                    StereoPacking.TOPBOTTOM -> ProjectionMode.TOPBOTTOM_HALF
                }
                assertThat(ProjectionMode.uvRectFor(mode, eye).toList())
                    .containsExactlyElementsIn(ProjectionMode.uvRectFor(expected, eye).toList()).inOrder()
            }
        }
    }

    @Test
    fun detect_readsHalfSbsFromTitle() {
        assertThat(ProjectionMode.detect("Movie.2016.1080p.HSBS.mkv", 1920, 1080))
            .isEqualTo(ProjectionMode.SBS_HALF)
    }

    @Test
    fun detect_readsEquirect360FromTitleAndFromAspect() {
        assertThat(ProjectionMode.detect("Beach 360 VR.mp4", 3840, 1920))
            .isEqualTo(ProjectionMode.EQUIRECT_360)
        // 2:1 frame, no token in the title.
        assertThat(ProjectionMode.detect("Reef dive", 3840, 1920))
            .isEqualTo(ProjectionMode.EQUIRECT_360)
    }

    @Test
    fun detect_readsOverUnderAnd180() {
        assertThat(ProjectionMode.detect("Concert.Half-OU.mkv", 1920, 1080))
            .isEqualTo(ProjectionMode.TOPBOTTOM_HALF)
        assertThat(ProjectionMode.detect("Skydive 180.mp4", 2880, 1440))
            .isEqualTo(ProjectionMode.EQUIRECT_180)
    }

    @Test
    fun detect_plainSixteenNineIsFlat() {
        assertThat(ProjectionMode.detect("The Movie 1080p", 1920, 1080))
            .isEqualTo(ProjectionMode.FLAT)
    }

    @Test
    fun uvRect_givesLeftEyeTheLeftHalfForSbs() {
        val left = ProjectionMode.uvRectFor(ProjectionMode.SBS_HALF, Eye.LEFT)
        val right = ProjectionMode.uvRectFor(ProjectionMode.SBS_FULL, Eye.RIGHT)

        assertThat(left.toList()).containsExactly(0f, 0f, 0.5f, 1f).inOrder()
        assertThat(right.toList()).containsExactly(0.5f, 0f, 0.5f, 1f).inOrder()
    }

    @Test
    fun uvRect_givesLeftEyeTheTopHalfForTopBottom() {
        val left = ProjectionMode.uvRectFor(ProjectionMode.TOPBOTTOM_HALF, Eye.LEFT)
        val right = ProjectionMode.uvRectFor(ProjectionMode.TOPBOTTOM_FULL, Eye.RIGHT)

        // In standard texture coordinates, v offset 0.5 is the top half of the frame.
        assertThat(left[1]).isEqualTo(0.5f)
        assertThat(left[3]).isEqualTo(0.5f)
        assertThat(right[1]).isEqualTo(0f)
    }

    @Test
    fun uvRect_isWholeFrameForFlatAndEquirect() {
        for (mode in listOf(ProjectionMode.FLAT, ProjectionMode.EQUIRECT_180, ProjectionMode.EQUIRECT_360)) {
            assertThat(ProjectionMode.uvRectFor(mode, Eye.LEFT).toList())
                .containsExactly(0f, 0f, 1f, 1f).inOrder()
            assertThat(ProjectionMode.uvRectFor(mode, Eye.RIGHT).toList())
                .containsExactly(0f, 0f, 1f, 1f).inOrder()
        }
    }
}
