package com.dataradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

class TrackerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var ticks = 0

    private val loop = object : Runnable {
        override fun run() {
            ticks++
            val n = ticks
            worker.execute { tick(n) }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    private fun tick(n: Int) {
        try {
            val cur = Tracker.currentLine(this) ?: return
            val saved = Tracker.savedLine(this)
            if (saved == -2) {
                Tracker.saveLine(this, cur)
                Tracker.flush(this)
            } else if (cur != saved) {
                // تغيّر خط الإنترنت: نسجّل ما قبل التغيير على الخط القديم ثم نبدّل
                Tracker.flush(this)
                Tracker.saveLine(this, cur)
            } else if (n == 1 || n % 12 == 0) {
                Tracker.flush(this)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startAsForeground() {
        try {
            val channel = "tracker"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channel, "تسجيل الاستهلاك", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("رادار البيانات")
                .setContentText("يسجّل استهلاك كل خط بدقة")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, n)
            }
        } catch (_: Throwable) {
        }
    }
}
