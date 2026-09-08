package com.daydreamvr.player.di

import android.content.Context
import com.daydreamvr.vrcore.input.GamepadProfileResolver
import com.daydreamvr.vrcore.input.InputBindings
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.profile.DeviceProfiles

/**
 * The whole object graph (~20 objects by the end). Manual constructor injection;
 * no Hilt (ARCHITECTURE.md §2). Grows one field per phase.
 */
class AppContainer(context: Context) {

    @Suppress("unused") // Retained for Phase 3+ (DataStore, network binder).
    private val appContext: Context = context.applicationContext

    val inputBindings: InputBindings = InputBindings()

    val gamepadProfileResolver: GamepadProfileResolver = GamepadProfileResolver()

    /** Active viewer profile. User-selectable from the lobby / settings later. */
    @Volatile
    var deviceProfile: DeviceProfile = DeviceProfiles.DEFAULT
}
