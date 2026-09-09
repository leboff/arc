package com.daydreamvr.player.media.thumb

import com.daydreamvr.player.media.MediaRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncKeyedLoaderTest {

    private val ref = MediaRef("content://x/1")

    @Test
    fun sixConcurrentRequestsForOneKeyInvokeLoadExactlyOnce() = runTest {
        val calls = AtomicInteger(0)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = AtomicInteger(0)
        val loader = AsyncKeyedLoader<String>(
            cache = SizedLruCache(1024) { 1 },
            scope = TestScope(testScheduler),
            dispatcher = dispatcher,
            onLoaded = { loaded.incrementAndGet() },
        ) { _, _ -> calls.incrementAndGet(); "bmp" }

        repeat(6) { loader.request("k", ref) }
        testScheduler.advanceUntilIdle()

        assertThat(calls.get()).isEqualTo(1)
        assertThat(loaded.get()).isEqualTo(1)
        assertThat(loader.peek("k")).isEqualTo("bmp")
        assertThat(loader.inFlightCount).isEqualTo(0)
    }

    @Test
    fun aFailingLoadLeavesNoEntryAndDoesNotWedgeTheInFlightMap() = runTest {
        var fail = true
        val loader = AsyncKeyedLoader<String>(
            cache = SizedLruCache(1024) { 1 },
            scope = TestScope(testScheduler),
            dispatcher = StandardTestDispatcher(testScheduler),
            onLoaded = {},
        ) { _, _ -> if (fail) throw RuntimeException("boom") else "ok" }

        loader.request("k", ref)
        testScheduler.advanceUntilIdle()
        assertThat(loader.peek("k")).isNull()
        assertThat(loader.inFlightCount).isEqualTo(0)

        // A later request retries and now succeeds.
        fail = false
        loader.request("k", ref)
        testScheduler.advanceUntilIdle()
        assertThat(loader.peek("k")).isEqualTo("ok")
    }

    @Test
    fun aCachedKeyIsNotRequestedAgain() = runTest {
        val calls = AtomicInteger(0)
        val loader = AsyncKeyedLoader<String>(
            cache = SizedLruCache(1024) { 1 },
            scope = TestScope(testScheduler),
            dispatcher = StandardTestDispatcher(testScheduler),
            onLoaded = {},
        ) { _, _ -> calls.incrementAndGet(); "v" }

        loader.request("k", ref)
        testScheduler.advanceUntilIdle()
        loader.request("k", ref)
        testScheduler.advanceUntilIdle()

        assertThat(calls.get()).isEqualTo(1)
    }
}
