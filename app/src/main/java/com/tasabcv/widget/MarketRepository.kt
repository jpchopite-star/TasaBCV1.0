package com.tasabcv.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Market (non-official) USD rate: Bs per USDT on Binance P2P. */
data class MarketRate(val value: Double, val time: Long, val source: String)

object MarketRepository {

    const val SOURCE_BINANCE = "Binance P2P"
    const val SOURCE_PARALELO = "Paralelo"

    private const val BINANCE_P2P = "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search"
    private const val DOLARAPI_ALL = "https://ve.dolarapi.com/v1/dolares"

    /** Binance first; if it's unreachable, the "paralelo" average from DolarApi (labelled as such). */
    fun fetch(): MarketRate =
        runCatching { fetchBinance() }.getOrElse { fetchParalelo() }

    /**
     * Ads where you SELL USDT for bolívares (what you get when changing dollars to Bs).
     * Uses the median of the 5 best prices so one odd ad doesn't skew it.
     */
    private fun fetchBinance(): MarketRate {
        val body = JSONObject()
            .put("asset", "USDT")
            .put("fiat", "VES")
            .put("tradeType", "SELL")
            .put("page", 1)
            .put("rows", 10)
            .put("payTypes", JSONArray())
            .put("publisherType", JSONObject.NULL)
            .toString()
        val json = JSONObject(http(BINANCE_P2P, body))
        val data = json.getJSONArray("data")
        val prices = (0 until data.length())
            .mapNotNull { data.getJSONObject(it).optJSONObject("adv")?.optString("price")?.toDoubleOrNull() }
            .filter { it > 0 }
            .take(5)
            .sorted()
        require(prices.isNotEmpty()) { "Binance: sin anuncios" }
        val median = prices[prices.size / 2]
        return MarketRate(median, System.currentTimeMillis(), SOURCE_BINANCE)
    }

    private fun fetchParalelo(): MarketRate {
        val arr = JSONArray(http(DOLARAPI_ALL, null))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("fuente") == "paralelo") {
                val v = o.getDouble("promedio")
                require(v > 0)
                return MarketRate(v, System.currentTimeMillis(), SOURCE_PARALELO)
            }
        }
        error("Paralelo no disponible")
    }

    private fun http(url: String, postJson: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) TasaBCV/1.4")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (postJson != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postJson.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} en $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---- cache ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("market", Context.MODE_PRIVATE)

    fun save(ctx: Context, r: MarketRate) {
        prefs(ctx).edit()
            .putFloat("value", r.value.toFloat())
            .putLong("time", r.time)
            .putString("source", r.source)
            .apply()
    }

    fun load(ctx: Context): MarketRate? {
        val p = prefs(ctx)
        val v = p.getFloat("value", 0f).toDouble()
        if (v <= 0) return null
        return MarketRate(v, p.getLong("time", 0), p.getString("source", SOURCE_BINANCE) ?: SOURCE_BINANCE)
    }
}
