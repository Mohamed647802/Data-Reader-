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

    data class Result(
        val items: List<AppUsage>,
        val separated: Boolean,
        val sinceMs: Long = 0L
    )

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

    /** sim: 0 = الكل، 1 = الخط الأول، 2 = الخط الثاني */
    fun query(ctx: Context, wifi: Boolean, sim: Int, start: Long, end: Long): Result {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val acc = HashMap<Int, LongArray>()
        var since = 0L

        if (wifi) {
            collect(nsm, ConnectivityManager.TYPE_WIFI, null, start, end, acc)
        } else if (sim == 0) {
            val sims = activeSims(ctx)
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
            val id = activeSims(ctx).firstOrNull { Tracker.lineOf(it.first) == sim }?.second
            if (Build.VERSION.SDK_INT < 29 && id != null) {
                // أندرويد 9 وما قبله: النظام يسمح بالفصل مباشرة
                collect(nsm, ConnectivityManager.TYPE_MOBILE, id, start, end, acc)
            } else {
                // أندرويد 10+: من تسجيل التطبيق نفسه
                Tracker.flush(ctx)
                acc.putAll(Tracker.query(ctx, sim, start, end))
                since = Tracker.since(ctx)
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

        return Result(items, true, since)
    }

    /** يرجع true لو القراءة نجحت (حتى لو فاضية) */
    fun collect(
        nsm: NetworkStatsManager, type: Int, subId: String?,
        start: Long, end: Long, acc: HashMap<Int, LongArray>
    ): Boolean {
        var stats: NetworkStats? = null
        return try {
            stats = nsm.querySummary(type, subId, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val a = acc.getOrPut(bucket.uid) { LongArray(2) }
                a[0] += bucket.rxBytes
                a[1] += bucket.txBytes
            }
            true
        } catch (e: Exception) {
            false
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
