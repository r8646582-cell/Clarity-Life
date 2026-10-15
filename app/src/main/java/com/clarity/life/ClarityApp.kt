package com.clarity.life

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ClarityApp : Application() {
    override fun onCreate() {
        com.clarity.life.core.AppPerformance.markProcessStart()
        super.onCreate()
        CrashLog.install(this)
    }
}
