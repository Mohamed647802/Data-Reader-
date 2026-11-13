package com.dataradar.app

import android.annotation.SuppressLint
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.telephony.SubscriptionManager

private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE usage(bucket INTEGER, line INTEGER, uid INTEGER, " +
                "rx INTEGER, tx INTEGER, PRIMARY KEY(bucket, line, uid))"
        )
        db.execSQL("CREATE TABLE last(uid INTEGER PRIMARY KEY, rx INTEGER, tx INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

/**
 * يسجّل استهلاك بيانات الجوال لكل خط (1 / 2) بنفسه،
 * لأن أندرويد 10+ يمنع قراءة الاستهلاك مفصولاً بالشريحة.
 * line: 1 = الخط الأول، 2 = الخط الثاني، 0 = غير معروف
 */
object Tracker {
    private const val BUCKET = 5 * 60 * 1000L
    private const val KEEP = 120L * 24 * 3600 * 1000
    private var helper: Db? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: Db(ctx).also { helper = it }
        return h.writableDatabase
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    fun lineOf(rawSlot: Int): Int = if (rawSlot == 0) 1 else if (rawSlot > 0) 2 else 0

    /** الخط المستخدم حالياً للإنترنت، أو null لو تعذّرت القراءة */
    @SuppressLint("MissingPermission")
    fun currentLine(ctx: Context): Int? {
        try {
            val id = SubscriptionManager.getDefaultDataSubscriptionId()
            if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return 0
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val info = sm.getActiveSubscriptionInfo(id) ?: return 0
            return lineOf(info.simSlotIndex)
        } catch (t: Throwable) {
            return null
        }
    }

    fun savedLine(ctx: Context): Int = prefs(ctx).getInt("line", -2)

    fun saveLine(ctx: Context, line: Int) {
        prefs(ctx).edit().putInt("line", line).apply()
    }

    fun since(ctx: Context): Long = prefs(ctx).getLong("since", 0L)

    @SuppressLint("MissingPermission")
    fun lineNames(ctx: Context): Map<Int, String> {
        val m = HashMap<Int, String>()
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList?.forEach {
                m[lineOf(it.simSlotIndex)] = it.displayName?.toString() ?: ""
            }
        } catch (_: Throwable) {
        }
        return m
    }

    /** يقرأ الإجمالي التراكمي من النظام وينسب الفرق منذ آخر مرة للخط الحالي */
    @Synchronized
    fun flush(ctx: Context) {
        if (!UsageRepo.hasAccess(ctx)) return
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val now = System.currentTimeMillis()
        val cur = HashMap<Int, LongArray>()
        if (!UsageRepo.collect(
                nsm, ConnectivityManager.TYPE_MOBILE, null, 0L, now + 3_600_000L, cur
            )
        ) return
        // تطبيقات محذوفة تتجمّع تحت UID خاص فتعطي قفزة كاذبة، فنتجاهله
        cur.remove(NetworkStats.Bucket.UID_REMOVED)

        var line = savedLine(ctx)
        if (line == -2) line = currentLine(ctx) ?: 0

        val p = prefs(ctx)
        val d = db(ctx)
        d.beginTransaction()
        try {
            if (!p.contains("since")) {
                // أول مرة: نحفظ نقطة البداية فقط بدون نسب أي استهلاك قديم
                d.execSQL("DELETE FROM last")
                for ((uid, c) in cur) {
                    d.execSQL(
                        "INSERT OR REPLACE INTO last VALUES(?,?,?)",
                        arrayOf<Any>(uid, c[0], c[1])
                    )
                }
                d.setTransactionSuccessful()
                p.edit().putLong("since", now).apply()
                return
            }

            val last = HashMap<Int, LongArray>()
            d.rawQuery("SELECT uid, rx, tx FROM last", null).use { cu ->
                while (cu.moveToNext()) {
                    last[cu.getInt(0)] = longArrayOf(cu.getLong(1), cu.getLong(2))
                }
            }

            val bucket = now / BUCKET * BUCKET
            val up = d.compileStatement(
                "UPDATE usage SET rx = rx + ?, tx = tx + ? WHERE bucket = ? AND line = ? AND uid = ?"
            )
            for ((uid, c) in cur) {
                val l = last[uid]
                val drx = if (l == null) c[0] else if (c[0] >= l[0]) c[0] - l[0] else 0L
                val dtx = if (l == null) c[1] else if (c[1] >= l[1]) c[1] - l[1] else 0L
                if (drx > 0 || dtx > 0) {
                    up.clearBindings()
                    up.bindLong(1, drx)
                    up.bindLong(2, dtx)
                    up.bindLong(3, bucket)
                    up.bindLong(4, line.toLong())
                    up.bindLong(5, uid.toLong())
                    if (up.executeUpdateDelete() == 0) {
                        d.execSQL(
                            "INSERT INTO usage(bucket, line, uid, rx, tx) VALUES(?,?,?,?,?)",
                            arrayOf<Any>(bucket, line, uid, drx, dtx)
                        )
                    }
                }
                if (l == null || l[0] != c[0] || l[1] != c[1]) {
                    d.execSQL(
                        "INSERT OR REPLACE INTO last VALUES(?,?,?)",
                        arrayOf<Any>(uid, c[0], c[1])
                    )
                }
            }
            d.execSQL("DELETE FROM usage WHERE bucket < ${now - KEEP}")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    @Synchronized
    fun query(ctx: Context, line: Int, start: Long, end: Long): HashMap<Int, LongArray> {
        val out = HashMap<Int, LongArray>()
        val from = start / BUCKET * BUCKET
        db(ctx).rawQuery(
            "SELECT uid, SUM(rx), SUM(tx) FROM usage " +
                "WHERE line = $line AND bucket >= $from AND bucket <= $end GROUP BY uid",
            null
        ).use { cu ->
            while (cu.moveToNext()) {
                out[cu.getInt(0)] = longArrayOf(cu.getLong(1), cu.getLong(2))
            }
        }
        return out
    }
}
