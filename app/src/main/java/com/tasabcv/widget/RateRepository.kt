package com.tasabcv.widget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime

/** One official BCV rate: bolívares per unit of foreign currency. */
data class Rate(val value: Double, val date: LocalDate)

data class Rates(val usd: Rate, val eur: Rate)

/** What the widget shows: today's rates, the previous ones (for the change arrow) and when we last checked. */
data class Snapshot(
    val usd: Rate?,
    val eur: Rate?,
    val usdPrev: Double?,
    val eurPrev: Double?,
    val lastCheck: Long,
)

object RateRepository {

    private const val DOLARAPI_USD = "https://ve.dolarapi.com/v1/dolares/oficial"
    private const val DOLARAPI_EUR = "https://ve.dolarapi.com/v1/euros/oficial"
    private const val BCV_HOME = "https://www.bcv.org.ve/"

    /** Fetches from DolarApi (which mirrors BCV); falls back to reading bcv.org.ve directly. */
    fun fetch(): Rates {
        val first = runCatching { fetchDolarApi() }
        if (first.isSuccess) return first.getOrThrow()
        return runCatching { fetchBcv() }.getOrElse { e ->
            throw Exception("No se pudo obtener la tasa", first.exceptionOrNull() ?: e)
        }
    }

    private fun fetchDolarApi(): Rates {
        fun parse(json: String): Rate {
            val o = JSONObject(json)
            val value = o.getDouble("promedio")
            val date = OffsetDateTime.parse(o.getString("fechaActualizacion")).toLocalDate()
            require(value > 0)
            return Rate(value, date)
        }
        return Rates(parse(get(DOLARAPI_USD)), parse(get(DOLARAPI_EUR)))
    }

    private fun fetchBcv(): Rates {
        val html = get(BCV_HOME)
        fun rateFor(id: String): Double {
            val m = Regex("""id="$id"[\s\S]*?<strong>\s*([\d.,]+)\s*</strong>""").find(html)
                ?: error("BCV: $id no encontrado")
            // BCV writes "39,18130000" (comma decimal).
            return m.groupValues[1].replace(".", "").replace(",", ".").toDouble()
        }
        val dateStr = Regex("""date-display-single"[^>]*content="(\d{4}-\d{2}-\d{2})""").find(html)
            ?.groupValues?.get(1)
        val date = dateStr?.let { LocalDate.parse(it) } ?: LocalDate.now()
        return Rates(Rate(rateFor("dolar"), date), Rate(rateFor("euro"), date))
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "TasaBCV-Widget/1.0 (Android)")
        conn.setRequestProperty("Accept", "application/json, text/html")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} en $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---- local cache (SharedPreferences) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rates", Context.MODE_PRIVATE)

    fun save(ctx: Context, rates: Rates) {
        val p = prefs(ctx)
        val e = p.edit()
        fun store(key: String, r: Rate) {
            val oldDate = p.getString("${key}_date", null)?.let(LocalDate::parse)
            val oldValue = p.getFloat("${key}_value", 0f).toDouble()
            // New publication date -> remember the previous rate for the change indicator.
            if (oldDate != null && r.date.isAfter(oldDate) && oldValue > 0) {
                e.putFloat("${key}_prev", oldValue.toFloat())
            }
            e.putFloat("${key}_value", r.value.toFloat())
            e.putString("${key}_date", r.date.toString())
        }
        store("usd", rates.usd)
        store("eur", rates.eur)
        e.putLong("last_check", System.currentTimeMillis())
        e.apply()
    }

    fun load(ctx: Context): Snapshot {
        val p = prefs(ctx)
        fun rate(key: String): Rate? {
            val d = p.getString("${key}_date", null) ?: return null
            return Rate(p.getFloat("${key}_value", 0f).toDouble(), LocalDate.parse(d))
        }
        fun prev(key: String) = p.getFloat("${key}_prev", 0f).toDouble().takeIf { it > 0 }
        return Snapshot(rate("usd"), rate("eur"), prev("usd"), prev("eur"), p.getLong("last_check", 0))
    }
}

/** User settings (separate from the rate cache). */
object AppSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun showCredit(ctx: Context): Boolean = prefs(ctx).getBoolean("show_credit", true)

    fun setShowCredit(ctx: Context, show: Boolean) {
        prefs(ctx).edit().putBoolean("show_credit", show).apply()
    }
}
