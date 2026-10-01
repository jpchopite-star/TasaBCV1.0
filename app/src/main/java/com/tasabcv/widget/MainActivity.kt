package com.tasabcv.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** Companion screen: today's rates, a USD/EUR ⇄ Bs calculator, refresh and widget helpers. */
class MainActivity : Activity(), CoroutineScope by MainScope() {

    private val ve = Locale.forLanguageTag("es-VE")

    /** false = foreign → Bs (default), true = Bs → foreign */
    private var reversed = false

    private lateinit var amount: EditText
    private lateinit var currency: RadioGroup
    private lateinit var result: TextView
    private lateinit var rateUsed: TextView
    private lateinit var inSymbol: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RateUpdateWorker.schedule(this)

        amount = findViewById(R.id.amount)
        currency = findViewById(R.id.currency)
        result = findViewById(R.id.result)
        rateUsed = findViewById(R.id.rate_used)
        inSymbol = findViewById(R.id.in_symbol)

        show()

        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = calculate()
        })
        currency.setOnCheckedChangeListener { _, _ -> calculate() }

        findViewById<ImageButton>(R.id.swap).setOnClickListener {
            // Carry the current result over as the new input, so swapping feels natural.
            val current = parseAmount(amount.text.toString())
            val converted = current?.let { convert(it) }
            reversed = !reversed
            amount.setText(converted?.let { plain(it) } ?: "")
            amount.setSelection(amount.text.length)
            calculate()
        }

        result.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = result.text.toString().substringAfter(" ") // drop the "Bs."/"$"/"€" prefix
            cm.setPrimaryClip(ClipData.newPlainText("Tasa BCV", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        val creditSwitch = findViewById<Switch>(R.id.show_credit)
        val appCredit = findViewById<TextView>(R.id.credit)
        creditSwitch.isChecked = AppSettings.showCredit(this)
        appCredit.visibility = if (creditSwitch.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        creditSwitch.setOnCheckedChangeListener { _, on ->
            AppSettings.setShowCredit(this, on)
            appCredit.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            RateWidgetProvider.refreshAll(this, error = false) // redraw widgets right away
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }

        findViewById<Button>(R.id.btn_pin).setOnClickListener {
            val mgr = getSystemService(AppWidgetManager::class.java)
            val cn = ComponentName(this, RateWidgetProvider::class.java)
            if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(cn, null, null)
            else Toast.makeText(this, R.string.pin_manual, Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            // Samsung "Sleeping apps" can delay background updates; this opens the app's battery page.
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
        refresh()
    }

    // ---- calculator ----

    private val isUsd get() = currency.checkedRadioButtonId == R.id.cur_usd
    private val code get() = if (isUsd) "USD" else "EUR"
    private val symbol get() = if (isUsd) "$" else "€"

    private fun currentRate(): Double? {
        val s = RateRepository.load(this)
        return (if (isUsd) s.usd else s.eur)?.value
    }

    /** Converts in the current direction, or null if there's no rate yet. */
    private fun convert(value: Double): Double? {
        val rate = currentRate() ?: return null
        return if (reversed) value / rate else value * rate
    }

    private fun calculate() {
        inSymbol.text = if (reversed) "Bs." else symbol
        val rate = currentRate()
        if (rate == null) {
            result.text = if (reversed) "$symbol 0,00" else "Bs. 0,00"
            rateUsed.setText(R.string.no_rate)
            return
        }
        val value = parseAmount(amount.text.toString()) ?: 0.0
        val out = convert(value) ?: 0.0
        result.text = if (reversed) "$symbol ${money(out)}" else "Bs. ${money(out)}"
        rateUsed.text = getString(R.string.rate_used, RateWidgetProvider.formatRate(rate), code)
    }

    /**
     * Accepts "12,50", "12.50", "1.234,56" or "1,234.56":
     * the last separator is the decimal point, any earlier ones are thousands.
     */
    private fun parseAmount(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val last = maxOf(s.lastIndexOf(','), s.lastIndexOf('.'))
        val cleaned = if (last < 0) s else {
            val intPart = s.substring(0, last).replace(".", "").replace(",", "")
            val decPart = s.substring(last + 1).replace(".", "").replace(",", "")
            // A lone separator followed by exactly 3 digits (e.g. "1.500") is a thousands mark.
            if (decPart.length == 3 && s.count { it == '.' || it == ',' } == 1 && s[last] == '.') intPart + decPart
            else "$intPart.$decPart"
        }
        return cleaned.toDoubleOrNull()
    }

    private fun money(v: Double): String =
        NumberFormat.getNumberInstance(ve).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }.format(v)

    /** Plain editable number for the input box, with a decimal comma: 1234,56 */
    private fun plain(v: Double): String = String.format(Locale.US, "%.2f", v).replace('.', ',')

    // ---- rates ----

    private fun refresh() {
        findViewById<TextView>(R.id.status).setText(R.string.updating)
        launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { RateRepository.save(this@MainActivity, RateRepository.fetch()) }.isSuccess
            }
            RateWidgetProvider.refreshAll(this@MainActivity, error = !ok)
            show()
            if (!ok) findViewById<TextView>(R.id.status).setText(R.string.offline)
        }
    }

    private fun show() {
        val s = RateRepository.load(this)
        findViewById<TextView>(R.id.usd).text = s.usd?.let { RateWidgetProvider.formatRate(it.value) } ?: "—"
        findViewById<TextView>(R.id.eur).text = s.eur?.let { RateWidgetProvider.formatRate(it.value) } ?: "—"
        findViewById<TextView>(R.id.status).text = s.usd?.let {
            getString(R.string.value_date, RateWidgetProvider.formatDay(it.date))
        } ?: getString(R.string.no_data)
        calculate()
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}
