package com.daydreamvr.player

import android.app.Application
import com.daydreamvr.player.di.AppContainer

/** Owns the single [AppContainer] (manual DI, ARCHITECTURE.md §2). */
class PlayerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
