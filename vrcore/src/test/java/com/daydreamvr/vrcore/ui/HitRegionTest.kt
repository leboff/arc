package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitRegionTest {

    @Test
    fun containsIsHalfOpen() {
        val r = HitRegion(10f, 20f, 30f, 40f, id = "a")
        assertThat(r.contains(10f, 20f)).isTrue() // inclusive top-left
        assertThat(r.contains(29.9f, 39.9f)).isTrue()
        assertThat(r.contains(30f, 30f)).isFalse() // exclusive right
        assertThat(r.contains(20f, 40f)).isFalse() // exclusive bottom
    }

    @Test
    fun hitTestReturnsNullInGaps() {
        val map = HitMap(
            listOf(
                HitRegion(0f, 0f, 10f, 10f, "a"),
                HitRegion(0f, 20f, 10f, 30f, "b"),
            ),
        )
        assertThat(map.hitTest(5f, 15f)).isNull()
        assertThat(map.hitTest(5f, 5f)).isEqualTo("a")
        assertThat(map.hitTest(5f, 25f)).isEqualTo("b")
    }

    @Test
    fun lastRegionWins() {
        val map = HitMap(
            listOf(
                HitRegion(0f, 0f, 100f, 100f, "under"),
                HitRegion(10f, 10f, 20f, 20f, "over"),
            ),
        )
        assertThat(map.hitTest(15f, 15f)).isEqualTo("over")
        assertThat(map.hitTest(50f, 50f)).isEqualTo("under")
    }

    @Test
    fun emptyMapHitsNothing() {
        assertThat(HitMap.empty<String>().hitTest(0f, 0f)).isNull()
        assertThat(HitMap.empty<String>().isEmpty).isTrue()
    }
}
