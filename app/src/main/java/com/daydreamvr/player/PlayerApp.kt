package com.daydreamvr.player

import android.app.Application
import com.daydreamvr.player.di.AppContainer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the single [AppContainer] (manual DI, ARCHITECTURE.md §2). */
class PlayerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("DaydreamCrash", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = java.io.StringWriter().also { throwable.printStackTrace(java.io.PrintWriter(it)) }.toString()
                File(filesDir, "last_crash.txt").writeText(
                    "Crash at $ts on thread ${thread.name}\n\n$trace",
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        container = AppContainer(this)
    }
}
