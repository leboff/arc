package com.daydreamvr.player.media.thumb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SizedLruCacheTest {

    /** Every value weighs 1024 KB regardless of content. */
    private fun cache(maxKb: Int) = SizedLruCache<String, String>(maxKb) { 1024 }

    @Test
    fun evictionIsBySizeNotCount() {
        val c = cache(2048) // room for exactly two 1024 KB entries
        c.put("a", "A")
        c.put("b", "B")
        c.get("a")           // touch a — b is now least-recently-used
        c.put("c", "C")      // must evict b, not a

        assertThat(c.get("a")).isEqualTo("A")
        assertThat(c.get("b")).isNull()
        assertThat(c.get("c")).isEqualTo("C")
        assertThat(c.sizeKb()).isEqualTo(2048)
    }

    @Test
    fun getPromotesAndEvictToSizeAndSizeKbTrackExactly() {
        val c = cache(3072)
        c.put("a", "A"); c.put("b", "B"); c.put("c", "C")
        assertThat(c.sizeKb()).isEqualTo(3072)

        c.evictToSize(1024)
        assertThat(c.sizeKb()).isEqualTo(1024)
        assertThat(c.get("c")).isEqualTo("C") // most recent survives

        c.evictToSize(0)
        assertThat(c.sizeKb()).isEqualTo(0)
        assertThat(c.get("c")).isNull()
    }

    @Test
    fun replacingAKeyDoesNotDoubleCountItsSize() {
        val c = cache(2048)
        c.put("a", "A")
        c.put("a", "A2")
        assertThat(c.sizeKb()).isEqualTo(1024)
        assertThat(c.get("a")).isEqualTo("A2")
    }
}
