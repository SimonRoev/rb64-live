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
    private var liveWasOn = false
    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val cur = RideStore.state.value ?: return
            val now = System.currentTimeMillis()
            // Sicherheitsnetz zuerst: nach 3 h keine Punkte mehr anhaengen (z. B. liegengebliebene Fahrt)
            if (now - cur.started > 3 * 3600_000L) { finishRide(); return }
            val pts = r.locations.filter { Stations.inCorridor(it.latitude, it.longitude) }
                .map { Pt(it.time, it.latitude, it.longitude, if (it.hasSpeed()) it.speed else -1f, it.accuracy) }
            // Punkte und Halte in einem Schritt; auf die Platte hoechstens alle 20 s (spart Akku und Speicher)
            val persist = now - lastSave >= 20_000L
            if (persist) lastSave = now
            RideStore.update(this@RideService, persist) { s ->
                val all = s.points + pts
                s.copy(points = all, events = Analyzer.events(all))
            }
            val s0 = RideStore.state.value ?: return
            val ev = s0.events
            // sobald die Abfahrt gemessen ist: war es wirklich der gewaehlte Zug?
            // falsche Richtung gewaehlt (z. B. "Burgsteinfurt -> Muenster" angetippt, aber Richtung Burgsteinfurt
            // eingestiegen): auf die passende Strecke und den Zug zur gemessenen Abfahrt umstellen
            if (!dirChecked) {
                val fixed = correctDirection(this@RideService, s0)
                if (fixed !== s0) {
                    dirChecked = true; trainChecked = true; exitTicked = false
                    de.rb64.live.Repo.prefs(this@RideService).edit().remove("ride_boarded").apply()
                    RideStore.update(this@RideService) { fixed }
                    android.util.Log.i("RideService", "Richtung korrigiert: ${s0.info.from}->${s0.info.to} => ${fixed.info.from}->${fixed.info.to}")
                } else if (s0.points.mapNotNull { de.rb64.live.LiveGps.seqAt(it.lat, it.lon) }.let { q ->
                        q.size >= 10 && progress(s0, q.last()) - progress(s0, q.first()) >= 0.8 }) dirChecked = true
            }
            if (!trainChecked && Analyzer.departures(ev).containsKey(s0.info.from)) {
                trainChecked = true
                RideStore.update(this@RideService) { correctTrain(this@RideService, it) }
            }
            val s = RideStore.state.value ?: return
            // nicht eingestiegen (automatisch gestartet und weggegangen, oder Zug laengst weg): still verwerfen
            if (notBoarded(s, r.lastLocation)) { finishRide(); return }
            shareLive(s, r.lastLocation)
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

    /**
     * Ohne Einstieg verwerfen (nichts gespeichert, nichts gesendet):
     * - Automatisch gestartet und du bist seit 4 min weg vom Bahnhof, nicht entlang der Strecke (zu Fuss, Rad, Auto).
     * - Automatisch gestartet und der Zug ist laut Daten seit 15 min weg.
     * - Von Hand gestartet und der Zug ist seit 30 min weg (z. B. "Ich fahre mit" gedrueckt, dann doch nicht).
     * Eingestiegen heisst [boarded] - strenger als die Anzeige "abgefahren".
     */
    private var awaySince = 0L
    private fun notBoarded(s: RideState, l: android.location.Location?): Boolean {
        if (isBoarded(s)) { awaySince = 0L; return false }
        val auto = de.rb64.live.Repo.prefs(this).getString("ride_auto", null) == s.info.id
        val now = System.currentTimeMillis()
        val dep = liveRow(this, s.info)?.depEpoch ?: s.info.depEpochPlanned
        if (now > dep + (if (auto) 15 else 30) * 60_000L) return true
        if (!auto) return false
        // ohne neuen Standort (Minuten-Takt): nur den laufenden "weg vom Bahnhof"-Zaehler auswerten, nicht zuruecksetzen
        if (l == null) return awaySince > 0L && now - awaySince >= 4 * 60_000L
        val st = Stations.byName(s.info.from)
        // weg vom Bahnhof, aber nicht ein Stueck die Strecke entlang (ein Zug, der vor einem Signal steht, bleibt)
        val ahead = l?.let { de.rb64.live.LiveGps.seqAt(it.latitude, it.longitude) }?.let { q -> progress(s, q) >= 0.25 } ?: false
        val away = st != null && l != null && l.accuracy < 100f && st.distanceTo(l.latitude, l.longitude) > 500f && !ahead
        if (!away) { awaySince = 0L; return false }
        if (awaySince == 0L) awaySince = now
        return now - awaySince >= 4 * 60_000L
    }

    /**
     * Live-Position teilen (falls eingeschaltet): erst nach der Abfahrt am Einstieg (Warten am Bahnsteig ist keine
     * Zugposition) und bis der Zug am Ziel einfaehrt - der Weg danach wird nie gesendet. Mit den erkannten Halten und
     * zusaetzlich hart nach der Position: wurde die Ankunft am Ziel nicht erkannt, endet das Teilen trotzdem dort.
     * (Spaeter vielleicht: Ende/Start zufaellig ein paar Halte frueher/spaeter, solange es wenige Teilende gibt.)
     */
    private fun shareLive(s: RideState, l: android.location.Location?) {
        val deps = Analyzer.departures(s.events)
        val here = l?.let { de.rb64.live.LiveGps.seqAt(it.latitude, it.longitude) }
        val limit = de.rb64.live.LiveCard.seqOf(s.info.to).toDouble()
        val pastLimit = here != null && limit > 0 && progress(s, here) >= progress(s, limit) - 0.05
        val arrived = s.events.any { it.station == s.info.to }
        // im Zug: entweder schon sicher eingestiegen oder gerade mit Zugtempo unterwegs (gleich nach der Abfahrt)
        val moving = isBoarded(s) || ((Analyzer.speedKmh(s.points) ?: 0) >= 25 && depAtTrainTime(this, s))
        val sharing = deps.containsKey(s.info.from) && s.info.to != s.info.from && !arrived && !pastLimit && moving
        if (sharing && l != null && Stations.inCorridor(l.latitude, l.longitude))
            de.rb64.live.LiveGps.onLocation(this, s.info.train, l.latitude, l.longitude)
        else if (!sharing && liveWasOn) de.rb64.live.LiveGps.stop()
        liveWasOn = sharing
    }

    private var lastSave = 0L
    private var dirChecked = false

    /** Eingestiegen merken: einmal sicher erkannt, bleibt es so (auch wenn spaeter Daten fehlen). */
    private fun isBoarded(s: RideState): Boolean {
        val p = de.rb64.live.Repo.prefs(this)
        if (p.getString("ride_boarded", null) == s.info.id) return true
        if (!boarded(this, s)) return false
        p.edit().putString("ride_boarded", s.info.id).apply()
        return true
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
                val fresh = info != null && RideStore.state.value == null
                if (fresh) {
                    RideStore.start(this, info!!)
                    exitTicked = false; trainChecked = false; awaySince = 0L; dirChecked = false   // Merker gelten je Fahrt
                }
                if (RideStore.state.value == null) { bail(); return START_NOT_STICKY }
                if (!goForeground()) {
                    // Android laesst den Dienst nicht starten (z. B. aus dem Hintergrund): keine halbe Fahrt zuruecklassen
                    if (fresh) RideStore.discard(this)
                    stopSelf(); return START_NOT_STICKY
                }
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
            else -> {   // Neustart durch das System oder die App: laufende Fahrt fortsetzen
                val s = RideStore.state.value
                if (s == null) { bail(); return START_NOT_STICKY }
                if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
                // liegengebliebene Fahrt (z. B. App-Update mitten in der Fahrt, lange her): ohne neues GPS abschliessen,
                // damit kein Punkt von woanders (etwa zu Hause) an die alte Fahrt gehaengt wird
                val lastT = s.points.lastOrNull()?.t ?: s.started
                val now = System.currentTimeMillis()
                if (now - s.started > 3 * 3600_000L || now - lastT > 20 * 60_000L) { finishRide(); return START_NOT_STICKY }
                requestUpdates()
            }
        }
        return START_STICKY
    }

    /** Per startForegroundService gestartet, aber nichts zu tun: kurz in den Vordergrund (Pflicht), dann beenden. */
    private fun bail() {
        runCatching { startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(): Boolean = runCatching {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startTicker()
    }.onFailure { android.util.Log.w("RideService", "Vordergrund-Start abgelehnt", it) }.isSuccess

    /**
     * Eigener Takt (jede Minute), unabhaengig von GPS: Sicherheitsnetz nach 3 h und Verwerfen ohne Einstieg greifen
     * auch, wenn keine Standortmeldungen mehr kommen (Tunnel, GPS aus, Gebaeude).
     */
    private val ticker = android.os.Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val s = RideStore.state.value ?: return
            if (System.currentTimeMillis() - s.started > 3 * 3600_000L || notBoarded(s, null)) { finishRide(); return }
            ticker.postDelayed(this, 60_000L)
        }
    }
    private fun startTicker() { ticker.removeCallbacks(tick); ticker.postDelayed(tick, 60_000L) }

    private fun requestUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateIntervalMillis(3_000L).build()
        loc.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    private fun finishRide() {
        loc.removeLocationUpdates(cb)
        de.rb64.live.Repo.prefs(this).edit().remove("ride_auto").apply()
        de.rb64.live.LiveGps.stop()
        // vor der Abfahrt abgebrochen (z. B. frueh "Ich fahre mit" gedrueckt): nichts speichern oder hochladen
        val cur = RideStore.state.value
        val wasBoarded = cur != null && isBoarded(cur)
        de.rb64.live.Repo.prefs(this).edit().remove("ride_boarded").apply()
        if (cur != null && !wasBoarded) {
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
            // Dienst laeuft weiter, bis die Fahrt oben ist (hoechstens 2 min) - sonst friert Android die App im
            // Hintergrund ein und der Upload haengt bis zum naechsten Oeffnen
            val main = android.os.Handler(Looper.getMainLooper())
            var closed = false
            // schliessen erst nach dem Upload - aber nie, wenn inzwischen schon die naechste Fahrt laeuft
            val close = Runnable {
                if (!closed && RideStore.state.value == null) { closed = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            main.postDelayed(close, 120_000L)
            Cloud.uploadRide(this, done) { main.post(close) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(DONE_NOTIF_ID, Notification.Builder(this, CH_RIDE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Fahrt gespeichert: ${done.info.train} → ${done.info.to}")
                .setContentText("Antippen für die Auswertung")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("summary", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE))
                .build())
            return
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
        val trip = de.rb64.live.Repo.load(this)?.trips?.firstOrNull { it.train == s?.info?.train }
        val L = de.rb64.live.LiveCard
        val bar: de.rb64.live.LiveCard.Bar?
        var chip: String? = null
        var countdown: Long? = null
        var badge: de.rb64.live.LiveCard.Badge? = null
        if (!departed) {
            // noch am Bahnsteig: "Zug kommt" statt "Fahrt laeuft"; die Leiste zeigt, wie der Zug auf deinen Bahnhof zufaehrt
            val dep = live?.depExpected ?: s?.info?.depPlanned
            title = "Warte auf ${s?.info?.train ?: ""} → ${s?.info?.to ?: ""}"
            body = listOfNotNull("Abfahrt ca. $dep" + (live?.depDelay?.takeIf { it >= 1 }?.let { " (+$it)" } ?: ""),
                live?.whereText(), live?.platform?.let { "Gleis $it" }).joinToString(" · ")
            val toHigher = stops.size >= 2 && L.seqOf(stops.last()) > L.seqOf(stops.first())
            bar = s?.info?.from?.let { L.approach(it, toHigher, trip?.pos ?: -1.0) }
                ?: de.rb64.live.LiveCard.Bar(stops, 0.0, stops.lastIndex, null)
            countdown = L.countdown(live?.depEpoch ?: s?.info?.depEpochPlanned ?: 0L)
            badge = live?.platform?.let { de.rb64.live.LiveCard.Badge.Platform(it) }
            chip = dep?.let { "ab $it" }
        } else {
            // Zug-Symbol nach deinem GPS (gleitend), sonst nach Daten; Ausstieg rot, Kreuzung bernsteinfarben
            val lp = s?.points?.lastOrNull()
            val seqNow = lp?.let { de.rb64.live.LiveGps.seqAt(it.lat, it.lon) } ?: trip?.pos?.takeIf { it >= 0 }
            val cross = L.crossingIndex(trip, stops, seqNow)
            bar = de.rb64.live.LiveCard.Bar(stops, seqNow?.let { L.fraction(stops, it) } ?: 0.0, stops.lastIndex, cross,
                delay = live?.arrDelay, start = true)
            badge = live?.arrDelay?.let { de.rb64.live.LiveCard.Badge.Delay(it) }
            val crossName = cross?.let { stops[it] }
            val lastEv = s?.events?.lastOrNull()
            val waitingCross = crossName != null && lastEv?.station == crossName && lastEv.dep == null
            val delay = live?.arrDelay
            val nextTime = next?.let { n -> live?.stops?.firstOrNull { it.name == n }?.time?.ifBlank { null } ?: s?.info?.dbExpected?.get(n) }
            // Titel kurz halten (Always-on-Display schneidet sonst ab): was als Naechstes passiert
            title = when {
                waitingCross -> "Wartet in $crossName auf Gegenzug"
                next == null -> "${s?.info?.train ?: ""} → ${s?.info?.to ?: ""}"
                else -> (if (next == s?.info?.to) "Ausstieg " else "Nächster Halt ") + next + (nextTime?.let { " · $it" } ?: "")
            }
            val mins = live?.arrEpoch?.takeIf { it > 0 }?.let { ((it - System.currentTimeMillis()) / 60_000L).toInt() }
            body = listOfNotNull(
                if (delay == null) null else if (delay >= 1) "+$delay" else "pünktlich",
                if (next != null && next != s?.info?.to) (if (mins != null && mins in 1..90) "Ausstieg in $mins min" else arrTo?.let { "an $it" }) else null,
                kmh?.let { "$it km/h" },
            ).joinToString(" · ").ifBlank { text }
            chip = L.chipFor(delay, arrTo)
        }
        val b = Notification.Builder(this, CH_RIDE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
        if (bar != null) L.apply(this, b, bar, chip, countdown, badge)
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
            .addAction(act(if (departed) "Beenden" else "Nicht mitfahren", ACTION_STOP, null, 13))
            .build()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
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

        /** Fortschritt in Fahrtrichtung ab dem Einstieg, in Halten (seq-Differenz, negativ = falsche Richtung). */
        fun progress(s: RideState, seq: Double): Double {
            val a = de.rb64.live.LiveCard.seqOf(s.info.from); val b = de.rb64.live.LiveCard.seqOf(s.info.to)
            if (a <= 0 || b <= 0 || a == b) return 0.0
            return (seq - a) * (if (b > a) 1 else -1)
        }

        /**
         * Wirklich eingestiegen? Strenger als [isDeparted] (das steuert nur die Anzeige), damit Weggehen oder
         * Autofahren nicht als Zugfahrt gespeichert und hochgeladen wird:
         * - an einem weiteren Halt der Strecke gehalten bzw. angekommen, oder
         * - mindestens zwei Halte (bei kurzer Fahrt: fast bis zum Ziel) die Strecke entlang gekommen,
         * - und eine gemessene Abfahrt am Einstieg passt zur Zugzeit (5 min davor bis 30 min danach).
         */
        fun boarded(ctx: Context, s: RideState): Boolean {
            val stops = s.info.stops
            val atOther = s.events.any { it.station != null && it.station != s.info.from && it.station in stops }
            val span = kotlin.math.abs(de.rb64.live.LiveCard.seqOf(s.info.to) - de.rb64.live.LiveCard.seqOf(s.info.from))
            val need = minOf(2.0, span - 0.2)
            val far = need > 0 && s.points.any { p -> de.rb64.live.LiveGps.seqAt(p.lat, p.lon)?.let { progress(s, it) >= need } == true }
            val dep = Analyzer.departures(s.events)[s.info.from]
            val expected = liveRow(ctx, s.info)?.depEpoch ?: s.info.depEpochPlanned
            val timeOk = dep == null || expected <= 0 || dep in (expected - 5 * 60_000L)..(expected + 30 * 60_000L)
            return timeOk && (atOther || far)
        }

        /**
         * Gegenrichtung erkannt (die Fahrt bewegt sich fast einen Halt entgegen der gewaehlten Strecke)? Dann die Strecke
         * ab dem tatsaechlichen Abfahrtsbahnhof in Fahrtrichtung nehmen - bevorzugt eine eigene Strecke, sonst bis zum
         * Linienende - und den Zug, dessen Abfahrt dort am besten zur gemessenen passt. Sonst unveraendert zurueck.
         */
        fun correctDirection(ctx: Context, s: RideState): RideState {
            if (s.info.stops.size < 2) return s
            val seqs = s.points.mapNotNull { de.rb64.live.LiveGps.seqAt(it.lat, it.lon) }
            if (seqs.size < 10) return s
            if (progress(s, seqs.last()) - progress(s, seqs.first()) > -0.8) return s
            val dep = Analyzer.departures(s.events).minByOrNull { it.value } ?: return s
            val a = de.rb64.live.LiveCard.seqOf(dep.key)
            if (a <= 0) return s
            val toHigher = seqs.last() > seqs.first()
            val snap = de.rb64.live.Repo.load(ctx) ?: return s
            val favs = de.rb64.live.Repo.favs(ctx).flatMap { listOf(it.a to it.b, it.b to it.a) }
            val target = favs.firstOrNull { (x, y) -> x == a && (y > x) == toHigher }?.second
                ?: (if (toHigher) Stations.all.size else 1)
            if (target == a) return s
            val rel = runCatching { snap.rel(a, target) }.getOrNull() ?: return s
            val best = rel.rows.filter { !it.bus && it.depEpoch > 0 }.minByOrNull { kotlin.math.abs(it.depEpoch - dep.value) } ?: return s
            if (kotlin.math.abs(best.depEpoch - dep.value) > 10 * 60_000L) return s
            return s.copy(info = infoFrom(rel, best).copy(id = s.info.id, corrected = s.info.corrected ?: s.info.train,
                weather = s.info.weather))
        }

        /** Abfahrt am Einstieg gemessen und zur Zugzeit passend (5 min davor bis 30 min danach)? */
        fun depAtTrainTime(ctx: Context, s: RideState): Boolean {
            val dep = Analyzer.departures(s.events)[s.info.from] ?: return false
            val expected = liveRow(ctx, s.info)?.depEpoch ?: s.info.depEpochPlanned
            return expected <= 0 || dep in (expected - 5 * 60_000L)..(expected + 30 * 60_000L)
        }

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
