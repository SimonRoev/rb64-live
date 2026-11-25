package de.rb64.live.ride

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** GPS-Punkt: Zeit (ms), Position, Geschwindigkeit (m/s, -1 = unbekannt), Genauigkeit (m). */
data class Pt(val t: Long, val lat: Double, val lon: Double, val speed: Float, val acc: Float)

/** Markierung waehrend der Fahrt (vor Signal, voll, Durchsage, Stoerung). */
data class Tag(val t: Long, val kind: String, val lat: Double?, val lon: Double?)

/** Erkannter Halt: an einem Bahnhof (station != null) oder auf freier Strecke. */
data class StopEvent(val station: String?, val arr: Long, val dep: Long?, val lat: Double, val lon: Double) {
    val seconds: Long? get() = dep?.let { (it - arr) / 1000 }
}

/** Was zum Start bekannt war: Zug, Strecke, Plan- und Prognosezeiten je Halt ("HH:mm"). */
data class RideInfo(
    val id: String, val relId: String, val train: String, val from: String, val to: String,
    val depPlanned: String, val depEpochPlanned: Long,
    val stops: List<String>, val planned: Map<String, String>, val dbExpected: Map<String, String>,
    /** Urspruenglich gewaehlter Zug, falls die gemessene Abfahrt zu einem anderen passte. */
    val corrected: String? = null,
    /** Wetterwarnungen, die waehrend der Fahrt galten (fuer die Fahrkarte). */
    val weather: List<String> = emptyList(),
)

data class RideState(
    val info: RideInfo,
    val started: Long,
    val points: List<Pt> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val events: List<StopEvent> = emptyList(),
    val ended: Long? = null,
)

val TAG_LABELS = linkedMapOf(
    "signal" to "🚦 vor Signal",
    "voll" to "👥 sehr voll",
    "durchsage" to "📢 Durchsage",
    "stoerung" to "⚠ Störung",
)

/**
 * Halte-Erkennung: steht der Zug (Geschwindigkeit < 1,5 m/s), beginnt ein Halt; faehrt er wieder, endet er.
 * An einem Bahnhof zaehlt schon ein kurzer Halt (>= 8 s, z. B. Grottenkamp), auf freier Strecke erst >= 20 s.
 * Mehrere Halte am selben Bahnhof kurz hintereinander (GPS-Zittern beim Stehen) werden zusammengefasst.
 */
object Analyzer {
    private const val STOP_SPEED = 1.5f      // m/s
    private const val MIN_STOP_MS = 20_000L        // freie Strecke
    private const val MIN_STATION_STOP_MS = 8_000L // am Bahnhof
    private const val MERGE_MS = 180_000L          // Halte am selben Bahnhof mit < 3 min Fahrt dazwischen = ein Halt

    /** Aktuelle Geschwindigkeit in km/h (null, wenn der letzte Punkt aelter als 20 s ist). */
    fun speedKmh(points: List<Pt>, now: Long = System.currentTimeMillis()): Int? {
        val p = points.lastOrNull() ?: return null
        if (now - p.t > 20_000) return null
        return (speedOf(points.getOrNull(points.size - 2), p) * 3.6f).toInt()
    }

    /** Hoechstgeschwindigkeit in km/h (nur Punkte mit brauchbarer Genauigkeit). */
    fun maxKmh(points: List<Pt>): Int? =
        points.filter { it.speed >= 0f && it.acc <= 30f }.maxOfOrNull { it.speed * 3.6f }?.toInt()

    fun speedOf(prev: Pt?, p: Pt): Float {
        if (p.speed >= 0f) return p.speed
        if (prev == null || p.t <= prev.t) return 0f
        val d = FloatArray(1)
        android.location.Location.distanceBetween(prev.lat, prev.lon, p.lat, p.lon, d)
        return d[0] / ((p.t - prev.t) / 1000f)
    }

    fun events(points: List<Pt>): List<StopEvent> {
        val raw = mutableListOf<StopEvent>()
        var stopStart: Int? = null
        var prev: Pt? = null
        fun long(ev: StopEvent, ms: Long) = ms >= (if (ev.station != null) MIN_STATION_STOP_MS else MIN_STOP_MS)
        points.forEachIndexed { i, p ->
            val v = speedOf(prev, p)
            if (v < STOP_SPEED) {
                if (stopStart == null) stopStart = i
            } else if (stopStart != null) {
                val s = stopStart!!
                val ev = make(points.subList(s, i), p.t)
                if (long(ev, p.t - points[s].t)) raw += ev
                stopStart = null
            }
            prev = p
        }
        // laufender Halt (Zug steht gerade)
        stopStart?.let { s ->
            val ev = make(points.subList(s, points.size), null)
            if (long(ev, points.last().t - points[s].t)) raw += ev
        }
        // Halte am selben Bahnhof kurz hintereinander zusammenfassen
        val out = mutableListOf<StopEvent>()
        for (ev in raw) {
            val last = out.lastOrNull()
            if (last != null && ev.station != null && last.station == ev.station && last.dep != null && ev.arr - last.dep < MERGE_MS) {
                out[out.size - 1] = last.copy(dep = ev.dep)
            } else out += ev
        }
        return out
    }

    /** Fahrt von Hand beendet, kurz nachdem der Zug am Ziel ankam: Ankunft aus den Punkten ergaenzen. */
    fun withArrival(events: List<StopEvent>, points: List<Pt>, dest: String): List<StopEvent> {
        if (events.lastOrNull()?.station == dest) return events
        val after = events.lastOrNull()?.let { e -> e.dep ?: e.arr } ?: 0L
        val near = points.filter { it.t > after && Stations.near(it.lat, it.lon)?.name == dest }
        if (near.isEmpty()) return events
        var prev: Pt? = null
        val stopped = near.firstOrNull { p -> (speedOf(prev, p) < STOP_SPEED).also { prev = p } } ?: near.last()
        return events + StopEvent(dest, stopped.t, null, stopped.lat, stopped.lon)
    }

    private fun make(seg: List<Pt>, dep: Long?): StopEvent {
        val lat = seg.map { it.lat }.average()
        val lon = seg.map { it.lon }.average()
        return StopEvent(Stations.near(lat, lon)?.name, seg.first().t, dep, lat, lon)
    }

    /** Gemessene Abfahrt je Bahnhof (letzter Moment am Bahnsteig). */
    /** Abweichung gemessene Zeit gegen Planzeit "HH:mm" in Minuten. */
    fun deltaMin(planned: String?, measured: Long?): Int? {
        if (planned == null || measured == null) return null
        val p = planned.split(":").takeIf { it.size == 2 } ?: return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = measured }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        var d = m - (p[0].toInt() * 60 + p[1].toInt())
        if (d < -720) d += 1440
        return d
    }

    fun departures(events: List<StopEvent>): Map<String, Long> =
        events.filter { it.station != null && it.dep != null }.associate { it.station!! to it.dep!! }

    /** Ist der Zug am Ziel angekommen und steht dort? */
    fun arrivedAt(events: List<StopEvent>, dest: String): Boolean =
        events.lastOrNull()?.let { it.station == dest && it.dep == null } == true
}

/** Laufende Fahrt (fuer UI und Dienst) + Ablage auf dem Handy. */
/** Kurzinfo einer gespeicherten Fahrt fuer die Liste (ohne Messpunkte). */
data class RideMeta(
    val id: String, val train: String, val from: String, val to: String,
    val started: Long, val ended: Long, val arrDelta: Int?, val km: Double, val tags: Int,
    /** Fahrzeit von gemessener Abfahrt bis Ankunft (wie auf der Fahrkarte); null bei aelteren Eintraegen. */
    val rideMin: Int? = null,
) {
    /** Fahrzeit, sonst Dauer der Aufzeichnung. */
    val minutes: Long get() = rideMin?.toLong() ?: ((ended - started) / 60_000)
}

/** Fahrzeit Abfahrt -> Ankunft in Minuten (wie auf der Fahrkarte), null wenn nicht gemessen. */
fun RideState.rideMinutes(): Int? {
    val dep = Analyzer.departures(events)[info.from] ?: events.firstOrNull { it.station == info.from }?.dep
    val arr = events.lastOrNull { it.station == info.to }?.arr
    return if (dep != null && arr != null && arr > dep) ((arr - dep) / 60_000).toInt() else null
}

object RideStore {
    private val _state = MutableStateFlow<RideState?>(null)
    val state: StateFlow<RideState?> = _state
    /** Neueste gespeicherte Fahrt (fuer "Angekommen" und Upload). */
    private val _last = MutableStateFlow<RideState?>(null)
    val last: StateFlow<RideState?> = _last
    /** Alle gespeicherten Fahrten, neueste zuerst. */
    private val _history = MutableStateFlow<List<RideMeta>>(emptyList())
    val history: StateFlow<List<RideMeta>> = _history
    private var loaded = false

    private const val MAX_RIDES = 300            // ca. 30 kB je Fahrt

    private fun file(ctx: Context) = File(ctx.filesDir, "ride_current.json")
    private fun lastFile(ctx: Context) = File(ctx.filesDir, "ride_last.json")      // alt (nur eine Fahrt)
    private fun dir(ctx: Context) = File(ctx.filesDir, "rides").apply { mkdirs() }
    private fun rideFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    /** Fahrt-IDs sind UUIDs; alles andere (z. B. "../x" aus einer manipulierten Sicherung) wird abgelehnt. */
    private val ID_OK = Regex("^[A-Za-z0-9-]{8,64}$")
    fun validId(id: String) = ID_OK.matches(id)

    /** Schreiben ueber eine Zwischendatei: bricht der Prozess mitten im Schreiben ab, bleibt die alte Datei heil. */
    private fun writeSafe(f: File, text: String) {
        val a = android.util.AtomicFile(f)
        val out = a.startWrite()
        try { out.write(text.toByteArray()); a.finishWrite(out) } catch (e: Exception) { a.failWrite(out); throw e }
    }
    private fun readSafe(f: File): String = String(android.util.AtomicFile(f).readFully())

    fun init(ctx: Context) {
        if (_state.value == null) _state.value = runCatching { fromJson(JSONObject(readSafe(file(ctx)))) }.getOrNull()
        if (loaded) return
        loaded = true
        var list = runCatching { JSONArray(readSafe(indexFile(ctx))).let { a -> List(a.length()) { metaFrom(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList())
        // Fahrtdateien, die im Index fehlen (Index beschaedigt oder alte Schreibluecke), wieder aufnehmen -
        // nie Fahrten verlieren, nur weil der Index nicht lesbar war
        val known = list.map { it.id }.toSet()
        val missing = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") && it.name != "index.json" &&
            it.name.removeSuffix(".json") !in known }.orEmpty()
        if (missing.isNotEmpty()) list = list + missing.mapNotNull { f ->
            runCatching { meta(fromJson(JSONObject(readSafe(f)))) }.getOrNull()?.takeIf { validId(it.id) }
        }
        // Umzug: bisher gab es nur die letzte Fahrt
        runCatching { fromJson(JSONObject(lastFile(ctx).readText())) }.getOrNull()?.let { old ->
            if (list.none { it.id == old.info.id }) {
                if (validId(old.info.id)) writeSafe(rideFile(ctx, old.info.id), toJson(old).toString())
                list = listOf(meta(old)) + list
            }
            lastFile(ctx).delete()
        }
        _history.value = list.sortedByDescending { it.started }
        saveIndex(ctx)
        _last.value = _history.value.firstOrNull()?.let { load(ctx, it.id) }
        // einmalig: Fahrzeit (Abfahrt -> Ankunft) fuer aeltere Eintraege nachtragen, im Hintergrund
        if (list.any { it.rideMin == null }) Thread {
            val fixed = list.associate { m -> m.id to (m.rideMin ?: load(ctx, m.id)?.rideMinutes()) }
            _history.update { h -> h.map { m -> if (m.rideMin == null) m.copy(rideMin = fixed[m.id]) else m } }
            saveIndex(ctx)
        }.start()
    }

    /** Ganze Fahrt mit Messpunkten laden. */
    fun load(ctx: Context, id: String): RideState? =
        if (!validId(id)) null else runCatching { fromJson(JSONObject(readSafe(rideFile(ctx, id)))) }.getOrNull()

    /** Gespeicherte Fahrt loeschen (lokal; die Cloud-Kopie markiert der Aufrufer). */
    fun delete(ctx: Context, id: String) {
        if (validId(id)) android.util.AtomicFile(rideFile(ctx, id)).delete()
        _history.update { h -> h.filter { it.id != id } }
        saveIndex(ctx)
        if (_last.value?.info?.id == id) _last.value = _history.value.firstOrNull()?.let { load(ctx, it.id) }
    }

    /** Fahrt aus einer Sicherung uebernehmen (false = war schon da oder unvollstaendig). */
    fun importRide(ctx: Context, s: RideState): Boolean {
        init(ctx)
        if (s.ended == null || !validId(s.info.id) || _history.value.any { it.id == s.info.id }) return false
        writeSafe(rideFile(ctx, s.info.id), toJson(s).toString())
        val before = _history.value.map { it.id }.toSet()
        _history.update { h -> (h + meta(s)).sortedByDescending { it.started }.take(MAX_RIDES) }
        dropBeyondLimit(ctx, before + s.info.id)
        saveIndex(ctx)
        _last.value = _history.value.firstOrNull()?.let { load(ctx, it.id) }
        return true
    }

    private val indexLock = Any()
    private fun saveIndex(ctx: Context) = runCatching {
        synchronized(indexLock) {
            writeSafe(indexFile(ctx), JSONArray().apply { _history.value.forEach { put(metaJson(it)) } }.toString())
        }
    }

    /** Nur Fahrten loeschen, die gerade ueber das Limit gefallen sind - nie "alles, was nicht im Index steht". */
    private fun dropBeyondLimit(ctx: Context, before: Set<String>) {
        val now = _history.value.map { it.id }.toSet()
        (before - now).filter { validId(it) }.forEach { android.util.AtomicFile(rideFile(ctx, it)).delete() }
    }

    fun meta(s: RideState): RideMeta {
        val arr = s.events.lastOrNull { it.station == s.info.to }?.arr
        var m = 0.0
        val pts = s.points.filter { it.acc <= 60f }
        val d = FloatArray(1)
        for (i in 1 until pts.size) {
            android.location.Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon, d); m += d[0]
        }
        return RideMeta(s.info.id, s.info.train, s.info.from, s.info.to, s.started, s.ended ?: s.started,
            Analyzer.deltaMin(s.info.planned[s.info.to], arr), m / 1000, s.tags.size, s.rideMinutes())
    }

    private fun metaJson(m: RideMeta) = JSONObject().apply {
        put("id", m.id); put("train", m.train); put("from", m.from); put("to", m.to); put("started", m.started); put("ended", m.ended)
        m.arrDelta?.let { put("arrDelta", it) }; put("km", m.km); put("tags", m.tags)
        m.rideMin?.let { put("rideMin", it) }
    }

    private fun metaFrom(o: JSONObject) = RideMeta(o.getString("id"), o.getString("train"), o.getString("from"), o.getString("to"),
        o.getLong("started"), o.getLong("ended"), if (o.has("arrDelta")) o.getInt("arrDelta") else null, o.optDouble("km", 0.0), o.optInt("tags"),
        if (o.has("rideMin")) o.getInt("rideMin") else null)

    /** Aktuelle Wetterwarnungen aus den Hinweisen des Servers. */
    private fun weatherNow(ctx: Context): List<String> =
        de.rb64.live.Repo.load(ctx)?.notes?.filter { it.startsWith("Wetterwarnung") }?.map { it.removePrefix("Wetterwarnung:").trim() } ?: emptyList()

    fun start(ctx: Context, info: RideInfo) {
        val s = RideState(info.copy(weather = runCatching { weatherNow(ctx) }.getOrDefault(emptyList())), System.currentTimeMillis())
        _state.value = s
        save(ctx, s)
    }

    fun update(ctx: Context, persist: Boolean = true, f: (RideState) -> RideState) {
        val s = _state.value ?: return
        val n = f(s)
        _state.value = n
        if (persist) save(ctx, n)
    }

    fun finish(ctx: Context): RideState? {
        val s = _state.value ?: return null
        val wx = (s.info.weather + runCatching { weatherNow(ctx) }.getOrDefault(emptyList())).distinct()
        val done = s.copy(info = s.info.copy(weather = wx), ended = System.currentTimeMillis(),
            events = Analyzer.withArrival(Analyzer.events(s.points), s.points, s.info.to))
        writeSafe(rideFile(ctx, done.info.id), toJson(done).toString())
        val before = _history.value.map { it.id }.toSet()
        _history.update { h -> (listOf(meta(done)) + h.filter { it.id != done.info.id }).take(MAX_RIDES) }
        saveIndex(ctx)
        dropBeyondLimit(ctx, before)          // aelteste Fahrten ueber dem Limit entfernen
        _last.value = done
        _state.value = null
        android.util.AtomicFile(file(ctx)).delete()
        ctx.getSharedPreferences("rb64", Context.MODE_PRIVATE).edit().putLong("ride_ended", done.ended!!).apply()
        de.rb64.live.Cloud.queueUpload(ctx, done.info.id)                // bis der Upload bestaetigt ist
        return done
    }

    /** Laufende Aufzeichnung verwerfen (nie losgefahren). */
    fun discard(ctx: Context) { _state.value = null; android.util.AtomicFile(file(ctx)).delete() }


    private fun save(ctx: Context, s: RideState) = runCatching { writeSafe(file(ctx), toJson(s).toString()) }

    // ---------------------------------------------------------------- JSON
    fun toJson(s: RideState): JSONObject = JSONObject().apply {
        val i = s.info
        put("info", JSONObject().apply {
            put("id", i.id); put("relId", i.relId); put("train", i.train); put("from", i.from); put("to", i.to)
            put("depPlanned", i.depPlanned); put("depEpochPlanned", i.depEpochPlanned)
            put("stops", JSONArray(i.stops)); put("planned", JSONObject(i.planned as Map<*, *>))
            put("dbExpected", JSONObject(i.dbExpected as Map<*, *>))
            i.corrected?.let { put("corrected", it) }
            if (i.weather.isNotEmpty()) put("weather", JSONArray(i.weather))
        })
        put("started", s.started); s.ended?.let { put("ended", it) }
        put("points", JSONArray().apply { s.points.forEach { p -> put(JSONArray(listOf(p.t, p.lat, p.lon, p.speed.toDouble(), p.acc.toDouble()))) } })
        put("tags", JSONArray().apply { s.tags.forEach { t -> put(JSONObject().apply { put("t", t.t); put("kind", t.kind); t.lat?.let { put("lat", it) }; t.lon?.let { put("lon", it) } }) } })
        put("events", JSONArray().apply { s.events.forEach { e -> put(JSONObject().apply {
            put("station", e.station ?: JSONObject.NULL); put("arr", e.arr); e.dep?.let { put("dep", it) }; put("lat", e.lat); put("lon", e.lon) }) } })
    }

    private fun strMap(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

    fun fromJson(o: JSONObject): RideState {
        val i = o.getJSONObject("info")
        val info = RideInfo(
            i.getString("id"), i.getString("relId"), i.getString("train"), i.getString("from"), i.getString("to"),
            i.getString("depPlanned"), i.optLong("depEpochPlanned"),
            i.getJSONArray("stops").let { a -> List(a.length()) { a.getString(it) } },
            strMap(i.optJSONObject("planned")), strMap(i.optJSONObject("dbExpected")),
            if (i.has("corrected")) i.getString("corrected") else null,
            i.optJSONArray("weather")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )
        val pts = o.optJSONArray("points")?.let { a -> List(a.length()) { k -> a.getJSONArray(k).let {
            Pt(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getDouble(3).toFloat(), it.getDouble(4).toFloat()) } } } ?: emptyList()
        val tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { k -> a.getJSONObject(k).let {
            Tag(it.getLong("t"), it.getString("kind"), if (it.has("lat")) it.getDouble("lat") else null, if (it.has("lon")) it.getDouble("lon") else null) } } } ?: emptyList()
        val ev = o.optJSONArray("events")?.let { a -> List(a.length()) { k -> a.getJSONObject(k).let {
            StopEvent(if (it.isNull("station")) null else it.getString("station"), it.getLong("arr"),
                if (it.has("dep")) it.getLong("dep") else null, it.getDouble("lat"), it.getDouble("lon")) } } } ?: emptyList()
        return RideState(info, o.getLong("started"), pts, tags, ev, if (o.has("ended")) o.getLong("ended") else null)
    }
}
