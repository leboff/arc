package com.daydreamvr.player.media.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaPermissionTest {

    @Test
    fun tiramisuAndAboveRequestsReadMediaVideo() {
        for (sdk in listOf(33, 34, 36)) {
            assertThat(MediaPermission.required(sdk)).isEqualTo("android.permission.READ_MEDIA_VIDEO")
        }
    }

    @Test
    fun preTiramisuRequestsReadExternalStorage() {
        for (sdk in listOf(29, 30, 31, 32)) {
            assertThat(MediaPermission.required(sdk)).isEqualTo("android.permission.READ_EXTERNAL_STORAGE")
        }
    }
}
