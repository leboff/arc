package com.daydreamvr.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaKeyTest {

    @Test
    fun storageKeyIsSourceIdPipeNodeId() {
        assertThat(MediaKey("upnp:uuid:abc", "17\$3").storageKey()).isEqualTo("upnp:uuid:abc|17\$3")
        assertThat(MediaKey("local", "4521").storageKey()).isEqualTo("local|4521")
    }

    @Test
    fun theFormatIsStableAcrossEqualInputs() {
        assertThat(MediaKey("local", "1").storageKey()).isEqualTo(MediaKey("local", "1").storageKey())
    }
}
