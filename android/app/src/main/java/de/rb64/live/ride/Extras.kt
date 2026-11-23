package de.rb64.live.ride

import android.content.Context
import kotlin.random.Random

/**
 * Erweiterbare Sammlung aller Extras der Fahrkarte: Sprueche, Druckvarianten, Zufalls-Beileger und
 * Sonderstempel. Neue Ideen = neuer Eintrag in einer der Listen unten (id nie wieder aendern oder neu vergeben).
 *
 * Zufall ist "stabil": er haengt an der Fahrt-ID, und was eine Karte einmal bekommen hat, wird gespeichert.
 * So aendern spaetere Eintraege keine schon gesammelten Karten.
 */
enum class Rarity(val label: String, val weight: Int) {
    COMMON("häufig", 100), UNCOMMON("selten", 25), RARE("sehr selten", 6), LEGENDARY("legendär", 1)
}

/** Alles, was eine Bedingung ueber die Fahrt wissen kann. */
data class Facts(
    val delay: Int?, val depDelay: Int?, val crossingMin: Int, val hour: Int, val weekday: Int,
    val border: Boolean, val special: String?, val holiday: Boolean, val wear: Wear, val cls: TicketClass, val ironed: Boolean,
    val weather: List<String>, val minutes: Int, val planMinutes: Int?, val rides: Int, val mirrored: Boolean,
) {
    val late get() = delay ?: 0
    /** Verspaetung laenger als die eigentliche Fahrzeit laut Plan. */
    val longerThanRide get() = planMinutes != null && planMinutes > 0 && late > planMinutes
    val stormy get() = weather.isNotEmpty()
}

/** Ein Spruch. prio: 0 allgemein (nach Verspaetung) … 4 sehr speziell; die hoechste passende Stufe gewinnt. */
data class Line(val id: String, val rarity: Rarity, val prio: Int, val text: String, val cond: (Facts) -> Boolean)

/** Seltene Druckvariante der Karte (reiner Zufall). chance = 1 zu n. */
data class Variant(val id: String, val name: String, val rarity: Rarity, val chance: Int)

/** Sonderstempel im Heft. */
data class Special(val key: String, val name: String, val hint: String, val rarity: Rarity)

object Extras {
    val LINES: List<Line> = listOf(
        // 4 - Pech der besonderen Art
        Line("longer", Rarity.COMMON, 4, "Die Verspätung war heute länger als die Fahrt. Respekt.") { it.longerThanRide },
        Line("longer2", Rarity.UNCOMMON, 4, "Gewartet: länger. Gefahren: kürzer. Erlebt: beides.") { it.longerThanRide },
        // 3 - Sonderfahrt, Gebrauchsspuren
        Line("special", Rarity.COMMON, 3, "Diese Verbindung verkehrt nur gelegentlich.") { it.special != null },
        Line("festive", Rarity.UNCOMMON, 3, "Sie reisen heute außerplanmäßig festlich.") { it.holiday },
        Line("wear", Rarity.COMMON, 3, "Gebrauchsspuren sind im Fahrpreis enthalten.") { it.wear != Wear.NONE },
        Line("wear2", Rarity.COMMON, 3, "Diese Karte hat schon einiges erlebt.") { it.wear != Wear.NONE },
        Line("wear3", Rarity.UNCOMMON, 3, "Bitte nicht bügeln. Wir kümmern uns darum.") { it.wear != Wear.NONE },
        // 2 - Wetter, Klasse, Kreuzung, Grenze
        Line("weather", Rarity.COMMON, 2, "Alle reden vom Wetter. Wir nicht.") { it.stormy && it.late <= 5 },
        Line("weather2", Rarity.UNCOMMON, 2, "Draußen Sturm, drinnen Ruhe. Der Fahrplan bleibt tapfer.") { it.stormy && it.late <= 5 },
        Line("weather3", Rarity.COMMON, 2, "Das Wetter hat heute mitgeredet.") { it.stormy && it.late > 5 },
        Line("first", Rarity.COMMON, 2, "Heute lief der Fahrplan mit Rückenwind.") { it.cls == TicketClass.FIRST },
        Line("first2", Rarity.UNCOMMON, 2, "Erste Klasse. Getränke leider nicht inklusive.") { it.cls == TicketClass.FIRST },
        Line("ironed", Rarity.COMMON, 2, "Frisch gebügelt. Wir haben uns Mühe gegeben.") { it.ironed },
        Line("cross", Rarity.COMMON, 2, "Wartezeit erfolgreich in Gegenverkehr investiert.") { it.crossingMin >= 3 },
        Line("cross2", Rarity.UNCOMMON, 2, "Wir erwarteten den Gegenzug. Er erwartete uns auch.") { it.crossingMin >= 3 },
        Line("border", Rarity.COMMON, 2, "Nächster Halt: internationaler Alltag.") { it.border },
        Line("border2", Rarity.RARE, 2, "Sänk ju for träwelling.") { it.border },
        // 1 - Tageszeit, Wochentag, Gewohnheit
        Line("early", Rarity.COMMON, 1, "Der Tag wurde ordnungsgemäß früh begonnen.") { it.hour < 6 },
        Line("late", Rarity.COMMON, 1, "Endstation erreicht. Der Rest ist Schlaf.") { it.hour >= 22 },
        Line("friday", Rarity.COMMON, 1, "Erhöhtes Reisewunschaufkommen festgestellt.") { it.weekday == 5 && it.hour >= 14 },
        Line("sunday", Rarity.COMMON, 1, "Sie fahren zurück in die Woche. Wir bedauern.") { it.weekday == 7 && it.hour >= 17 },
        Line("monday", Rarity.UNCOMMON, 1, "Montag. Wir bitten um Ihr Verständnis.") { it.weekday == 1 && it.hour < 10 },
        Line("regular", Rarity.UNCOMMON, 1, "Schon wieder Sie. Wir freuen uns jedes Mal.") { it.rides >= 20 },
        Line("mirror", Rarity.COMMON, 1, "Hin und zurück. Wir bedanken uns für die Treue.") { it.mirrored },
        // 0 - nach Verspaetung
        Line("nodata", Rarity.COMMON, 0, "Ankunft nicht gemessen. Wir gehen vom Besten aus.") { it.delay == null },
        Line("ontime", Rarity.COMMON, 0, "Wir bitten um Ihr Staunen. Heute pünktlich.") { it.delay != null && it.late <= 0 },
        Line("ontime2", Rarity.COMMON, 0, "Der Fahrplan hat heute recht behalten.") { it.delay != null && it.late <= 0 },
        Line("ontime3", Rarity.UNCOMMON, 0, "Pünktlich. Bitte nicht daran gewöhnen.") { it.delay != null && it.late <= 0 },
        Line("d2", Rarity.COMMON, 0, "Geringfügige Abweichung vom Idealzustand.") { it.late in 1..2 },
        Line("d2b", Rarity.UNCOMMON, 0, "Zwei Minuten sind keine Verspätung, sondern Charakter.") { it.late in 1..2 },
        Line("d5", Rarity.COMMON, 0, "Verzögerung im Rahmen des Üblichen. Wir bitten um Gelassenheit.") { it.late in 3..5 },
        Line("d5b", Rarity.UNCOMMON, 0, "Laut Bahn-Statistik pünktlich. Laut Ihnen nicht.") { it.late in 3..5 },
        Line("d14", Rarity.COMMON, 0, "Sie haben die Aussicht heute besonders lange genießen dürfen.") { it.late >= 6 },
        Line("d14b", Rarity.RARE, 0, "Grund: Verzögerung im Betriebsablauf. Seit 2021 außer Dienst, heute aushilfsweise.") { it.late >= 6 },
        // seltene, die immer mitspielen
        Line("clock", Rarity.RARE, 0, "Heute sprach die Bahnhofsuhr: klack.") { true },
        Line("keep", Rarity.LEGENDARY, 0, "Dieser Spruch ist selten. Bitte gut aufbewahren.") { true },
        Line("keks", Rarity.LEGENDARY, 0, "Rund, rot, seit 1955. Sie wissen schon.") { true },
    )

    val VARIANTS = listOf(
        Variant("misprint", "Fehldruck", Rarity.LEGENDARY, 150),
        Variant("double", "Doppelstempel", Rarity.RARE, 40),
    )

    /** Zufalls-Beileger: Fahrscheinkontrolle (1 zu n). */
    const val CONTROL_CHANCE = 25

    val SPECIALS = listOf(
        Special("grenze", "Grenzgänger", "über Glanerbrug gefahren", Rarity.COMMON),
        Special("frueh", "Frühschicht", "vor 5:30 los", Rarity.COMMON),
        Special("ehrenrunde", "Ehrenrunde", "hin und zurück an einem Tag", Rarity.COMMON),
        Special("knitter", "Knitterkarte", "Pechfahrt", Rarity.COMMON),
        Special("spaet", "Spätschicht", "nach 22:30 los", Rarity.UNCOMMON),
        Special("kreuzung", "Kreuzungsmeister", "5 min Kreuzung", Rarity.UNCOMMON),
        Special("erste", "Erste Klasse", "pünktlich, keine Kreuzung", Rarity.UNCOMMON),
        Special("museum", "Museumsstück", "Verspätung ohne erkennbaren Grund", Rarity.UNCOMMON),
        Special("wetterfest", "Wetterfest", "Wetterwarnung und trotzdem pünktlich", Rarity.UNCOMMON),
        Special("kontrolle", "Kontrolliert", "Fahrscheinkontrolle auf der Rückseite", Rarity.UNCOMMON),
        Special("sekunde", "Auf die Sekunde", "Ankunft ±30 s", Rarity.RARE),
        Special("geduld", "Geduldsprobe", "länger gewartet als gefahren", Rarity.RARE),
        Special("schnaps", "Schnapszahl", "11:11, 22:22 …", Rarity.RARE),
        Special("sonder", "Sonderfahrt", "Feiertag, Fahrplanwechsel", Rarity.RARE),
        Special("doppel", "Doppelstempel", "Druckvariante", Rarity.RARE),
        Special("64", "64. Fahrt", "Linienkenntnis bestätigt", Rarity.RARE),
        Special("100", "100. Fahrt", "Jubiläum", Rarity.LEGENDARY),
        Special("schalt", "Schaltjahr", "Fahrt am 29.2.", Rarity.LEGENDARY),
        Special("fehldruck", "Fehldruck", "Druckvariante", Rarity.LEGENDARY),
    )

    private fun prefs(ctx: Context) = de.rb64.live.Repo.prefs(ctx)
    private fun rnd(id: String, salt: String) = Random((id + salt).hashCode().toLong())

    private fun <T> weighted(r: Random, items: List<T>, w: (T) -> Int): T? {
        val total = items.sumOf(w); if (total <= 0) return null
        var x = r.nextInt(total)
        for (i in items) { x -= w(i); if (x < 0) return i }
        return items.lastOrNull()
    }

    /** Spruch fuer eine Fahrt (einmal gewaehlt, dann gespeichert). */
    fun line(ctx: Context, rideId: String, f: Facts): Line {
        val key = "tk_sp_$rideId"
        prefs(ctx).getString(key, null)?.let { id -> LINES.firstOrNull { it.id == id }?.let { return it } }
        val match = LINES.filter { it.cond(f) }
        val top = match.filter { it.rarity < Rarity.RARE }.maxOfOrNull { it.prio } ?: 0
        val pool = match.filter { it.prio == top || it.rarity >= Rarity.RARE }
        val pick = weighted(rnd(rideId, "line"), pool) { it.rarity.weight } ?: LINES.first { it.id == "nodata" }
        prefs(ctx).edit().putString(key, pick.id).apply()
        found(ctx, pick.id)
        return pick
    }

    /** Druckvariante (oder null), einmal gewuerfelt, dann gespeichert ("-" = keine). */
    fun variant(ctx: Context, rideId: String): Variant? {
        val key = "tk_var_$rideId"
        prefs(ctx).getString(key, null)?.let { id -> return VARIANTS.firstOrNull { it.id == id } }
        val r = rnd(rideId, "variant")
        val v = VARIANTS.firstOrNull { r.nextInt(it.chance) == 0 }
        prefs(ctx).edit().putString(key, v?.id ?: "-").apply()
        return v
    }

    fun control(rideId: String) = rnd(rideId, "control").nextInt(CONTROL_CHANCE) == 0

    /** Gefundene Sprueche merken (fuer die Spruchsammlung im Heft). */
    private fun found(ctx: Context, id: String) {
        val s = prefs(ctx).getStringSet("tk_found", emptySet())!!
        if (id !in s) prefs(ctx).edit().putStringSet("tk_found", s + id).apply()
    }

    fun foundLines(ctx: Context): List<Line> {
        val s = prefs(ctx).getStringSet("tk_found", emptySet())!!
        return LINES.filter { it.id in s }
    }
}
