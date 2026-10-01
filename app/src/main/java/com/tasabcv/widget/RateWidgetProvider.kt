package com.tasabcv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class RateWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Keep the periodic job alive and draw from cache (no network on the main thread).
        RateWidgetProvider.render(ctx, mgr, ids, status = null)
        RateUpdateWorker.schedule(ctx)
        val stale = System.currentTimeMillis() - RateRepository.load(ctx).lastCheck > 60 * 60 * 1000
        if (stale) RateUpdateWorker.refreshNow(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RateUpdateWorker.schedule(ctx)
        RateUpdateWorker.refreshNow(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            render(ctx, mgr, ids(ctx, mgr), status = ctx.getString(R.string.updating))
            RateUpdateWorker.refreshNow(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.tasabcv.widget.REFRESH"

        private val ve = Locale.forLanguageTag("es-VE")
        private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", ve)

        private fun ids(ctx: Context, mgr: AppWidgetManager) =
            mgr.getAppWidgetIds(ComponentName(ctx, RateWidgetProvider::class.java))

        fun refreshAll(ctx: Context, error: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            render(ctx, mgr, ids(ctx, mgr), if (error) ctx.getString(R.string.offline) else null)
        }

        fun formatRate(v: Double): String =
            NumberFormat.getNumberInstance(ve).apply {
                minimumFractionDigits = 2; maximumFractionDigits = 2
            }.format(v)

        fun formatDay(d: LocalDate): String =
            d.format(dayFmt).replace(".", "").replaceFirstChar { it.uppercase() }

        /** "▲ 0,42 %" style change vs. the previous publication, or null if unknown. */
        fun change(now: Double, prev: Double?): Pair<String, Boolean>? {
            if (prev == null || prev <= 0) return null
            val pct = (now - prev) / prev * 100
            val f = NumberFormat.getNumberInstance(ve).apply {
                minimumFractionDigits = 2; maximumFractionDigits = 2
            }
            val arrow = if (pct >= 0) "▲" else "▼"
            return "$arrow ${f.format(kotlin.math.abs(pct))} %" to (pct >= 0)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray, status: String?) {
            if (ids.isEmpty()) return
            val snap = RateRepository.load(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_rates)

            fun row(valueId: Int, changeId: Int, rate: Rate?, prev: Double?) {
                v.setTextViewText(valueId, rate?.let { formatRate(it.value) } ?: "—")
                val c = rate?.let { change(it.value, prev) }
                if (c == null) {
                    v.setViewVisibility(changeId, View.GONE)
                } else {
                    v.setViewVisibility(changeId, View.VISIBLE)
                    v.setTextViewText(changeId, c.first)
                    v.setTextColor(changeId, ctx.getColor(if (c.second) R.color.up else R.color.down))
                }
            }
            row(R.id.usd_value, R.id.usd_change, snap.usd, snap.usdPrev)
            row(R.id.eur_value, R.id.eur_change, snap.eur, snap.eurPrev)

            // Binance P2P line: rate + gap vs BCV
            val mkt = MarketRepository.load(ctx)
            if (mkt == null) {
                v.setTextViewText(R.id.mkt_value, "—")
                v.setViewVisibility(R.id.mkt_gap, View.GONE)
            } else {
                v.setTextViewText(R.id.mkt_label, if (mkt.source == MarketRepository.SOURCE_BINANCE) "USDT" else "PAR")
                v.setTextViewText(R.id.mkt_value, formatRate(mkt.value))
                val bcv = snap.usd?.value
                if (bcv != null && bcv > 0) {
                    val pct = (mkt.value / bcv - 1) * 100
                    val f = NumberFormat.getNumberInstance(ve).apply {
                        minimumFractionDigits = 1; maximumFractionDigits = 1
                    }
                    val txt = (if (pct >= 0) "+" else "−") + f.format(kotlin.math.abs(pct)) + " %"
                    v.setViewVisibility(R.id.mkt_gap, View.VISIBLE)
                    v.setTextViewText(R.id.mkt_gap, ctx.getString(R.string.widget_gap, txt))
                    v.setTextColor(R.id.mkt_gap, ctx.getColor(if (pct >= 0) R.color.up else R.color.down))
                } else {
                    v.setViewVisibility(R.id.mkt_gap, View.GONE)
                }
            }

            val u = snap.usd?.date
            val e = snap.eur?.date
            val dateLine = when {
                u == null && e == null -> ctx.getString(R.string.no_data)
                u == e || e == null -> ctx.getString(R.string.value_date, formatDay(u!!))
                u == null -> ctx.getString(R.string.value_date, formatDay(e!!))
                else -> "USD ${formatDay(u)} · EUR ${formatDay(e)}"
            }
            v.setTextViewText(R.id.value_date, dateLine)

            val footer = status ?: if (snap.lastCheck > 0) {
                ctx.getString(
                    R.string.checked_at,
                    DateFormat.getTimeInstance(DateFormat.SHORT, ve).format(Date(snap.lastCheck))
                )
            } else ctx.getString(R.string.updating)
            v.setTextViewText(R.id.checked, footer)
            v.setViewVisibility(R.id.credit, if (AppSettings.showCredit(ctx)) View.VISIBLE else View.GONE)

            val refresh = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, RateWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.refresh, refresh)

            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.root, open)

            mgr.updateAppWidget(ids, v)
        }
    }
}
