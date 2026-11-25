package de.rb64.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Calendar

/** Zeitfenster in Minuten seit Mitternacht. */
data class Window(val from: Int, val to: Int) {
    fun contains(min: Int) = min in from..to
    override fun toString() = "%02d:%02d–%02d:%02d".format(from / 60, from % 60, to / 60, to % 60)
    fun encode() = "$from-$to"

    companion object {
        fun decode(s: String?): Window? = s?.split("-")?.takeIf { it.size == 2 }
            ?.let { runCatching { Window(it[0].toInt(), it[1].toInt()) }.getOrNull() }
    }
}

/** Standard-Zeitfenster: Hinrichtung morgens, Rueckrichtung nachmittags. */
val WINDOW_HIN = Window(6 * 60, 9 * 60 + 30)
val WINDOW_BACK = Window(14 * 60, 18 * 60)

/** Fruehere feste Strecken-IDs -> neue IDs "von-nach" (seq), damit Einstellungen erhalten bleiben. */
private val OLD_IDS = mapOf("haeger_hbf" to "3-1", "hbf_haeger" to "1-3", "haeger_bst" to "3-8", "bst_haeger" to "8-3")

/** Einstellungen fuer Benachrichtigungen (lokal auf dem Handy). */
data class Settings(
    val enabled: Boolean = false,
    val vibrate: Boolean = false,
    val threshold: Int = 5,          // ab +X min
    val onlyCancel: Boolean = false,
    val leadMin: Int = 30,           // nur fuer Abfahrten in den naechsten X min
    // Welche Strecken/Zuege, wann und an welchen Tagen: siehe NotifyRule / Rules (beliebig viele)
) {
    companion object {
        fun load(ctx: Context): Settings {
            val p = Repo.prefs(ctx)
            val d = Settings()
            return Settings(
                enabled = p.getBoolean("n_enabled", d.enabled),
                vibrate = p.getBoolean("n_vibrate", d.vibrate),
                threshold = p.getInt("n_threshold", d.threshold),
                onlyCancel = p.getBoolean("n_only_cancel", d.onlyCancel),
                leadMin = p.getInt("n_lead", d.leadMin),
            )
        }
    }

    fun save(ctx: Context) {
        val e = Repo.prefs(ctx).edit()
            .putBoolean("n_enabled", enabled).putBoolean("n_vibrate", vibrate)
            .putInt("n_threshold", threshold).putBoolean("n_only_cancel", onlyCancel)
            .putInt("n_lead", leadMin)
        e.apply()
    }
}

/**
 * Erzeugt Benachrichtigungen aus den empfangenen Daten - bewusst sparsam:
 * nur fuer eingeschaltete Strecken in ihrem Zeitfenster, kurz vor Abfahrt,
 * eine Benachrichtigung pro Zug (wird aktualisiert statt gestapelt), mit Knopf
 * "Zug genommen", der die Strecke fuer den Rest des Zeitfensters stumm schaltet.
 */
object Notifier {
    const val CH_QUIET = "rb64_quiet"
    const val CH_VIBRATE = "rb64_vibrate"
    const val ACTION_MUTE = "de.rb64.live.MUTE"

    const val CH_PIN = "rb64_pin"
    const val CH_PIN_ALERT = "rb64_pin_alert"
    const val PIN_ID = 6410
    const val CH_INFO = "rb64_info"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Wichtige Hinweise zur App, z. B. Störungen oder Wartung"
        })
        nm.createNotificationChannel(NotificationChannel(CH_PIN, "Dein Zug (Sperrbildschirm)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
            description = "Der angeheftete Zug mit aktueller Abfahrt, still"
        })
        nm.createNotificationChannel(NotificationChannel(CH_PIN_ALERT, "Dein Zug: Änderungen", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 200, 120, 200)
            description = "Wenn sich die Abfahrt deines angehefteten Zugs ändert oder er ausfällt"
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Verspätungen (lautlos)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
            description = "Ausfälle und Verspätungen deiner Züge, ohne Ton und Vibration"
        })
        nm.createNotificationChannel(NotificationChannel(CH_VIBRATE, "Verspätungen (Vibration)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 250, 150, 250)
            description = "Ausfälle und Verspätungen deiner Züge, mit Vibration"
        })
    }

    /** Freie Nachricht von der VM anzeigen. */
    fun showMessage(ctx: Context, title: String, body: String) {
        if (!permitted(ctx)) return
        createChannels(ctx)
        val n = android.app.Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt() + 7000, n)
    }

    fun permitted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun today(): String = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }

    fun isMuted(ctx: Context, relId: String) = Repo.prefs(ctx).getString("mute_$relId", null) == today()

    fun mute(ctx: Context, relId: String) {
        Repo.prefs(ctx).edit().putString("mute_$relId", today()).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.group == relId }.forEach { nm.cancel(it.id) }
    }

    /** Alle Zug-Benachrichtigungen entfernen (z. B. beim Start einer Fahrt). Sie tragen die Strecke als Gruppe. */
    fun cancelTrainNotifications(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.group != null }.forEach { nm.cancel(it.id) }
    }

    fun unmute(ctx: Context, relId: String) = Repo.prefs(ctx).edit().remove("mute_$relId").apply()

    private fun minutesOf(hm: String): Int? = hm.split(":").takeIf { it.size == 2 }
        ?.let { runCatching { it[0].toInt() * 60 + it[1].toInt() }.getOrNull() }

    /** "Dein Zug": still auf dem Sperrbildschirm, mit Vibration bei Aenderung ab X min oder Ausfall.
     *  Waehrend einer Aufzeichnung dieses Zugs uebernimmt das Live Update. */
    /** Fahrt mit dem angehefteten Zug ist zu Ende: Zug loslassen und Meldung entfernen. */
    fun onRideFinished(ctx: Context, train: String) {
        if (Repo.pinnedTrain(ctx) == train) Repo.unpin(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(PIN_ID)
    }

    /** [atStation] = gerade am Bahnhof dieses Zugs angekommen (Geofence): einmal hervorheben, "Los" sofort zeigen. */
    fun onPinned(ctx: Context, snap: Snapshot?, atStation: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val p = Repo.prefs(ctx)
        val train = Repo.pinnedTrain(ctx)
        de.rb64.live.ride.RideStore.init(ctx)
        val riding = de.rb64.live.ride.RideStore.state.value?.info?.train == train
        if (train == null || snap == null || riding || !permitted(ctx)) { nm.cancel(PIN_ID); return }
        val rel = Repo.pinnedRelId(ctx)?.let { Repo.relById(snap, it) } ?: Repo.currentRel(ctx, snap) ?: run { nm.cancel(PIN_ID); return }
        val r = rel.rows.firstOrNull { it.train == train } ?: run { nm.cancel(PIN_ID); return }
        val now = System.currentTimeMillis()
        // am Ziel angekommen (laut Daten) -> Zug loslassen, Meldung weg
        if (!r.cancelled && r.arrEpoch > 0 && now > r.arrEpoch + 2 * 60_000L) { Repo.unpin(ctx); nm.cancel(PIN_ID); return }

        // Aenderung gegenueber der letzten Meldung (erste Anzeige ist nur der Ausgangswert)
        val key = if (r.cancelled) -999 else r.depDelay
        val last = p.getInt("pin_last_delay", Int.MIN_VALUE)
        val changed = last != Int.MIN_VALUE && (key == -999 && last != -999 ||
            key != -999 && last != -999 && kotlin.math.abs(key - last) >= Repo.pinThreshold(ctx))
        if (last == Int.MIN_VALUE || changed) p.edit().putInt("pin_last_delay", key).apply()
        // am Bahnhof: merken (bis kurz nach der Abfahrt), damit auch spaetere Aktualisierungen "Los" zeigen
        if (atStation) p.edit().putString("pin_here", r.train).putLong("pin_here_until", r.depEpoch + 10 * 60_000L).apply()
        val here = p.getString("pin_here", null) == r.train && now < p.getLong("pin_here_until", 0)
        val alert = (changed && Repo.pinNotify(ctx)) || atStation
        if (!Repo.pinLock(ctx) && !alert) { nm.cancel(PIN_ID); return }

        val departed = r.depEpoch < now - 60_000
        val title = when {
            r.cancelled -> "Dein Zug ${r.depPlanned} fällt aus"
            departed -> "Dein Zug ist unterwegs · an ${r.arrExpected}"
            r.depDelay >= 1 -> "Dein Zug ${r.depExpected} (+${r.depDelay}) → ${rel.to}"
            else -> "Dein Zug ${r.depExpected} → ${rel.to} · pünktlich"
        }
        val text = listOfNotNull(
            if (!r.cancelled && r.depDelay >= 1 && !departed) "Plan ${r.depPlanned}" else null,
            r.platform?.let { "Gleis $it" },
            if (!r.cancelled && !departed) "an ${r.arrExpected}" else null,
            if (!r.cancelled && !departed) r.whereText() else null,
            r.reason?.takeIf { !r.cancelled }?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ").let { if (here && !departed && !r.cancelled) "Du bist am Bahnhof · $it" else it }
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java)
            .putExtra("train", r.train).putExtra("dep", r.depPlanned).putExtra("rel", rel.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, if (alert) CH_PIN_ALERT else CH_PIN)   // Builder, wird unten ggf. zum Live Update
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(Repo.pinLock(ctx))
            .setOnlyAlertOnce(!alert)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(open)
            .setTimeoutAfter(maxOf(60_000L, r.arrEpoch - now + 5 * 60_000L))
        // "Ich fahre mit" erst kurz vor der Abfahrt (~5 min) - oder sofort, wenn du schon am Bahnhof bist.
        // Am Bahnhof startet "Los" die Aufzeichnung direkt (auch gesperrt), sonst oeffnet es die App.
        val soon = r.depEpoch - now <= 5 * 60_000L
        if (!departed && !r.cancelled && !r.bus && (soon || here)) {
            val action = if (here) {
                val pi = PendingIntent.getForegroundService(ctx, 11,
                    de.rb64.live.ride.RideService.startIntent(ctx, de.rb64.live.ride.RideService.infoFrom(rel, r)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Notification.Action.Builder(null, "▶ Los, ich fahre mit", pi).setAuthenticationRequired(false).build()
            } else {
                val checkin = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java)
                    .putExtra("checkin", true).putExtra("train", r.train).putExtra("nonce", Repo.checkinNonce(ctx))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Notification.Action.Builder(null, "Ich fahre mit", checkin).build()
            }
            n.addAction(action)
        }
        // Android 16+: als Live Update oben (Statusleisten-Chip, Sperrbildschirm), gleiches Aussehen wie die Fahrt (LiveCard)
        if (android.os.Build.VERSION.SDK_INT >= 36 && Repo.pinLock(ctx) && !r.cancelled) {
            val L = LiveCard
            val names = r.stops.map { it.name }.ifEmpty { listOf(rel.from, rel.to) }
            val trip = snap.trips.firstOrNull { it.train == r.train }
            val toHigher = names.size >= 2 && L.seqOf(names.last()) > L.seqOf(names.first())
            if (!departed) {
                // der Zug faehrt auf deinen Bahnhof zu; kurz vor der Abfahrt zaehlt der Chip herunter
                val bar = L.approach(rel.from, toHigher, trip?.pos ?: -1.0) ?: de.rb64.live.LiveCard.Bar(names, 0.0, names.lastIndex, null)
                L.apply(ctx, n, bar, "ab ${r.depExpected}", L.countdown(r.depEpoch, now),
                    r.platform?.let { LiveCard.Badge.Platform(it) })
            } else {
                val pos = trip?.pos?.takeIf { it >= 0 }
                val cross = L.crossingIndex(trip, names, pos)
                val prog = pos?.let { L.fraction(names, it) } ?: r.marker.takeIf { it in 0.0..1.0 } ?: 0.0
                L.apply(ctx, n, de.rb64.live.LiveCard.Bar(names, prog, names.lastIndex, cross, delay = r.arrDelay, start = true),
                    L.chipFor(r.arrDelay, r.arrExpected), badge = LiveCard.Badge.Delay(r.arrDelay))
                // steht gerade am Kreuzungshalt: das ist die wichtigste Nachricht
                val cs = cross?.let { names[it] }
                if (cs != null && pos != null && kotlin.math.abs(pos - L.seqOf(cs)) < 0.05)
                    n.setContentTitle("Dein Zug wartet in $cs auf Gegenzug")
            }
        }
        nm.notify(PIN_ID, n.build())
    }

    fun onPayload(ctx: Context, snap: Snapshot) {
        val s = Settings.load(ctx)
        if (!s.enabled || !permitted(ctx)) return
        // waehrend einer Fahrtaufzeichnung sitzt man schon im Zug -> keine Meldungen zu anderen Zuegen
        de.rb64.live.ride.RideStore.init(ctx)
        if (de.rb64.live.ride.RideStore.state.value != null) return
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        val state = ctx.getSharedPreferences("rb64_notify", Context.MODE_PRIVATE)
        if (state.getString("day", null) != today()) state.edit().clear().putString("day", today()).apply()

        val now = System.currentTimeMillis()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // alle aktiven Regeln fuer heute, je Strecke zusammengefasst (gleicher Zug -> eine Meldung)
        val active = Rules.load(ctx).filter { it.on && dow in it.days && !isMuted(ctx, it.relId) }
        for ((relId, rules) in active.groupBy { it.relId }) {
            val rel = Repo.relById(snap, relId) ?: continue
            for (r in rel.rows) {
                if (r.past || r.bus || r.depEpoch == 0L) continue
                if (rules.none { it.matches(r) }) continue                 // Zeitfenster bzw. gewaehlter Zug
                val minsAhead = (r.depEpoch - now) / 60_000
                if (minsAhead < 0 || minsAhead > s.leadMin) continue      // nur kurz vor Abfahrt
                val st = when {
                    r.cancelled -> "x"
                    s.onlyCancel -> "0"
                    r.depDelay >= s.threshold -> "d" + (r.depDelay / 5) * 5
                    else -> "0"
                }
                val key = "${rel.id}:${r.train}:${r.depPlanned}"
                val old = state.getString(key, null)
                state.edit().putString(key, st).apply()
                val notify = if (old == null) st != "0" else (old != st && (st != "0" || old != "0"))
                if (!notify) continue

                val (title, text) = message(rel, r, st)
                val muteIntent = PendingIntent.getBroadcast(
                    ctx, rel.id.hashCode(), Intent(ctx, MuteReceiver::class.java).setAction(ACTION_MUTE).putExtra("rel", rel.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = Notification.Builder(ctx, if (s.vibrate) CH_VIBRATE else CH_QUIET)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setGroup(rel.id)
                    .setAutoCancel(true)
                    .setTimeoutAfter(maxOf(60_000L, r.depEpoch - now + 10 * 60_000L))   // verschwindet nach Abfahrt
                    // eigener requestCode je Strecke+Zug, sonst ueberschreiben sich die Extras mehrerer Meldungen
                    .setContentIntent(PendingIntent.getActivity(ctx, (rel.id + r.train).hashCode(), Intent(ctx, MainActivity::class.java)
                        .putExtra("train", r.train).putExtra("dep", r.depPlanned).putExtra("rel", rel.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .addAction(Notification.Action.Builder(null, "Zug genommen · Rest stumm", muteIntent).build())
                    .build()
                nm.notify(key.hashCode(), n)
            }
        }
    }

    private fun message(rel: Rel, r: Row, st: String): Pair<String, String> {
        val head = "${r.depPlanned} ${rel.from} → ${rel.to}"
        return when {
            st == "x" -> {
                val alt = rel.rows.firstOrNull { !it.past && !it.cancelled && it.depEpoch > r.depEpoch }
                "$head fällt aus" to (alt?.let { "Nächste Möglichkeit: ${if (it.bus) "Ersatzbus" else "Zug"} ${it.depExpected}" +
                        if (!it.bus && it.depDelay > 0) " (+${it.depDelay})" else "" } ?: "Keine Alternative in den Daten.")
            }
            st == "0" -> "$head wieder pünktlich" to "Abfahrt ${r.depExpected}."
            else -> "$head: +${r.depDelay} min" to
                    ("Abfahrt ca. ${r.depExpected}, Ankunft ca. ${r.arrExpected}." +
                            (r.reason?.let { "\n" + it.replaceFirstChar { c -> c.uppercase() } } ?: ""))
        }
    }
}

/** Knopf "Zug genommen" in der Benachrichtigung: Strecke fuer heute stumm schalten. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Notifier.ACTION_MUTE) intent.getStringExtra("rel")?.let { Notifier.mute(ctx, it) }
    }
}
