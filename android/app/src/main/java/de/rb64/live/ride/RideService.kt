package de.rb64.live.ride

import de.rb64.live.whereText
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.rb64.live.Cloud
import de.rb64.live.MainActivity
import de.rb64.live.R
import de.rb64.live.Rel
import de.rb64.live.Row
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Zeichnet eine Fahrt auf (Vordergrunddienst mit sichtbarer Benachrichtigung):
 * GPS alle ~5 s, nur Punkte im Bahnkorridor; Markierungen aus App oder Benachrichtigung;
 * endet am Ziel von selbst (Zug steht dort >= 30 s) oder per "Stopp".
 */
class RideService : Service() {

    private val loc by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            for (l in r.locations) {
                if (!Stations.inCorridor(l.latitude, l.longitude)) continue
                val p = Pt(l.time, l.latitude, l.longitude, if (l.hasSpeed()) l.speed else -1f, l.accuracy)
                RideStore.update(this@RideService) { s -> s.copy(points = s.points + p) }
            }
            val s0 = RideStore.state.value ?: return
            val ev = Analyzer.events(s0.points)
            RideStore.update(this@RideService) { it.copy(events = ev) }
            // sobald die Abfahrt gemessen ist: war es wirklich der gewaehlte Zug?
            if (!trainChecked && Analyzer.departures(ev).containsKey(s0.info.from)) {
                trainChecked = true
                RideStore.update(this@RideService) { correctTrain(this@RideService, it) }
            }
            val s = RideStore.state.value ?: return
            val beforeLast = s.info.stops.getOrNull(s.info.stops.size - 2)
            val lastSt = ev.lastOrNull { it.station != null }
            if (!exitTicked && beforeLast != null && lastSt?.station == beforeLast && lastSt.dep != null) {
                exitTicked = true
                exitTick()
            }
            // am Ziel angekommen und steht dort >= 30 s -> fertig
            val last = ev.lastOrNull()
            if (last != null && last.station == s.info.to && last.dep == null &&
                System.currentTimeMillis() - last.arr >= 30_000) { finishRide(); return }
            // Sicherheitsnetz: nach 3 h beenden
            if (System.currentTimeMillis() - s.started > 3 * 3600_000L) { finishRide(); return }
            notifyOngoing()
        }
    }

    /** Einmal je Fahrt: kurzer, leiser Haptik-Tick "naechster Halt ist dein Ausstieg". */
    private var exitTicked = false
    private var trainChecked = false

    private fun exitTick() {
        runCatching {
            val vm = getSystemService(android.os.VibratorManager::class.java)
            vm.defaultVibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RideStore.init(this)
        createChannel(this)
        when (intent?.action) {
            ACTION_START -> {
                val info = intent.getStringExtra("info")?.let { infoFromJson(it) }
                if (info != null && RideStore.state.value == null) RideStore.start(this, info)
                if (RideStore.state.value == null) { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                requestUpdates()
                getSystemService(NotificationManager::class.java).cancel(PROMPT_NOTIF_ID)
                RideStore.state.value?.info?.relId?.let { de.rb64.live.Notifier.mute(this, it) }
                de.rb64.live.Notifier.cancelTrainNotifications(this)
                de.rb64.live.Notifier.onPinned(this, de.rb64.live.Repo.load(this))
            }
            ACTION_TAG -> {
                val kind = intent.getStringExtra("kind") ?: "signal"
                val p = RideStore.state.value?.points?.lastOrNull()
                RideStore.update(this) { it.copy(tags = it.tags + Tag(System.currentTimeMillis(), kind, p?.lat, p?.lon)) }
                notifyOngoing()
            }
            ACTION_STOP -> finishRide()
            ACTION_REFRESH -> notifyOngoing()
            else -> {   // Neustart durch das System: laufende Fahrt fortsetzen
                if (RideStore.state.value == null) { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                requestUpdates()
            }
        }
        return START_STICKY
    }

    private fun requestUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateIntervalMillis(3_000L).build()
        loc.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    private fun finishRide() {
        loc.removeLocationUpdates(cb)
        // vor der Abfahrt abgebrochen (z. B. frueh "Ich fahre mit" gedrueckt): nichts speichern oder hochladen
        val cur = RideStore.state.value
        if (cur != null && !isDeparted(cur)) {
            RideStore.discard(this)
            de.rb64.live.Repo.prefs(this).edit().remove("mute_${cur.info.relId}").apply()   // Meldungen wieder an
            de.rb64.live.Notifier.onPinned(this, de.rb64.live.Repo.load(this))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        RideStore.update(this) { correctTrain(this, it) }
        val done = RideStore.finish(this)
        // angehefteter Zug ist mit dieser Fahrt erledigt (auch wenn der Zug korrigiert wurde: beide pruefen)
        cur?.info?.train?.let { de.rb64.live.Notifier.onRideFinished(this, it) }
        done?.info?.train?.let { de.rb64.live.Notifier.onRideFinished(this, it) }
        if (done != null) {
            Cloud.uploadRide(this, done)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(DONE_NOTIF_ID, Notification.Builder(this, CH_RIDE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Fahrt gespeichert: ${done.info.train} → ${done.info.to}")
                .setContentText("Antippen für die Auswertung")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("summary", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE))
                .build())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyOngoing() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val s = RideStore.state.value
        val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)
        val text = s?.let { st ->
            val last = st.events.lastOrNull { it.station != null && it.dep != null }
            val lastTxt = last?.let { "${it.station} ab ${hm.format(Date(it.dep!!))}" } ?: "Start ${st.info.from}"
            val tags = if (st.tags.isNotEmpty()) " · ${st.tags.size} Markierung(en)" else ""
            lastTxt + tags
        } ?: ""
        fun act(label: String, action: String, kind: String? = null, code: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, code, Intent(this, RideService::class.java).setAction(action).apply { kind?.let { putExtra("kind", it) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAuthenticationRequired(false)      // auf dem Sperrbildschirm ohne Entsperren
            .build()
        // aktuelle Zeiten aus dem letzten Push (nicht die vom Start der Aufzeichnung)
        val live = s?.info?.let { liveRow(this, it) }
        val reached = s?.events?.mapNotNull { it.station }?.toSet().orEmpty()
        val stops = s?.info?.stops.orEmpty()
        val departed = s != null && isDeparted(s)
        val next = stops.firstOrNull { it !in reached && it != s?.info?.from }
        val arrTo = live?.arrExpected ?: s?.info?.let { it.dbExpected[it.to] ?: it.planned[it.to] }
        val kmh = s?.points?.let { Analyzer.speedKmh(it) }
        val title: String
        val body: String
        if (!departed) {
            // noch am Bahnsteig: "Zug kommt" statt "Fahrt laeuft"
            val dep = live?.depExpected ?: s?.info?.depPlanned
            title = "Warte auf ${s?.info?.train ?: ""} → ${s?.info?.to ?: ""}"
            body = listOfNotNull("Abfahrt ca. $dep" + (live?.depDelay?.takeIf { it >= 1 }?.let { " (+$it)" } ?: ""),
                live?.whereText(), live?.platform?.let { "Gleis $it" }).joinToString(" · ")
        } else {
            // Titel kurz halten (Always-on-Display schneidet sonst ab); km/h in die zweite Zeile
            title = "${s?.info?.train ?: ""} → ${s?.info?.to ?: ""}" + (arrTo?.let { " · an $it" } ?: "")
            body = listOfNotNull(next?.let { n ->
                "Nächster Halt $n" + (if (n == s?.info?.to) " (Ausstieg)" else "") +
                    ((live?.stops?.firstOrNull { it.name == n }?.time?.ifBlank { null } ?: s?.info?.dbExpected?.get(n))?.let { " · ca. $it" } ?: "")
            } ?: text, kmh?.let { "$it km/h" }).joinToString(" · ")
        }
        val b = Notification.Builder(this, CH_RIDE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
        if (android.os.Build.VERSION.SDK_INT >= 36 && stops.size >= 2) {
            // Fortschritt: ein Punkt je Halt (gleichmaessig), Position = letzter erreichter Halt (+ halb, wenn schon wieder unterwegs)
            val total = 1000
            val lastIdx = stops.indexOfLast { it in reached }.coerceAtLeast(0)
            val moving = s?.events?.lastOrNull()?.dep != null
            val prog = if (!departed) 0 else (((lastIdx + if (moving && lastIdx < stops.size - 1) 0.5 else 0.0) / (stops.size - 1)) * total).toInt()
            val primary = getColor(R.color.widget_primary)
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(total).setColor(primary)))
                .setProgressPoints(stops.indices.map { i -> Notification.ProgressStyle.Point(i * total / (stops.size - 1)).setColor(primary) })
                .setProgress(prog)
                .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notify))
            b.setStyle(style)
            b.setRequestPromotedOngoing(true)
            if (!departed) (live?.depExpected ?: s?.info?.depPlanned)?.let { b.setShortCriticalText("ab $it") }
            else arrTo?.let { b.setShortCriticalText("an $it") }
        }
        return b
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE))
            .addAction(act("Signal", ACTION_TAG, "signal", 11))
            .addAction(act("Sehr voll", ACTION_TAG, "voll", 12))
            .addAction(act("Beenden", ACTION_STOP, null, 13))
            .build()
    }

    override fun onDestroy() {
        loc.removeLocationUpdates(cb)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "de.rb64.live.RIDE_START"
        const val ACTION_TAG = "de.rb64.live.RIDE_TAG"
        const val ACTION_STOP = "de.rb64.live.RIDE_STOP"
        const val ACTION_REFRESH = "de.rb64.live.RIDE_REFRESH"

        /**
         * Gemessene Abfahrt am Start mit den Zuegen der Strecke vergleichen. Passt ein anderer Zug klar besser
         * (z. B. der verspaetete Vorgaenger stand noch da), wird er eingetragen; der gewaehlte bleibt in "corrected".
         */
        fun correctTrain(ctx: Context, s: RideState): RideState {
            val dep = Analyzer.departures(Analyzer.events(s.points))[s.info.from] ?: return s
            val rel = de.rb64.live.Repo.load(ctx)?.let { de.rb64.live.Repo.relById(it, s.info.relId) } ?: return s
            val cands = rel.rows.filter { !it.bus && it.depEpoch > 0 }
            val best = cands.minByOrNull { kotlin.math.abs(it.depEpoch - dep) } ?: return s
            if (best.train == s.info.train) return s
            val dBest = kotlin.math.abs(best.depEpoch - dep)
            val dCur = cands.firstOrNull { it.train == s.info.train }?.let { kotlin.math.abs(it.depEpoch - dep) } ?: Long.MAX_VALUE
            if (dBest > 6 * 60_000L || dCur - dBest < 4 * 60_000L) return s
            val n = infoFrom(rel, best).copy(id = s.info.id, corrected = s.info.corrected ?: s.info.train)
            android.util.Log.i("RideService", "Zug korrigiert: ${s.info.train} -> ${best.train}")
            return s.copy(info = n)
        }

        /** Aktuelle Daten dieser Fahrt aus dem letzten Push. */
        fun liveRow(ctx: Context, info: RideInfo): Row? =
            de.rb64.live.Repo.load(ctx)?.let { de.rb64.live.Repo.relById(it, info.relId) }?.rows?.firstOrNull { it.train == info.train }

        /** Hat der Zug den Starthalt schon verlassen? (Abfahrt gemessen, spaeterer Halt erreicht oder schnell unterwegs) */
        fun isDeparted(s: RideState): Boolean =
            Analyzer.departures(s.events).containsKey(s.info.from) ||
                s.events.any { it.station != null && it.station != s.info.from } ||
                (Analyzer.speedKmh(s.points) ?: 0) >= 25

        /** Neue Daten angekommen: laufende Fahrt-Benachrichtigung mit aktuellen Zeiten neu zeichnen. */
        fun refresh(ctx: Context) {
            RideStore.init(ctx)
            if (RideStore.state.value == null) return
            runCatching { ctx.startService(Intent(ctx, RideService::class.java).setAction(ACTION_REFRESH)) }
        }
        const val CH_RIDE = "rb64_ride2"          // neu: Wichtigkeit laesst sich nachtraeglich nicht aendern
        const val CH_PROMPT = "rb64_ride_prompt"
        const val NOTIF_ID = 6401
        const val DONE_NOTIF_ID = 6402
        const val PROMPT_NOTIF_ID = 6403

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("rb64_ride")
            // DEFAULT statt LOW: "lautlose" Benachrichtigungen blendet Android auf dem Sperrbildschirm oft aus
            nm.createNotificationChannel(NotificationChannel(CH_RIDE, "Laufende Fahrt", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aufzeichnung mit Knöpfen Signal/voll/Beenden, auch auf dem Sperrbildschirm"
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel(CH_PROMPT, "Mitfahr-Vorschlag am Bahnhof", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "„Ich fahre mit“ für den nächsten Zug, mit Vibration, direkt vom Sperrbildschirm startbar"
                setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 200, 120, 200)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }

        /** Fahrtdaten aus einer Zeile der Abfahrtsliste. */
        fun infoFrom(rel: Rel, r: Row): RideInfo {
            val stops = r.stops.map { it.name }.ifEmpty { listOf(rel.from, rel.to) }
            val expected = r.stops.associate { it.name to it.time }.filterValues { it.isNotBlank() }
            return RideInfo(
                id = UUID.randomUUID().toString(), relId = rel.id, train = r.train, from = rel.from, to = rel.to,
                depPlanned = r.depPlanned, depEpochPlanned = r.depEpoch - r.depDelay * 60_000L,
                stops = stops, planned = mapOf(rel.from to r.depPlanned, rel.to to r.arrPlanned),
                dbExpected = expected + mapOf(rel.from to r.depExpected, rel.to to r.arrExpected),
            )
        }

        fun startIntent(ctx: Context, info: RideInfo): Intent =
            Intent(ctx, RideService::class.java).setAction(ACTION_START).putExtra("info", infoToJson(info))

        fun start(ctx: Context, info: RideInfo) = ctx.startForegroundService(startIntent(ctx, info))

        private fun infoToJson(i: RideInfo) = RideStore.toJson(RideState(i, 0)).getJSONObject("info").toString()
        private fun infoFromJson(s: String): RideInfo = RideStore.fromJson(
            org.json.JSONObject().put("info", org.json.JSONObject(s)).put("started", 0L)).info
    }
}
