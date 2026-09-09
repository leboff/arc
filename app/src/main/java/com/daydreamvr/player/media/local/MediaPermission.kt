package com.daydreamvr.player.media.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime storage permission for reading local video
 * (UI_REDESIGN_REVIEWED_PLAN.md §8.2, R10).
 *
 * The *request* only ever happens in `SetupActivity` (the 2D lobby) — a system
 * permission dialog raised from inside the headset is a mono 2D sheet the user
 * cannot read or dismiss.
 */
object MediaPermission {

    /** The permission to request on [sdkInt] (defaults to the running API level). */
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): String = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    enum class Grant { FULL, PARTIAL, DENIED }

    fun status(ctx: Context): Grant {
        val granted = { p: String ->
            ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }
        return when {
            granted(required()) -> Grant.FULL
            // API 34+: the user may have granted access to only a picked subset.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Grant.PARTIAL
            else -> Grant.DENIED
        }
    }
}
