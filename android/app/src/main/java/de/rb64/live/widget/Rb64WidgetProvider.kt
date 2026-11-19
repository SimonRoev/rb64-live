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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import de.rb64.live.MainActivity
import de.rb64.live.R
import de.rb64.live.RenderWorker
import de.rb64.live.Repo
import de.rb64.live.Row
import de.rb64.live.histShort
import de.rb64.live.sourceText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pendler-Widget nach Regeln v2: Petrol-Kopf mit Richtung tauschen, naechster Zug gross,
 * danach Liste (fruehere Zuege per Antippen), Fusszeile mit Datenstand und Aktualisieren.
 */
class Rb64WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SWAP -> { Repo.toggleDirection(ctx); updateAll(ctx) }
            ACTION_PAST -> { Repo.togglePast(ctx); updateAll(ctx); scheduleRender(ctx, 3 * 60 + 5) }
            ACTION_REFRESH -> {
                Repo.markRefreshing(ctx)
                updateAll(ctx)
                scheduleRender(ctx, 42)          // falls keine Antwort kommt: Anzeige zuruecksetzen
                Repo.requestRefresh(ctx)
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_SWAP = "de.rb64.live.SWAP"
        const val ACTION_REFRESH = "de.rb64.live.REFRESH"
        const val ACTION_PAST = "de.rb64.live.PAST"
        private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)

        fun updateAll(ctx: Context) {
            Rb64SmallWidget.updateAll(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Rb64WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val v = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        /** Widget nach einer Weile neu zeichnen (Aktualisieren-Anzeige / fruehere Zuege ausblenden). */
        fun scheduleRender(ctx: Context, seconds: Long) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<RenderWorker>().setInitialDelay(seconds, TimeUnit.SECONDS).build()
            )
        }

        private fun broadcast(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, Rb64WidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Oeffnet die App direkt im Detailblatt dieses Zugs. */
        fun openTrain(ctx: Context, train: String, dep: String): PendingIntent = PendingIntent.getActivity(
            ctx, (train + dep).hashCode(), Intent(ctx, MainActivity::class.java).putExtra("train", train).putExtra("dep", dep)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openApp(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        /* Regeln v2: Pille nur bei Abweichung (ab +3 oder Ausfall), nur dann ist die Zeit farbig;
         * veraltete Daten (ab 15 min) ohne Statusfarben. */
        private data class Status(val text: String, val fg: Int, val bg: Int)

        private fun status(r: Row, stale: Boolean): Status? = when {
            r.cancelled -> if (stale) Status("fällt aus", R.color.widget_muted, R.drawable.pill_neutral)
                else Status("fällt aus", R.color.status_bad, R.drawable.pill_bad)
            r.bus -> Status("Ersatzbus", R.color.widget_muted, R.drawable.pill_neutral)
            r.depDelay >= 3 && stale -> Status("+${r.depDelay}", R.color.widget_muted, R.drawable.pill_neutral)
            r.depDelay >= 6 -> Status("+${r.depDelay}", R.color.status_bad, R.drawable.pill_bad)
            r.depDelay >= 3 -> Status("+${r.depDelay}", R.color.status_warn, R.drawable.pill_warn)
            else -> null
        }

        private fun delayColor(d: Int, stale: Boolean) = when {
            stale -> R.color.widget_text
            d >= 6 -> R.color.status_bad
            d >= 3 -> R.color.status_warn
            else -> R.color.widget_text
        }

        private fun setPill(ctx: Context, v: RemoteViews, id: Int, st: Status?) {
            v.setViewVisibility(id, if (st != null) View.VISIBLE else View.GONE)
            if (st == null) return
            v.setTextViewText(id, st.text)
            v.setTextColor(id, ctx.getColor(st.fg))
            v.setInt(id, "setBackgroundResource", st.bg)
        }

        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_root)
            val snap = Repo.load(ctx)
            val rel = Repo.currentRel(ctx, snap)
            val route = Repo.currentRoute(ctx)
            v.setTextViewText(R.id.route, "${rel?.from ?: de.rb64.live.Line.name(route.first)} → ${rel?.to ?: de.rb64.live.Line.name(route.second)}")
            v.setOnClickPendingIntent(R.id.swap, broadcast(ctx, ACTION_SWAP, 1))
            v.setOnClickPendingIntent(R.id.footer, broadcast(ctx, ACTION_REFRESH, 2))
            v.setOnClickPendingIntent(R.id.past_toggle, broadcast(ctx, ACTION_PAST, 3))
            v.setOnClickPendingIntent(R.id.route, openApp(ctx))
            v.setOnClickPendingIntent(R.id.hero, openApp(ctx))

            // Effekte aus "Darstellung": Kopfleiste farbig oder hell, Schattenstreifen
            val vis = Repo.visuals(ctx)
            v.setViewVisibility(R.id.strip, if (vis.widgetStrip) View.VISIBLE else View.GONE)
            val onHeader = ctx.getColor(if (vis.colorHeader) R.color.widget_on_header else R.color.widget_text)
            v.setInt(R.id.header, "setBackgroundColor", ctx.getColor(if (vis.colorHeader) R.color.widget_header else R.color.widget_card))
            v.setTextColor(R.id.route, onHeader)
            v.setInt(R.id.swap, "setColorFilter", onHeader)

            val note = snap?.notes?.firstOrNull()
            v.setViewVisibility(R.id.note, if (note != null) View.VISIBLE else View.GONE)
            if (note != null) v.setTextViewText(R.id.note, note)

            // Datenstand: bis 5 min grau, ab 5 gelb, ab 15 "veraltet"
            val stale = snap?.stale() == true
            val age = snap?.ageMs() ?: 0L
            v.setTextViewText(R.id.updated, when {
                Repo.isRefreshing(ctx) -> "Aktualisiere …"
                snap == null -> "Antippen zum Laden"
                stale -> "Stand " + HM.format(Date(snap.fresh)) + " · veraltet"
                else -> "Stand " + HM.format(Date(snap.fresh))
            })
            val footerColor = ctx.getColor(if (age > 5 * 60_000) R.color.status_warn else R.color.widget_muted)
            v.setTextColor(R.id.updated, footerColor)
            v.setInt(R.id.footer_icon, "setColorFilter", footerColor)

            val showPast = Repo.showPast(ctx)
            val past = rel?.let { Repo.pastRows(it) }.orEmpty()
            val upcoming = rel?.let { Repo.visibleRows(it, false) }.orEmpty()
            v.setViewVisibility(R.id.past_toggle, if (past.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.past_toggle,
                if (showPast) "Frühere Züge ausblenden" else "${past.size} ${if (past.size == 1) "früherer Zug" else "frühere Züge"} zeigen")

            // Naechster Zug (bzw. angehefteter "dein Zug")
            val pin = Repo.pinnedTrain(ctx)
            val hero = upcoming.firstOrNull { it.train == pin } ?: upcoming.firstOrNull()
            v.setViewVisibility(R.id.hero, if (hero != null) View.VISIBLE else View.GONE)
            if (hero != null) {
                v.setOnClickPendingIntent(R.id.hero, openTrain(ctx, hero.train, hero.depPlanned))
                v.setTextViewText(R.id.hero_time, if (hero.cancelled) strike(hero.depPlanned) else hero.depExpected)
                v.setTextColor(R.id.hero_time, ctx.getColor(if (hero.cancelled) R.color.widget_muted else delayColor(hero.depDelay, stale)))
                v.setViewVisibility(R.id.hero_plan, if (!hero.cancelled && hero.depDelay >= 1) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.hero_plan, "Plan ${hero.depPlanned}")
                setPill(ctx, v, R.id.hero_pill, status(hero, stale))
                v.setTextViewText(R.id.hero_sub, if (hero.cancelled) "Nächste Möglichkeit: " +
                    (upcoming.getOrNull(1)?.depExpected ?: "–")
                    else "→ ${hero.arrExpected}" + (if (hero.train == pin) " · dein Zug" else "") + (hero.arrPlatform?.let { " · an Gl. $it" } ?: "") +
                        (hero.histShort()?.takeIf { vis.history }?.let { " · $it" } ?: ""))
                val why = hero.reason?.takeIf { !hero.cancelled }?.let { r ->
                    (if (hero.ownForecast) "Vermutlich: " else "") + r.replaceFirstChar { it.uppercase() } +
                        (hero.sourceText()?.takeIf { vis.source }?.let { " · $it" } ?: "")
                }
                v.setViewVisibility(R.id.hero_reason, if (why != null) View.VISIBLE else View.GONE)
                if (why != null) v.setTextViewText(R.id.hero_reason, why)
            }

            val rows = (if (showPast) past else emptyList()) + upcoming.filter { it !== hero }
            if (rows.isEmpty()) {
                v.setViewVisibility(R.id.list, View.GONE)
                v.setViewVisibility(R.id.empty, View.VISIBLE)
                v.setViewVisibility(R.id.list_box, if (hero == null) View.VISIBLE else View.GONE)
                if (snap != null) v.setTextViewText(R.id.empty, "Keine Fahrten in den nächsten Stunden")
                return v
            }
            v.setViewVisibility(R.id.list_box, View.VISIBLE)
            v.setViewVisibility(R.id.list, View.VISIBLE)
            v.setViewVisibility(R.id.empty, View.GONE)

            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
            rows.forEachIndexed { i, r ->
                val past = r.past || r.depEpoch < System.currentTimeMillis() - 60_000
                items.addItem((r.train + r.depPlanned).hashCode().toLong() * 31 + i, if (past) pastRow(ctx, r, stale) else row(ctx, r, stale))
            }
            v.setRemoteAdapter(R.id.list, items.build())
            v.setPendingIntentTemplate(R.id.list, openApp(ctx))
            return v
        }

        /** Fruehere Fahrt kompakt: Zeit mit tatsaechlicher Verspaetung, damit mehr aktuelle Zuege Platz haben. */
        private fun pastRow(ctx: Context, r: Row, stale: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_row_past)
            if (r.cancelled) {
                v.setTextViewText(R.id.dep, strike(r.depPlanned)); v.setTextColor(R.id.dep, ctx.getColor(R.color.widget_muted))
                v.setViewVisibility(R.id.dep_plan, View.GONE)
                v.setTextViewText(R.id.arr, "abgefahren")
                v.setTextViewText(R.id.delay, "fiel aus"); v.setTextColor(R.id.delay, ctx.getColor(R.color.status_bad))
            } else {
                v.setTextViewText(R.id.dep, r.depExpected)
                v.setTextColor(R.id.dep, ctx.getColor(delayColor(r.depDelay, stale)))
                v.setViewVisibility(R.id.dep_plan, if (r.depDelay >= 1) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.dep_plan, "Plan ${r.depPlanned}")
                v.setTextViewText(R.id.arr, "→ ${r.arrExpected} · abgefahren")
                v.setTextViewText(R.id.delay, if (r.depDelay >= 1) "+${r.depDelay}" else "pünktlich")
                v.setTextColor(R.id.delay, ctx.getColor(if (r.depDelay >= 3) delayColor(r.depDelay, stale) else R.color.widget_muted))
            }
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("train", r.train).putExtra("dep", r.depPlanned))
            return v
        }

        private fun row(ctx: Context, r: Row, stale: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_row)
            val muted = ctx.getColor(R.color.widget_muted)
            val past = false
            when {
                r.cancelled -> {
                    v.setTextViewText(R.id.dep, strike(r.depPlanned)); v.setTextColor(R.id.dep, muted)
                    v.setTextViewText(R.id.arr, strike("→ ${r.arrPlanned}"))
                    v.setViewVisibility(R.id.dep_plan, View.GONE)
                }
                else -> {
                    v.setTextViewText(R.id.dep, r.depExpected)
                    v.setTextColor(R.id.dep, ctx.getColor(delayColor(r.depDelay, stale)))
                    v.setViewVisibility(R.id.dep_plan, if (r.depDelay >= 1) View.VISIBLE else View.GONE)
                    v.setTextViewText(R.id.dep_plan, "Plan ${r.depPlanned}")
                    v.setTextViewText(R.id.arr, "→ ${r.arrExpected}")
                }
            }
            val st = if (past) null else status(r, stale)
            setPill(ctx, v, R.id.pill, st)
            val info = if (past || st != null || !Repo.visuals(ctx).history) null else r.histShort()
            v.setViewVisibility(R.id.info, if (info != null) View.VISIBLE else View.GONE)
            if (info != null) v.setTextViewText(R.id.info, info)
            val why = r.reason?.takeIf { !past && !r.cancelled && st != null }?.replaceFirstChar { it.uppercase() }
            v.setViewVisibility(R.id.reason, if (why != null) View.VISIBLE else View.GONE)
            if (why != null) v.setTextViewText(R.id.reason, why)
            v.setOnClickFillInIntent(R.id.row, Intent().putExtra("train", r.train).putExtra("dep", r.depPlanned))
            return v
        }

        private fun strike(s: String) = SpannableString(s).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
