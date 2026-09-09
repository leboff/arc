package com.daydreamvr.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumeStoreTest {

    @Test
    fun writeThenReadRoundTrips() {
        val store = InMemoryResumeStore()
        store.put("udn|42", positionMs = 30_000L, durationMs = 120_000L, nowMs = 1_000L)

        val entry = store.get("udn|42")
        assertThat(entry).isNotNull()
        assertThat(entry!!.positionMs).isEqualTo(30_000L)
        assertThat(entry.durationMs).isEqualTo(120_000L)
        assertThat(entry.isFinished).isFalse()
        assertThat(entry.fractionWatched).isWithin(1e-4f).of(0.25f)
    }

    @Test
    fun pastNinetyFivePercentMarksFinished() {
        val store = InMemoryResumeStore()
        val entry = store.put("udn|movie", positionMs = 119_000L, durationMs = 120_000L, nowMs = 7_777L)

        assertThat(entry.isFinished).isTrue()
        assertThat(entry.finishedAtMs).isEqualTo(7_777L)
        assertThat(store.get("udn|movie")!!.isFinished).isTrue()
    }

    @Test
    fun unknownKeyIsNull() {
        assertThat(InMemoryResumeStore().get("nope")).isNull()
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCap() {
        val store = InMemoryResumeStore(maxEntries = 500)
        for (i in 0..500) {
            store.put("k$i", positionMs = 1_000L, durationMs = 10_000L, nowMs = i.toLong())
        }

        assertThat(store.all()).hasSize(500)
        assertThat(store.get("k0")).isNull() // eldest evicted
        assertThat(store.get("k500")).isNotNull()
    }

    @Test
    fun readingAnEntryRefreshesItsRecency() {
        val store = InMemoryResumeStore(maxEntries = 3)
        store.put("a", 1L, 10L, 0L)
        store.put("b", 1L, 10L, 0L)
        store.put("c", 1L, 10L, 0L)

        store.get("a") // touch "a" so "b" is now the eldest
        store.put("d", 1L, 10L, 0L)

        assertThat(store.get("a")).isNotNull()
        assertThat(store.get("b")).isNull()
    }

    @Test
    fun restoreRehydratesAndEnforcesCap() {
        val store = InMemoryResumeStore(maxEntries = 2)
        store.restore(
            listOf(
                ResumeEntry("x", 1L, 10L, null),
                ResumeEntry("y", 1L, 10L, null),
                ResumeEntry("z", 1L, 10L, null),
            ),
        )
        assertThat(store.all().map { it.itemKey }).containsExactly("y", "z").inOrder()
    }
}
