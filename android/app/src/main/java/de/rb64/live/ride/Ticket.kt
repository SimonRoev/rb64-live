package de.rb64.live.ride

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Sammelbare Fahrkarte: alles wird aus der aufgezeichneten Fahrt abgeleitet (keine Zufallszahlen,
 * gleiche Fahrt = gleiche Karte). Die Karte lebt nur in der Auswertung und im Stempelheft -
 * nichts davon taucht in Abfahrten, Meldungen, Live Update oder der laufenden Aufzeichnung auf.
 */
enum class TicketClass { SECOND, FIRST, SPECIAL }

/** Gebrauchsspuren bei Pechfahrten: +15 Kaffeerand, +30 Eselsohr/Knick, ab +60 zerknuellt. */
enum class Wear { NONE, COFFEE, FOLD, CRUMPLED }

sealed class Beileger {
    data class Stammplatz(val label: String) : Beileger()
    data class Fahrgastrechte(val delay: Int) : Beileger()
    data class Zugbindung(val delay: Int) : Beileger()
    data class Kreuzung(val station: String, val minutes: Int) : Beileger()
    data class Gleiswechsel(val station: String) : Beileger()
    data class Bordbistro(val items: List<String>) : Beileger()
    data class Fundbuero(val item: String, val note: String) : Beileger()
    data class Kontrolle(val station: String) : Beileger()
}

data class Ticket(
    val id: String, val train: String, val from: String, val to: String,
    val dep: String, val arr: String, val depPlan: String, val arrPlan: String?,
    val started: Long, val depDelay: Int?, val delay: Int?,
    val minutes: Int, val km: Double, val vmax: Int?,
    /** Halte zwischen from und to in Fahrtrichtung (inkl. Start und Ziel). */
    val stops: List<String>, val crossing: Pair<String, Int>?,
    val cls: TicketClass, val wear: Wear, val specialReason: String?, val ironed: Boolean,
    val wagen: Int, val platz: Int, val window: Boolean,
    val spruch: String, val agb: String, val beileger: Beileger?,
    val mirrored: Boolean, val serial: String, val nthInMonth: Int, val ordinal: Int,
    val firstRide: Long?, val arrMeasured: Long?, val arrPlanEpoch: Long?, val freeStops: Int,
    /** Letzte fruehere Fahrt mit gleichem Wagen und Platz (Gimmick an der Klassen-Ziffer). */
    val seatSeen: Long?,
    val spruchRarity: Rarity = Rarity.COMMON, val variant: Variant? = null,
    val doodle: Boolean = false, val seatNote: String = "",
    val weather: List<String> = emptyList(), val planMinutes: Int? = null,
) {
    val border get() = stops.contains("Glanerbrug")
    val monthName: String get() = SimpleDateFormat("MMMM", Locale.GERMANY).format(Date(started))
}

object Tickets {
    private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val DM = SimpleDateFormat("ddMM", Locale.GERMANY)
    private val WD = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    private fun prefs(ctx: Context) = de.rb64.live.Repo.prefs(ctx)

    /** Stabiler Wert je Fahrt fuer Spruch, AGB-Zeile und seltene Beileger. */
    private fun seed(id: String) = abs(id.hashCode())

    /** Halte der Linie zwischen a und b in Fahrtrichtung. */
    fun lineBetween(a: String, b: String): List<String> {
        val names = Stations.all.map { it.name }
        val i = names.indexOf(a); val j = names.indexOf(b)
        if (i < 0 || j < 0) return listOf(a, b)
        return if (i <= j) names.subList(i, j + 1) else names.subList(j, i + 1).reversed()
    }

    private fun minuteOfDay(hm: String): Int? = hm.split(":").takeIf { it.size == 2 }
        ?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { mm -> hh * 60 + mm } } }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
    private fun weekday(t: Long) = (cal(t).get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1      // Mo = 1 … So = 7

    /** Feiertage mit Sonderfahrplan (NRW) - Ostersonntag nach Gauss. */
    fun holiday(t: Long): String? {
        val c = cal(t); val y = c.get(Calendar.YEAR); val d = c.get(Calendar.DAY_OF_MONTH); val m = c.get(Calendar.MONTH) + 1
        when (m to d) {
            1 to 1 -> return "Neujahr"; 5 to 1 -> return "Tag der Arbeit"; 10 to 3 -> return "Tag der Deutschen Einheit"
            11 to 1 -> return "Allerheiligen"; 12 to 24 -> return "Heiligabend"; 12 to 25 -> return "1. Weihnachtstag"
            12 to 26 -> return "2. Weihnachtstag"; 12 to 31 -> return "Silvester"
        }
        val a = y % 19; val b = y / 100; val cc = y % 100; val dd = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - dd - g + 15) % 30
        val i = cc / 4; val k = cc % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val mm = (a + 11 * h + 22 * l) / 451
        val em = (h + l - 7 * mm + 114) / 31; val ed = (h + l - 7 * mm + 114) % 31 + 1
        val easter = Calendar.getInstance().apply { clear(); set(y, em - 1, ed) }
        val doy = c.get(Calendar.DAY_OF_YEAR); val e0 = easter.get(Calendar.DAY_OF_YEAR)
        return when (doy - e0) {
            -2 -> "Karfreitag"; 1 -> "Ostermontag"; 39 -> "Christi Himmelfahrt"; 50 -> "Pfingstmontag"; 60 -> "Fronleichnam"
            else -> null
        }
    }

    /** Fahrplanwechsel: zweiter Sonntag im Dezember (00:00). */
    fun timetableChange(year: Int): Long {
        val c = Calendar.getInstance().apply { clear(); set(year, Calendar.DECEMBER, 1) }
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.DAY_OF_MONTH, 7)
        return c.timeInMillis
    }

    private val AGB = listOf(
        "Dieser Fahrschein ist kein Beweis für Pünktlichkeit.",
        "Gefühlte Verspätungen werden nicht erstattet.",
        "Die Aussicht ist im Fahrpreis enthalten.",
        "Oberleitungsstörungen sind auf dieser Strecke ausgeschlossen. Es gibt keine.",
        "Kreuzungen dienen der Begegnung und sind kein Mangel.",
        "Der Gegenzug hat Vorfahrt, wenn er zuerst da ist. Meistens.",
        "Bei Verlust dieser Karte bleibt die Fahrt trotzdem passiert.",
        "Eingleisigkeit ist ein Charakterzug, kein Fehler.",
    )
    private val FUND = listOf(
        "1 Regenschirm, schwarz, leicht verbogen." to "Abzuholen: nie.",
        "Zeit." to "Eigentümer unbekannt.",
        "1 Handschuh (der linke)." to "Der rechte wird noch gesucht.",
        "Gute Laune, kaum benutzt." to "Bitte beim Zugpersonal melden.",
    )

    /** Karte zu einer gespeicherten Fahrt. [history] = alle Fahrten (neueste zuerst). */
    fun of(ctx: Context, s: RideState, history: List<RideMeta>): Ticket {
        val i = s.info
        val seed = seed(i.id)
        val meta = RideStore.meta(s)
        val deps = Analyzer.departures(s.events)
        val depM = deps[i.from] ?: s.events.firstOrNull { it.station == i.from }?.dep
        val arrM = s.events.lastOrNull { it.station == i.to }?.arr
        val delay = meta.arrDelta
        val depDelay = Analyzer.deltaMin(i.planned[i.from] ?: i.depPlanned, depM)
        val stops = lineBetween(i.from, i.to)
        val inner = stops.drop(1).dropLast(1).toSet()
        val crossing = s.events.filter { it.station in inner && (it.seconds ?: 0) >= 120 }
            .maxByOrNull { it.seconds ?: 0 }?.let { it.station!! to (((it.seconds ?: 0) + 30) / 60).toInt() }
        val freeStops = s.events.count { it.station == null && (it.seconds ?: 0) >= 20 }
        val t0 = if (i.depEpochPlanned > 0) i.depEpochPlanned else s.started
        val c = cal(t0)
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val wd = weekday(t0)
        val depMin = minuteOfDay(i.depPlanned) ?: (hour * 60 + c.get(Calendar.MINUTE))
        val minutes = if (depM != null && arrM != null) ((arrM - depM) / 60_000).toInt() else (((s.ended ?: s.started) - s.started) / 60_000).toInt()
        val vmax = Analyzer.maxKmh(s.points)

        // Reihenfolge der Fahrten (aelteste zuerst) fuer Zaehler und Serien
        val older = history.filter { it.started < s.started }.sortedBy { it.started }
        val ordinal = older.size + 1
        val monthStart = cal(s.started).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val nthInMonth = older.count { it.started >= monthStart } + 1
        val firstRide = (older.firstOrNull()?.started ?: s.started)

        // Sonderfahrt: nur wenn der Zug selbst besonders ist
        val year = c.get(Calendar.YEAR)
        val change = timetableChange(year)
        val special = holiday(t0)?.let { "Feiertagsfahrplan · $it" }
            ?: if (s.started >= change && s.started < change + 14 * 86_400_000L && older.none { it.started >= change })
                "Erste Fahrt im neuen Fahrplan" else null

        // Klasse wird beim ersten Ansehen festgelegt (sonst aendert ein spaeterer Rekord alte Karten)
        val p = prefs(ctx)
        val clsKey = "tk_cls_${i.id}"
        val cls = p.getString(clsKey, null)?.let { runCatching { TicketClass.valueOf(it) }.getOrNull() } ?: run {
            val rec = p.getInt("tk_vmax_rec", 0)
            val record = vmax != null && rec > 0 && vmax > rec && older.size >= 3
            val first = (delay != null && delay in -1..0 && crossing == null) || record
            val v = when { special != null -> TicketClass.SPECIAL; first -> TicketClass.FIRST; else -> TicketClass.SECOND }
            p.edit().putString(clsKey, v.name).putInt("tk_vmax_rec", maxOf(rec, vmax ?: 0)).apply()
            v
        }
        val wear = when { (delay ?: 0) >= 60 -> Wear.CRUMPLED; (delay ?: 0) >= 30 -> Wear.FOLD; (delay ?: 0) >= 15 -> Wear.COFFEE; else -> Wear.NONE }
        val ironed = wear == Wear.NONE && older.takeLast(3).let { l -> l.size == 3 && l.all { (it.arrDelta ?: 0) >= 15 } }

        // Ehrenrunde: am selben Tag schon die Gegenrichtung gefahren
        val dayKey = { t: Long -> cal(t).let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
        val mirrored = older.any { dayKey(it.started) == dayKey(s.started) && it.from == i.to && it.to == i.from }

        // Reservierung: Wagen = Wochentag, Platz = Abfahrtsminute + Zwischenhalte, Fenster bei Tageslicht
        val wagen = wd
        val platz = (depMin % 60) + inner.size + 1
        val (rise, set) = de.rb64.live.ui.Sky.sunTimes(t0)
        val window = t0 in rise..set

        // Beileger (hoechstens einer)
        val same = older.count { weekday(it.started) == wd && it.from == i.from && it.to == i.to &&
            abs((cal(it.started).let { k -> k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE) }) - (hour * 60 + c.get(Calendar.MINUTE))) <= 10 }
        val usual = (older.flatMap { listOf(it.from, it.to) }).groupingBy { it }.eachCount()
        val unusual = listOf(i.from, i.to).firstOrNull { older.size >= 5 && (usual[it] ?: 0) == 0 }
        val slowSec = s.points.zipWithNext().sumOf { (a, b) ->
            val v = Analyzer.speedOf(a, b); if (v in 1.5f..8.3f && b.t - a.t < 30_000) (b.t - a.t) / 1000 else 0L }
        val bistro = buildList {
            if (hour < 8) add("Kaffee")
            if (slowSec >= 120) add("Aussicht")
            if ((delay ?: 0) >= 1) add("Geduld")
            if (seed % 4 == 0) add("Keks")
        }
        val control = Extras.control(i.id)
        val beileger: Beileger? = when {
            same >= 4 -> Beileger.Stammplatz("${WD[wd - 1]} · ${i.depPlanned}")
            (delay ?: 0) >= 20 -> Beileger.Fahrgastrechte(delay!!)
            control -> Beileger.Kontrolle(inner.toList().getOrNull(seed % inner.size.coerceAtLeast(1)) ?: i.to)
            (delay ?: 0) >= 5 -> Beileger.Zugbindung(delay!!)
            crossing != null -> Beileger.Kreuzung(crossing.first, crossing.second)
            unusual != null -> Beileger.Gleiswechsel(unusual)
            seed % 7 == 0 -> FUND[seed / 7 % FUND.size].let { Beileger.Fundbuero(it.first, it.second) }
            minutes >= 10 && bistro.isNotEmpty() -> Beileger.Bordbistro(bistro)
            else -> null
        }

        val arrPlan = i.planned[i.to]
        val planMinutes = minuteOfDay(i.planned[i.from] ?: i.depPlanned)?.let { a -> arrPlan?.let { minuteOfDay(it) }?.let { b -> (b - a + 1440) % 1440 } }
        val facts = Facts(
            delay = delay, depDelay = depDelay, crossingMin = crossing?.second ?: 0, hour = hour, weekday = wd,
            border = stops.contains("Glanerbrug"), special = special, holiday = holiday(t0) != null, wear = wear, cls = cls, ironed = ironed,
            weather = i.weather, minutes = minutes, planMinutes = planMinutes, rides = older.size, mirrored = mirrored,
        )
        val line = Extras.line(ctx, i.id, facts)
        val variant = Extras.variant(ctx, i.id, facts)
        val digits = i.train.substringAfterLast(' ').filter { it.isDigit() }.ifBlank { "0" }
        val arrPlanEpoch = arrM?.let { a -> arrPlan?.let { minuteOfDay(it) }?.let { pm ->
            cal(a).apply { set(Calendar.HOUR_OF_DAY, pm / 60); set(Calendar.MINUTE, pm % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis } }
        return Ticket(
            i.id, i.train, i.from, i.to,
            dep = depM?.let { HM.format(Date(it)) } ?: i.depPlanned, arr = arrM?.let { HM.format(Date(it)) } ?: (arrPlan ?: "–"),
            depPlan = i.depPlanned, arrPlan = arrPlan, started = s.started, depDelay = depDelay, delay = delay,
            minutes = minutes, km = meta.km, vmax = vmax, stops = stops, crossing = crossing,
            cls = cls, wear = wear, specialReason = special, ironed = ironed,
            wagen = wagen, platz = platz, window = window, spruch = line.text, agb = AGB[seed % AGB.size], beileger = beileger,
            mirrored = mirrored, serial = "0064-${DM.format(Date(s.started))}-$digits", nthInMonth = nthInMonth, ordinal = ordinal,
            firstRide = firstRide, arrMeasured = arrM, arrPlanEpoch = arrPlanEpoch, freeStops = freeStops,
            seatSeen = older.lastOrNull { m ->
                weekday(m.started) == wagen && (cal(m.started).get(Calendar.MINUTE) + lineBetween(m.from, m.to).size - 1) == platz
            }?.started,
            spruchRarity = line.rarity, variant = variant, weather = i.weather, planMinutes = planMinutes,
            doodle = Extras.doodle(ctx, i.id), seatNote = Extras.seatNote(i.id, facts),
        )
    }

    /* ------------------------------------------------------------ Stempelheft */

    /** Sonderstempel stehen in [Extras.SPECIALS]. */
    val SPECIALS get() = Extras.SPECIALS

    fun specialsOf(t: Ticket): Set<String> = buildSet {
        val c = cal(t.started)
        val hm = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (t.border) add("grenze")
        if (t.arrMeasured != null && t.arrPlanEpoch != null && abs(t.arrMeasured - t.arrPlanEpoch) <= 30_000) add("sekunde")
        if ((t.crossing?.second ?: 0) >= 5) add("kreuzung")
        if (hm < 5 * 60 + 30) add("frueh")
        if (hm >= 22 * 60 + 30) add("spaet")
        if (t.depPlan in setOf("11:11", "22:22", "00:00", "12:12", "13:13", "14:14", "15:15", "16:16", "17:17", "18:18", "19:19", "20:20", "21:21", "23:23", "10:10")) add("schnaps")
        if (t.train.substringAfterLast(' ').filter { it.isDigit() }.let { it.length >= 3 && it.toSet().size == 1 }) add("schnaps")
        if (t.mirrored) add("ehrenrunde")
        if ((t.delay ?: 0) >= 3 && t.crossing == null && t.freeStops == 0) add("museum")
        if (t.wear != Wear.NONE) add("knitter")
        if (t.cls == TicketClass.FIRST) add("erste")
        if (t.cls == TicketClass.SPECIAL) add("sonder")
        if (t.ordinal == 64) add("64")
        if (t.ordinal == 100) add("100")
        if (c.get(Calendar.MONTH) == Calendar.FEBRUARY && c.get(Calendar.DAY_OF_MONTH) == 29) add("schalt")
        if (t.weather.isNotEmpty() && (t.delay ?: 99) <= 5) add("wetterfest")
        if (t.beileger is Beileger.Kontrolle) add("kontrolle")
        if (t.planMinutes != null && t.planMinutes > 0 && (t.delay ?: 0) > t.planMinutes) add("geduld")
    }

    /** Stempelheft: Stempel je Bahnhof, Sonderstempel, gefundene Varianten (id -> Anzahl), Kritzelkarten, Fahrten, Verspaetungsminuten. */
    data class Book(val stations: Map<String, Int>, val specials: Set<String>, val knitter: Int,
                    val variants: Map<String, Int> = emptyMap(), val doodles: Int = 0, val rides: Int = 0, val delayMinutes: Int = 0)

    /** Stempelheft aus allen Fahrten (laedt jede Fahrt einmal - im Hintergrund aufrufen). */
    fun book(ctx: Context, history: List<RideMeta>): Book {
        val counts = mutableMapOf<String, Int>()
        val specials = mutableSetOf<String>()
        var knitter = 0
        val variants = mutableMapOf<String, Int>()
        var doodles = 0
        var delayMin = 0
        history.forEach { m ->
            lineBetween(m.from, m.to).forEach { counts[it] = (counts[it] ?: 0) + 1 }
            RideStore.load(ctx, m.id)?.let { s ->
                val t = of(ctx, s, history)
                val sp = specialsOf(t)
                specials += sp
                if ("knitter" in sp) knitter++
                t.variant?.let { v -> variants[v.name] = (variants[v.name] ?: 0) + 1 }
                if (t.doodle) doodles++
            }
            delayMin += (m.arrDelta ?: 0).coerceIn(0, 60)   // je Fahrt hoechstens 60 min (GeduldsCard)
        }
        return Book(counts, specials, knitter, variants, doodles, history.size, delayMin)
    }
}
