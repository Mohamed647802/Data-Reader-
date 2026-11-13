package com.dataradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackerService::class.java)
            )
        } catch (_: Throwable) {
        }
    }
}
