package com.clarity.life.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the phone has a working internet connection right now. A guess is fine: a wrong "online" still ends in Try again. */
object Connectivity {
    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        true // unknown: try to send; a failure becomes Try again
    }
}
