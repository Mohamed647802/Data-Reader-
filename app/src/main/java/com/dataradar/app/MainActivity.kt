package com.dataradar.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var wifiMode = true
    private var simSel = 0
    private var askedPhone = false
    private val from = Calendar.getInstance()
    private val to = Calendar.getInstance()
    private val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH)
    private val executor = Executors.newSingleThreadExecutor()
    private var reqId = 0
    private val adapter = AppAdapter()

    private lateinit var tabWifi: TextView
    private lateinit var tabData: TextView
    private lateinit var simRow: View
    private lateinit var simAll: TextView
    private lateinit var sim1: TextView
    private lateinit var sim2: TextView
    private lateinit var btnFrom: TextView
    private lateinit var btnTo: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvNote: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView
    private lateinit var permBox: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabWifi = findViewById(R.id.tabWifi)
        tabData = findViewById(R.id.tabData)
        simRow = findViewById(R.id.simRow)
        simAll = findViewById(R.id.simAll)
        sim1 = findViewById(R.id.sim1)
        sim2 = findViewById(R.id.sim2)
        btnFrom = findViewById(R.id.btnFrom)
        btnTo = findViewById(R.id.btnTo)
        tvTotal = findViewById(R.id.tvTotal)
        tvDown = findViewById(R.id.tvDown)
        tvUp = findViewById(R.id.tvUp)
        tvNote = findViewById(R.id.tvNote)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)
        rv = findViewById(R.id.rv)
        permBox = findViewById(R.id.permBox)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        setRange(7)

        tabWifi.setOnClickListener { wifiMode = true; refreshUi(); load() }
        tabData.setOnClickListener {
            wifiMode = false
            refreshUi()
            ensurePhone()
            load()
        }
        simAll.setOnClickListener { simSel = 0; refreshUi(); load() }
        sim1.setOnClickListener { simSel = 1; refreshUi(); load() }
        sim2.setOnClickListener { simSel = 2; refreshUi(); load() }

        btnFrom.setOnClickListener { pickDate(from) }
        btnTo.setOnClickListener { pickDate(to) }
        findViewById<View>(R.id.q1).setOnClickListener { setRange(1); load() }
        findViewById<View>(R.id.q7).setOnClickListener { setRange(7); load() }
        findViewById<View>(R.id.q30).setOnClickListener { setRange(30); load() }

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (UsageRepo.hasAccess(this)) {
            permBox.visibility = View.GONE
            load()
        } else {
            permBox.visibility = View.VISIBLE
            rv.visibility = View.GONE
            tvEmpty.visibility = View.GONE
        }
    }

    private fun ensurePhone() {
        if (!askedPhone && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askedPhone = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load()
    }

    private fun setRange(days: Int) {
        to.timeInMillis = System.currentTimeMillis()
        from.timeInMillis = System.currentTimeMillis()
        from.add(Calendar.DAY_OF_YEAR, -(days - 1))
        refreshDates()
    }

    private fun pickDate(cal: Calendar) {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                cal.set(y, m, d)
                refreshDates()
                load()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun refreshDates() {
        btnFrom.text = "من\n${fmt.format(from.time)}"
        btnTo.text = "إلى\n${fmt.format(to.time)}"
    }

    private fun style(v: TextView, on: Boolean, onBg: Int, offBg: Int) {
        v.setBackgroundResource(if (on) onBg else offBg)
        v.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.on_accent else R.color.text)
        )
    }

    private fun refreshUi() {
        style(tabWifi, wifiMode, R.drawable.tab_on, R.drawable.card_bg)
        style(tabData, !wifiMode, R.drawable.tab_on, R.drawable.card_bg)
        simRow.visibility = if (wifiMode) View.GONE else View.VISIBLE
        style(simAll, simSel == 0, R.drawable.chip_on, R.drawable.chip_off)
        style(sim1, simSel == 1, R.drawable.chip_on, R.drawable.chip_off)
        style(sim2, simSel == 2, R.drawable.chip_on, R.drawable.chip_off)
    }

    private fun startMs(c: Calendar): Long {
        val x = c.clone() as Calendar
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0)
        x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
        return x.timeInMillis
    }

    private fun endMs(c: Calendar): Long {
        val x = c.clone() as Calendar
        x.set(Calendar.HOUR_OF_DAY, 23); x.set(Calendar.MINUTE, 59)
        x.set(Calendar.SECOND, 59); x.set(Calendar.MILLISECOND, 999)
        return x.timeInMillis
    }

    private fun load() {
        if (!UsageRepo.hasAccess(this)) return
        val s = startMs(from)
        val e = endMs(to)
        if (s > e) {
            Toast.makeText(this, "تاريخ البداية بعد تاريخ النهاية", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        val id = ++reqId
        val wifi = wifiMode
        val sim = simSel
        executor.execute {
            val r = try {
                UsageRepo.query(this, wifi, sim, s, e)
            } catch (t: Throwable) {
                UsageRepo.Result(emptyList(), true)
            }
            runOnUiThread {
                if (id == reqId && !isFinishing) show(r)
            }
        }
    }

    private fun show(r: UsageRepo.Result) {
        progress.visibility = View.GONE
        adapter.submit(r.items)
        val rx = r.items.sumOf { it.rx }
        val tx = r.items.sumOf { it.tx }
        tvTotal.text = Fmt.bytes(rx + tx)
        tvDown.text = Fmt.bytes(rx)
        tvUp.text = Fmt.bytes(tx)
        val empty = r.items.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility = if (empty) View.GONE else View.VISIBLE
        if (!wifiMode && simSel != 0 && !r.separated) {
            tvNote.visibility = View.VISIBLE
            tvNote.text = "تعذّر فصل الشريحة على هذا الجهاز (أندرويد 10+ يمنع ذلك) — المعروض هو مجموع الشريحتين."
        } else {
            tvNote.visibility = View.GONE
        }
    }
}
