package de.rb64.live

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.zip.GZIPInputStream

/** Ein Halt in der Detailansicht: Name, erwartete Zeit, Kreuzungshalt an dem der Zug wartet. */
data class Stop(val name: String, val time: String, val hang: Boolean)

/** Bisherige Verspaetung dieser Abfahrt (gleiche Uhrzeit, gleiche Tagesart, letzte 28 Tage). */
data class Hist(val median: Int, val days: Int, val late5: Int, val cancelled: Int)

/** Eine Fahrt (Zeiten als Ortszeit "HH:mm", Epochen in ms). */
data class Row(
    val train: String,
    val depPlanned: String,
    val depExpected: String,
    val depEpoch: Long,
    val depDelay: Int,
    val arrPlanned: String,
    val arrExpected: String,
    val arrEpoch: Long,
    val arrDelay: Int,
    val cancelled: Boolean,
    val bus: Boolean,
    val past: Boolean,
    val reason: String?,
    val platform: String?,
    val arrPlatform: String?,
    val stops: List<Stop>,
    val marker: Double,          // Position auf der Strecke 0..1, -1 = nicht auf der Strecke
    val before: String?,         // "Zug noch in ..."
    val dbDelay: Int? = null,    // Verspaetung laut DB; depDelay ist die eigene Prognose (>= DB)
    val hist: Hist? = null,
    val realtime: Boolean = true, // false = nur Fahrplan (DB hat fuer diese Fahrt noch keine Echtzeitdaten)
) {
    /** Eigene Prognose liegt deutlich ueber der DB (z. B. Kreuzung, die die DB noch nicht kennt). */
    val ownForecast: Boolean get() = !cancelled && !bus && dbDelay != null && depDelay - dbDelay >= 2

    /** Fortschritt fuer die Linie im Widget (0..100), aus erwarteten Zeiten. */
    fun progress(now: Long = System.currentTimeMillis()): Int = when {
        marker in 0.0..1.0 -> (marker * 100).toInt()
        depEpoch == 0L || arrEpoch <= depEpoch -> 0
        now <= depEpoch -> 0
        now >= arrEpoch -> 100
        else -> ((now - depEpoch) * 100 / (arrEpoch - depEpoch)).toInt()
    }
}

data class Rel(val id: String, val from: String, val to: String, val rows: List<Row>) {
    val title: String get() = "$from → $to"
}

/** Halt der Linie RB64 (seq 1 = Münster Hbf ... 14 = Enschede). */
data class LineStation(val seq: Int, val name: String, val wait: Boolean = false)

/** Feste Halteliste (fuer Auswahl, auch ohne Daten). Namen wie vom Server (live.short). */
object Line {
    val stations = listOf(
        LineStation(1, "Münster Hbf"), LineStation(2, "Zentrum Nord"), LineStation(3, "Häger"),
        LineStation(4, "Altenberge"), LineStation(5, "Nordwalde"), LineStation(6, "Borghorst"),
        LineStation(7, "Grottenkamp"), LineStation(8, "Burgsteinfurt"), LineStation(9, "Metelen Land"),
        LineStation(10, "Ochtrup"), LineStation(11, "Gronau"), LineStation(12, "Glanerbrug"),
        LineStation(13, "De Eschmarke"), LineStation(14, "Enschede"),
    )
    fun name(seq: Int) = stations.firstOrNull { it.seq == seq }?.name ?: "?"
    fun seqOf(name: String) = stations.firstOrNull { it.name == name }?.seq
}

/** Halt einer Fahrt; Zeiten in Minuten ab Snapshot.base, Verspaetungen in Minuten (eigene Prognose). */
data class TStop(
    val seq: Int, val par: Int?, val pdp: Int?, val dar: Int?, val ddp: Int?,
    val cancelled: Boolean, val platform: String?, val dbDp: Int?, val hist: Hist?,
)

/** Erklaerung ab einem Halt (Kreuzung = wartet dort auf Gegenzug). */
data class Reason(val seq: Int, val text: String, val crossing: Boolean)

data class Trip(
    val train: String, val toEnschede: Boolean, val bus: Boolean, val stops: List<TStop>,
    val pos: Double,                 // Position als seq (Zwischenwerte = unterwegs), -1 = nicht unterwegs
    val reasons: List<Reason>, val code: String?,
    val realtime: Boolean = true,
)

/** Live-Karte: seq 1 = Hbf ... (Zwischenwerte = unterwegs). */
data class MapStation(val name: String, val seq: Double, val wait: Boolean)
data class MapTrain(val train: String, val toEnschede: Boolean, val seq: Double, val delay: Int, val bus: Boolean, val at: String?)
data class LiveMap(val stations: List<MapStation>, val trains: List<MapTrain>)

/** ts = Sendezeit, fresh = letzte erfolgreiche DB-Abfrage auf dem Server (Datenstand). */
data class Snapshot(
    val ts: Long, val fresh: Long, val notes: List<String>,
    val base: Long, val stations: List<LineStation>, val trips: List<Trip>,
) {
    fun ageMs(now: Long = System.currentTimeMillis()): Long = (now - fresh).coerceAtLeast(0)
    /** Server sendet spaetestens alle 5 min; ab 5 min Stand gelb, ab 15 min veraltet (Warnbanner, keine Statusfarben). */
    fun stale(now: Long = System.currentTimeMillis()): Boolean = ageMs(now) > STALE_MS

    fun name(seq: Int): String = stations.firstOrNull { it.seq == seq }?.name ?: Line.name(seq)

    private fun ep(min: Int) = (base + min) * 60_000L
    private fun hm(min: Int) = HM.format(Date(ep(min)))

    /** Alle Fahrten von Halt [from] nach Halt [to] (seq), aus den Zugdaten berechnet. */
    fun rel(from: Int, to: Int, now: Long = System.currentTimeMillis()): Rel {
        val es = to > from
        fun atOrBefore(x: Int, y: Int) = if (es) x <= y else x >= y      // x liegt in Fahrtrichtung bei/vor y
        val rows = trips.filter { it.toEnschede == es }.mapNotNull { t ->
            val a = t.stops.firstOrNull { it.seq == from } ?: return@mapNotNull null
            val b = t.stops.firstOrNull { it.seq == to } ?: return@mapNotNull null
            val pdp = a.pdp ?: return@mapNotNull null
            val par = b.par ?: return@mapNotNull null
            val cancelled = a.cancelled || b.cancelled
            val dd = if (cancelled) 0 else a.ddp ?: 0
            val da = if (cancelled) 0 else b.dar ?: dd
            // Grund: Erklaerung, die bis zur Abfahrt gilt; sonst was unterwegs (zwischen Start und Ziel) dazukommt
            val atStart = t.reasons.lastOrNull { atOrBefore(it.seq, from) }?.text
            val between = t.reasons.lastOrNull { atOrBefore(it.seq, to) && !atOrBefore(it.seq, from) }?.text
            val reason = when {
                cancelled || t.bus -> null
                dd >= 1 -> atStart ?: t.code ?: "Verspätung von unterwegs"
                da - dd >= 3 -> "unterwegs: " + (between ?: t.code ?: "verliert Zeit")
                else -> null
            }
            // Halte-Streifen: alle Halte zwischen Start und Ziel in Fahrtrichtung
            val section = t.stops.filter { s -> atOrBefore(from, s.seq) && atOrBefore(s.seq, to) }
            val hang = t.reasons.firstOrNull { r -> r.crossing && section.any { it.seq == r.seq } }?.seq
            val strip = section.map { s ->
                val time = when {
                    s.cancelled -> ""
                    s.pdp != null -> hm(s.pdp + (s.ddp ?: 0))
                    s.par != null -> hm(s.par + (s.dar ?: 0))
                    else -> ""
                }
                Stop(name(s.seq), time, s.seq == hang && dd + da > 0)
            }
            // Position: auf dem Abschnitt (0..1) oder "Zug noch in ..."
            var marker = -1.0
            var before: String? = null
            val p = t.pos
            if (p >= 0) {
                val inSection = if (es) p in from.toDouble()..to.toDouble() else p in to.toDouble()..from.toDouble()
                if (inSection) marker = (p - from) / (to - from)
                else if (if (es) p < from else p > from) {
                    val r = Math.round(p).toInt()
                    before = name(if (kotlin.math.abs(p - r) < 0.02) r else if (es) kotlin.math.ceil(p).toInt() else kotlin.math.floor(p).toInt())
                }
            } else {
                val first = t.stops.firstOrNull { it.pdp != null }
                // noch nicht unterwegs: nur fuer bald startende Fahrten zeigen, wo der Zug steht bzw. herkommt
                val startsIn = first?.pdp?.let { ep(it) - now }
                if (startsIn != null && startsIn > -60_000 && startsIn < 20 * 60_000) {
                    if (first.seq == from) marker = 0.0 else if (atOrBefore(first.seq, from)) before = name(first.seq)
                }
            }
            Row(
                train = t.train,
                depPlanned = hm(pdp), depExpected = hm(pdp + dd), depEpoch = ep(pdp + dd), depDelay = dd,
                arrPlanned = hm(par), arrExpected = hm(par + da), arrEpoch = ep(par + da), arrDelay = da,
                cancelled = cancelled, bus = t.bus, past = false, reason = reason,
                platform = a.platform, arrPlatform = b.platform, stops = strip, marker = marker, before = before,
                dbDelay = if (t.bus || cancelled) null else (a.dbDp ?: a.ddp)?.coerceAtLeast(0),
                hist = a.hist,
                realtime = t.realtime,
            )
        }.sortedBy { it.depEpoch - it.depDelay * 60_000L }
        return Rel(relId(from, to), name(from), name(to), rows)
    }

    /** Live-Karte fuer den Abschnitt lo..hi (seq, lo < hi). */
    fun liveMap(lo: Int, hi: Int): LiveMap {
        val st = (stations.ifEmpty { Line.stations }).filter { it.seq in lo..hi }
            .map { MapStation(it.name, it.seq.toDouble(), it.wait) }
        val tr = trips.filter { it.pos >= lo && it.pos <= hi && !it.stops.all { s -> s.cancelled } }.map { t ->
            // Verspaetung am letzten erreichten Halt
            val last = if (t.toEnschede) t.stops.lastOrNull { it.seq <= t.pos + 0.01 } else t.stops.lastOrNull { it.seq >= t.pos - 0.01 }
            val r = Math.round(t.pos).toInt()
            MapTrain(t.train, t.toEnschede, t.pos, last?.let { it.ddp ?: it.dar } ?: 0, t.bus,
                if (kotlin.math.abs(t.pos - r) < 0.02) name(r) else null)
        }
        return LiveMap(st, tr)
    }

    companion object {
        const val STALE_MS = 15 * 60_000L
        const val AUTO_REFRESH_MS = 5 * 60_000L
        private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)
        fun relId(from: Int, to: Int) = "$from-$to"
        /** "3-1" -> (3, 1) */
        fun parseRelId(id: String): Pair<Int, Int>? =
            id.split("-").takeIf { it.size == 2 }?.let { runCatching { it[0].toInt() to it[1].toInt() }.getOrNull() }
    }
}

/** Wartet ein Zug (vermutlich) an einem Kreuzungshalt auf den Gegenzug? */
data class Crossing(val train: String, val seq: Int, val text: String, val toEnschede: Boolean)

/** Kreuzungen im Abschnitt lo..hi: laufende oder bald startende Zuege, nur Halte, die noch vor ihnen liegen. */
fun Snapshot.crossings(lo: Int, hi: Int, now: Long = System.currentTimeMillis()): List<Crossing> = trips.filter { t ->
    t.pos >= 0 || t.stops.firstOrNull { it.pdp != null }?.pdp?.let { (base + it) * 60_000L - now in -60_000L..45 * 60_000L } == true
}.flatMap { t ->
    t.reasons.filter { r ->
        r.crossing && r.seq in lo..hi && (t.pos < 0 || if (t.toEnschede) r.seq >= t.pos - 0.01 else r.seq <= t.pos + 0.01)
    }.map { Crossing(t.train, it.seq, it.text, t.toEnschede) }
}.distinctBy { it.train to it.seq }

/** Gespeicherte Strecke: a = "zu Hause" (z. B. Häger), b = anderes Ende. Hin = a → b, zurück = b → a. */
data class Fav(val a: Int, val b: Int) {
    val hinId get() = Snapshot.relId(a, b)
    val backId get() = Snapshot.relId(b, a)
}

object Repo {
    private const val PREFS = "rb64"
    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Daten
    /** FCM-Feld "z": base64(gzip(JSON)). */
    fun decode(z: String): String =
        GZIPInputStream(ByteArrayInputStream(Base64.decode(z, Base64.DEFAULT))).bufferedReader().use { it.readText() }

    fun parse(json: String): Snapshot {
        val o = JSONObject(json)
        require(o.optInt("v") >= 4) { "altes Datenformat" }
        val notes = o.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val stations = o.optJSONArray("st")?.let { a ->
            List(a.length()) { i -> a.getJSONArray(i).let { LineStation(it.getInt(0), it.getString(1), it.optInt(2) == 1) } }
        } ?: emptyList()
        fun JSONArray.intOrNull(i: Int): Int? = if (length() > i && !isNull(i)) getInt(i) else null
        val trips = o.getJSONArray("trips").let { ta ->
            List(ta.length()) { i ->
                val t = ta.getJSONObject(i)
                val stops = t.getJSONArray("s").let { sa ->
                    List(sa.length()) { k ->
                        val s = sa.getJSONArray(k)
                        val h = if (s.length() > 8 && !s.isNull(8)) s.getJSONArray(8) else null
                        TStop(
                            seq = s.getInt(0), par = s.intOrNull(1), pdp = s.intOrNull(2), dar = s.intOrNull(3), ddp = s.intOrNull(4),
                            cancelled = s.intOrNull(5) == 1,
                            platform = if (s.length() > 6 && !s.isNull(6)) s.getString(6) else null,
                            dbDp = s.intOrNull(7),
                            hist = h?.let { Hist(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3)) },
                        )
                    }
                }
                val reasons = t.optJSONArray("r")?.let { ra ->
                    List(ra.length()) { k -> ra.getJSONArray(k).let { Reason(it.getInt(0), it.getString(1), it.optInt(2) == 1) } }
                } ?: emptyList()
                Trip(t.getString("n"), t.getString("d") == "ES", t.optInt("b") == 1, stops, t.optDouble("p", -1.0),
                    reasons, t.optString("c").ifBlank { null }, realtime = t.optInt("f") != 1)
            }
        }
        val ts = o.getLong("ts")
        return Snapshot(ts, o.optLong("fr", ts).coerceAtMost(ts), notes, o.getLong("base"), stations, trips)
    }

    fun save(ctx: Context, json: String) {
        parse(json) // nur speichern, wenn lesbar
        prefs(ctx).edit().putString("payload", json).remove("refresh_since").apply()
    }

    // ---------------------------------------------------------------- Aktualisieren-Anzeige
    /** Merkt sich, dass gerade aktualisiert wird (bis Daten kommen, hoechstens 40 s). */
    fun markRefreshing(ctx: Context) = prefs(ctx).edit().putLong("refresh_since", System.currentTimeMillis()).apply()

    fun isRefreshing(ctx: Context): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong("refresh_since", 0) < 40_000

    // ---------------------------------------------------------------- Fruehere Zuege
    /** Fruehere Zuege nur auf Wunsch einblenden (fuer 3 min), damit man nicht versehentlich hineinscrollt. */
    fun showPast(ctx: Context): Boolean = System.currentTimeMillis() < prefs(ctx).getLong("past_until", 0)

    fun togglePast(ctx: Context) =
        prefs(ctx).edit().putLong("past_until", if (showPast(ctx)) 0 else System.currentTimeMillis() + 3 * 60_000).apply()

    fun load(ctx: Context): Snapshot? {
        val s = prefs(ctx).getString("payload", null) ?: return null
        return runCatching { parse(s) }.getOrNull()
    }

    // ---------------------------------------------------------------- Meine Strecken
    private val DEFAULT_FAVS = listOf(Fav(3, 1), Fav(3, 8))       // Häger ↔ Münster Hbf, Häger ↔ Burgsteinfurt

    fun favs(ctx: Context): List<Fav> =
        prefs(ctx).getString("favs", null)?.split(",")?.mapNotNull { Snapshot.parseRelId(it)?.let { (a, b) -> Fav(a, b) } }
            ?.filter { it.a != it.b }?.ifEmpty { null } ?: DEFAULT_FAVS

    fun setFavs(ctx: Context, favs: List<Fav>) {
        prefs(ctx).edit().putString("favs", favs.joinToString(",") { it.hinId }).apply()
        if (favIndex(ctx) >= favs.size) setFavIndex(ctx, 0)
    }

    /** Aktive Strecke (Index in favs). Uebernimmt die alte Auswahl "Münster Hbf | Burgsteinfurt". */
    fun favIndex(ctx: Context): Int {
        val p = prefs(ctx)
        val i = if (p.contains("fav")) p.getInt("fav", 0) else if (p.getString("route", null) == "BST") 1 else 0
        return i.coerceIn(0, favs(ctx).size - 1)
    }

    fun setFavIndex(ctx: Context, i: Int) = prefs(ctx).edit().putInt("fav", i).apply()

    /** Strecke i um eine Position nach oben (-1) oder unten (+1) verschieben; die aktive Strecke bleibt aktiv. */
    fun moveFav(ctx: Context, i: Int, dir: Int): List<Fav> {
        val list = favs(ctx).toMutableList()
        val j = i + dir
        if (i !in list.indices || j !in list.indices) return list
        val active = favIndex(ctx)
        list[i] = list[j].also { list[j] = list[i] }
        setFavs(ctx, list)
        setFavIndex(ctx, when (active) { i -> j; j -> i; else -> active })
        return list
    }

    fun fav(ctx: Context): Fav = favs(ctx)[favIndex(ctx)]

    /** Alle Richtungen aller Strecken: (id, Titel) – fuer Benachrichtigungen. */
    fun directedRels(ctx: Context): List<Pair<String, String>> = favs(ctx).flatMap { f ->
        listOf(f.hinId to "${Line.name(f.a)} → ${Line.name(f.b)}", f.backId to "${Line.name(f.b)} → ${Line.name(f.a)}")
    }.distinctBy { it.first }

    // ---------------------------------------------------------------- Richtung
    fun autoReturn(ctx: Context) = prefs(ctx).getBoolean("auto_return", true)
    fun switchHour(ctx: Context) = prefs(ctx).getInt("switch_hour", 13)
    fun setAutoReturn(ctx: Context, on: Boolean, hour: Int = switchHour(ctx)) =
        prefs(ctx).edit().putBoolean("auto_return", on).putInt("switch_hour", hour).remove("flip_half").apply()

    /** Tageshaelfte als Schluessel: vor dem Wechsel "Hin", danach "Zurueck". */
    private fun halfKey(ctx: Context): String {
        val c = Calendar.getInstance()
        val h = if (c.get(Calendar.HOUR_OF_DAY) < switchHour(ctx)) "A" else "B"
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}-$h"
    }

    /** true = Rueckfahrt (b → a). Automatisch ab der Wechsel-Uhrzeit; ⇅ dreht bis zum naechsten Wechsel um.
     *  Ohne Automatik bleibt die mit ⇅ gewaehlte Richtung, bis man wieder tauscht. */
    fun isReturn(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!autoReturn(ctx)) return p.getBoolean("manual_return", false)
        val auto = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= switchHour(ctx)
        val flipped = p.getString("flip_half", null) == halfKey(ctx)
        return auto xor flipped
    }

    fun toggleDirection(ctx: Context) {
        val p = prefs(ctx)
        if (!autoReturn(ctx)) { p.edit().putBoolean("manual_return", !p.getBoolean("manual_return", false)).apply(); return }
        val k = halfKey(ctx)
        p.edit().putString("flip_half", if (p.getString("flip_half", null) == k) null else k).apply()
    }

    /** (von, nach) als seq der aktiven Strecke in der aktuellen Richtung. */
    fun currentRoute(ctx: Context): Pair<Int, Int> = fav(ctx).let { if (isReturn(ctx)) it.b to it.a else it.a to it.b }

    fun currentRelId(ctx: Context): String = currentRoute(ctx).let { Snapshot.relId(it.first, it.second) }

    fun currentRel(ctx: Context, snap: Snapshot?): Rel? = snap?.let { s -> currentRoute(ctx).let { s.rel(it.first, it.second) } }

    fun relById(snap: Snapshot, id: String): Rel? = Snapshot.parseRelId(id)?.let { (a, b) -> snap.rel(a, b) }

    // ---------------------------------------------------------------- Mein Zug (angeheftet)
    /** Angehefteter Zug (Zugnummer), gilt bis 30 min nach seiner Ankunft. */
    fun pinnedTrain(ctx: Context): String? {
        val p = prefs(ctx)
        return if (System.currentTimeMillis() < p.getLong("pin_until", 0)) p.getString("pin_train", null) else null
    }

    fun pin(ctx: Context, r: Row, relId: String) =
        prefs(ctx).edit().putString("pin_train", r.train).putString("pin_rel", relId)
            .putLong("pin_until", r.arrEpoch + 30 * 60_000L).remove("pin_last_delay").apply()

    fun unpin(ctx: Context) = prefs(ctx).edit().remove("pin_train").remove("pin_until").remove("pin_rel").apply()

    fun pinnedRelId(ctx: Context): String? = prefs(ctx).getString("pin_rel", null)

    /** Dein Zug: auf dem Sperrbildschirm zeigen / bei Aenderung ab X min benachrichtigen. */
    fun pinLock(ctx: Context) = prefs(ctx).getBoolean("pin_lock", true)
    fun pinNotify(ctx: Context) = prefs(ctx).getBoolean("pin_notify", true)
    fun pinThreshold(ctx: Context) = prefs(ctx).getInt("pin_threshold", 2)

    // ---------------------------------------------------------------- Darstellung
    /** Farbschema: "system" | "light" | "dark" (das Widget folgt immer dem System). */
    fun themeMode(ctx: Context): String = prefs(ctx).getString("theme_mode", "system") ?: "system"
    fun setThemeMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("theme_mode", mode).apply()

    fun visuals(ctx: Context): Visuals = Visuals(VISUAL_OPTIONS.associate { (k, _, _) -> k to prefs(ctx).getBoolean("vis_$k", true) })
    fun setVisual(ctx: Context, key: String, on: Boolean) = prefs(ctx).edit().putBoolean("vis_$key", on).apply()
    fun setAllVisuals(ctx: Context, on: Boolean) =
        prefs(ctx).edit().apply { VISUAL_OPTIONS.forEach { (k, _, _) -> putBoolean("vis_$k", on) } }.apply()

    // ---------------------------------------------------------------- Strecke live
    /** Abschnitt der Live-Ansicht (seq von..bis, von < bis). Standard: Münster Hbf – Burgsteinfurt. */
    fun liveRange(ctx: Context): Pair<Int, Int> {
        val p = prefs(ctx)
        val a = p.getInt("live_from", 1); val b = p.getInt("live_to", 8)
        return minOf(a, b) to maxOf(a, b).coerceAtLeast(minOf(a, b) + 1)
    }

    fun setLiveRange(ctx: Context, from: Int, to: Int) =
        prefs(ctx).edit().putInt("live_from", minOf(from, to)).putInt("live_to", maxOf(from, to)).apply()

    /** Bereits abgefahrene Zuege (hoechstens 3). */
    fun pastRows(rel: Rel, now: Long = System.currentTimeMillis()): List<Row> =
        rel.rows.filter { it.past || (it.depEpoch in 1 until now - 60_000) }.takeLast(3)

    /** Liste ab dem naechsten Zug; fruehere nur, wenn eingeblendet. */
    fun visibleRows(rel: Rel, withPast: Boolean, now: Long = System.currentTimeMillis()): List<Row> {
        val past = pastRows(rel, now)
        val next = rel.rows.filter { r -> past.none { it === r } && !(r.past || r.depEpoch in 1 until now - 60_000) }
        return (if (withPast) past else emptyList()) + next
    }

    // ---------------------------------------------------------------- Kommunikation mit der VM
    /** Frische Daten anfordern (ueber Firestore, Antwort kommt per Firebase Cloud Messaging). */
    fun requestRefresh(ctx: Context) = Cloud.requestRefresh(ctx)
}

/** Schaltbare Effekte: (Schluessel, Titel, Erklaerung). Standard: alle an. */
val VISUAL_OPTIONS = listOf(
    Triple("shadows", "Schatten", "Karten und Kopfleiste heben sich leicht ab"),
    Triple("color_header", "Farbige Kopfleiste", "Petrol statt hell"),
    Triple("rel_time", "Abfahrt als „in X min“", "Sonst groß die Uhrzeit"),
    Triple("stop_strip", "Halte-Streifen", "Zugposition und Halte beim nächsten Zug"),
    Triple("source", "Prognose-Kennzeichnung", "„Prognose +7 · DB +2“ bei eigener Prognose"),
    Triple("history", "Bisherige Verspätung", "„meist +5“ an jeder Fahrt"),
    Triple("map_mini", "Minikarte", "Kleine Übersichtskarte mit dem echten Verlauf"),
    Triple("map_true", "Streckentreue Live-Linie", "Abstände und leichter Schwung wie die echte Strecke; aus = gerade Linie mit gleichen Abständen"),
    Triple("animations", "Sanfte Bewegungen", "Züge gleiten bei neuen Daten an ihre Position"),
    Triple("widget_strip", "Schattenstreifen im Widget", "Unter der Kopfleiste des Widgets"),
)

/** Welche Effekte an sind (unbekannte Schluessel = an). */
class Visuals(private val m: Map<String, Boolean> = emptyMap()) {
    fun on(key: String) = m[key] ?: true
    val shadows get() = on("shadows")
    val colorHeader get() = on("color_header")
    val relTime get() = on("rel_time")
    val stopStrip get() = on("stop_strip")
    val source get() = on("source")
    val history get() = on("history")
    val mapMini get() = on("map_mini")
    val mapTrue get() = on("map_true")
    val animations get() = on("animations")
    val widgetStrip get() = on("widget_strip")
    val countOn get() = VISUAL_OPTIONS.count { on(it.first) }
}

/* Gemeinsame Status-Logik fuer App und Widget */
enum class Level { OK, WARN, BAD, OFF }

fun Row.level(): Level = when {
    cancelled -> Level.BAD
    bus -> Level.OFF
    depDelay <= 2 -> Level.OK
    depDelay <= 5 -> Level.WARN
    else -> Level.BAD
}

fun Row.statusShort(): String = when {
    cancelled -> "fällt aus"
    bus -> "Bus"
    depDelay <= 0 -> "pünktlich"
    else -> "+$depDelay"
}

/** "Prognose +7 · DB +2", wenn die eigene Einschaetzung von der DB abweicht. */
fun Row.sourceText(): String? = if (ownForecast) "Prognose +$depDelay · DB +${dbDelay ?: 0}" else null

/** Kurzform fuer Karte/Widget: "meist +5", "fällt oft aus" oder null (zu wenige Tage / unauffaellig). */
fun Row.histShort(): String? {
    val h = hist ?: return null
    return when {
        h.cancelled * 2 >= h.days -> "fällt oft aus (${h.cancelled}/${h.days})"
        h.median >= 1 -> "meist +${h.median}"
        else -> "meist pünktlich"
    }
}

/** Langform fuer das Detailblatt. */
fun Row.histLong(weekday: Boolean): String? {
    val h = hist ?: return null
    val art = if (weekday) "Werktage" else "Wochenendtage"
    val parts = mutableListOf(if (h.median >= 1) "meist +${h.median} min" else "meist pünktlich")
    if (h.late5 > 0) parts += "${h.late5}× ab +5"
    if (h.cancelled > 0) parts += "${h.cancelled}× ausgefallen"
    return "Letzte ${h.days} $art: " + parts.joinToString(" · ")
}

/** "vor 3 min" / "vor 1 h 5 min" */
fun ageText(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "gerade eben"
        m < 60 -> "vor $m min"
        else -> "vor ${m / 60} h ${m % 60} min"
    }
}

/** Wo der Zug vor der Abfahrt steht: "Zug ist noch in X" – oder schon am eigenen Bahnhof. */
fun Row.whereText(): String? = before?.let { if (it == stops.firstOrNull()?.name) "Zug ist schon da" else "Zug ist noch in $it" }
