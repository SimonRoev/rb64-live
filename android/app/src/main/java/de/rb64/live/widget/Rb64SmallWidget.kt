package de.rb64.live.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import de.rb64.live.MainActivity
import de.rb64.live.R
import de.rb64.live.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kleines Widget (2x1): nur der naechste bzw. angeheftete Zug der aktiven Strecke. Antippen oeffnet die App. */
class Rb64SmallWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    companion object {
        private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Rb64SmallWidget::class.java))
            if (ids.isEmpty()) return
            val v = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        private fun strike(s: String) = SpannableString(s).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 7,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            val snap = Repo.load(ctx)
            val rel = Repo.currentRel(ctx, snap)
            val upcoming = rel?.let { Repo.visibleRows(it, false) }.orEmpty()
            val pin = Repo.pinnedTrain(ctx)
            val r = upcoming.firstOrNull { it.train == pin } ?: upcoming.firstOrNull()
            val stale = snap?.stale() == true
            val route = rel?.let { "${it.from} → ${it.to}" } ?: ""
            if (r == null) {
                v.setTextViewText(R.id.time, "–")
                v.setViewVisibility(R.id.plan, View.GONE)
                v.setViewVisibility(R.id.pill, View.GONE)
                v.setTextViewText(R.id.sub, if (snap == null) "Noch keine Daten" else route)
                return v
            }
            val col = when {
                stale || r.cancelled -> R.color.widget_muted
                r.depDelay >= 6 -> R.color.status_bad
                r.depDelay >= 3 -> R.color.status_warn
                else -> R.color.widget_text
            }
            v.setTextViewText(R.id.time, if (r.cancelled) strike(r.depPlanned) else r.depExpected)
            v.setTextColor(R.id.time, ctx.getColor(col))
            v.setViewVisibility(R.id.plan, if (!r.cancelled && r.depDelay >= 1) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.plan, "Plan ${r.depPlanned}")
            v.setOnClickPendingIntent(R.id.root, Rb64WidgetProvider.openTrain(ctx, r.train, r.depPlanned))
            val pill = when {
                r.cancelled -> Triple("fällt aus", R.color.status_bad, R.drawable.pill_bad)
                r.bus -> Triple("Bus", R.color.widget_muted, R.drawable.pill_neutral)
                stale && r.depDelay >= 3 -> Triple("+${r.depDelay}", R.color.widget_muted, R.drawable.pill_neutral)
                r.depDelay >= 6 -> Triple("+${r.depDelay}", R.color.status_bad, R.drawable.pill_bad)
                r.depDelay >= 3 -> Triple("+${r.depDelay}", R.color.status_warn, R.drawable.pill_warn)
                else -> null
            }
            v.setViewVisibility(R.id.pill, if (pill != null) View.VISIBLE else View.GONE)
            if (pill != null) {
                v.setTextViewText(R.id.pill, pill.first)
                v.setTextColor(R.id.pill, ctx.getColor(pill.second))
                v.setInt(R.id.pill, "setBackgroundResource", pill.third)
            }
            val sub = listOfNotNull(
                if (r.cancelled) null else "→ ${r.arrExpected}",
                if (r.train == pin) "dein Zug" else route,
                if (stale) "Stand ${snap?.fresh?.let { HM.format(Date(it)) }}" else null,
            ).joinToString(" · ")
            v.setTextViewText(R.id.sub, sub)
            return v
        }
    }
}
