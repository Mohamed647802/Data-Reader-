package com.dataradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

data class AppUsage(
    val uid: Int,
    val label: String,
    val pkg: String?,
    val rx: Long,
    val tx: Long
) {
    val total: Long get() = rx + tx
}

object Fmt {
    fun bytes(b: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b >= gb -> String.format(Locale.US, "%.2f GB", b / gb)
            b >= mb -> String.format(Locale.US, "%.1f MB", b / mb)
            b >= kb -> String.format(Locale.US, "%.0f KB", b / kb)
            else -> "$b B"
        }
    }
}

object UsageRepo {

    data class Result(val items: List<AppUsage>, val separated: Boolean)

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** sim: 0 = الكل، 1 = الشريحة الأولى، 2 = الشريحة الثانية */
    fun query(ctx: Context, wifi: Boolean, sim: Int, start: Long, end: Long): Result {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val acc = HashMap<Int, LongArray>()
        var separated = true

        if (wifi) {
            collect(nsm, ConnectivityManager.TYPE_WIFI, null, start, end, acc)
        } else {
            val sims = activeSims(ctx)
            if (sim == 0) {
                if (Build.VERSION.SDK_INT >= 29 || sims.none { it.second != null }) {
                    collect(nsm, ConnectivityManager.TYPE_MOBILE, null, start, end, acc)
                } else {
                    sims.forEach { s ->
                        s.second?.let {
                            collect(nsm, ConnectivityManager.TYPE_MOBILE, it, start, end, acc)
                        }
                    }
                }
            } else {
                val id = sims.firstOrNull { it.first == sim - 1 }?.second
                if (id != null) {
                    collect(nsm, ConnectivityManager.TYPE_MOBILE, id, start, end, acc)
                } else {
                    separated = false
                    collect(nsm, ConnectivityManager.TYPE_MOBILE, null, start, end, acc)
                }
            }
        }

        val pm = ctx.packageManager
        val items = acc.map { (uid, a) ->
            when (uid) {
                NetworkStats.Bucket.UID_REMOVED ->
                    AppUsage(uid, "تطبيقات محذوفة", null, a[0], a[1])
                NetworkStats.Bucket.UID_TETHERING ->
                    AppUsage(uid, "مشاركة الإنترنت (Hotspot)", null, a[0], a[1])
                else -> {
                    val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                    val label = try {
                        if (pkg != null) pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        else "خدمات النظام ($uid)"
                    } catch (e: Exception) {
                        pkg ?: "UID $uid"
                    }
                    AppUsage(uid, label, pkg, a[0], a[1])
                }
            }
        }.filter { it.total > 0 }.sortedByDescending { it.total }

        return Result(items, separated)
    }

    private fun collect(
        nsm: NetworkStatsManager, type: Int, subId: String?,
        start: Long, end: Long, acc: HashMap<Int, LongArray>
    ) {
        var stats: NetworkStats? = null
        try {
            stats = nsm.querySummary(type, subId, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val a = acc.getOrPut(bucket.uid) { LongArray(2) }
                a[0] += bucket.rxBytes
                a[1] += bucket.txBytes
            }
        } catch (e: Exception) {
            // لا توجد بيانات لهذه الشبكة
        } finally {
            try { stats?.close() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun activeSims(ctx: Context): List<Pair<Int, String?>> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val list = sm.activeSubscriptionInfoList ?: return emptyList()
            list.map { info ->
                val id = try {
                    tm.createForSubscriptionId(info.subscriptionId).subscriberId
                } catch (t: Throwable) {
                    null
                }
                Pair(info.simSlotIndex, id)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
