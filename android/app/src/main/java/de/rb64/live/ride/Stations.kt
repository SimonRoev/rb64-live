package de.rb64.live.ride

import android.location.Location

/** Halte Muenster Hbf - Burgsteinfurt (Koordinaten aus OpenStreetMap).
 *  fenceM: Radius fuer das Geofence "du bist am Bahnhof" (0 = kein Geofence). */
data class Station(val name: String, val lat: Double, val lon: Double, val fenceM: Float = 0f) {
    fun distanceTo(lat: Double, lon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(this.lat, this.lon, lat, lon, r)
        return r[0]
    }
}

object Stations {
    /** Ganze Linie (Koordinaten aus OpenStreetMap bzw. config.yaml). fenceM = Radius, wenn der Halt Teil einer Strecke ist. */
    val all = listOf(
        Station("Münster Hbf", 51.95641, 7.63579, fenceM = 250f),     // ausgedehnt
        Station("Zentrum Nord", 51.98418, 7.63846, fenceM = 150f),
        Station("Häger", 52.02180, 7.56279, fenceM = 100f),            // klein: Wohngegend
        Station("Altenberge", 52.05177, 7.48161, fenceM = 150f),
        Station("Nordwalde", 52.07767, 7.46145, fenceM = 150f),
        Station("Borghorst", 52.11852, 7.39851, fenceM = 150f),
        Station("Grottenkamp", 52.12324, 7.37543, fenceM = 150f),
        Station("Burgsteinfurt", 52.14728, 7.32957, fenceM = 150f),
        Station("Metelen Land", 52.1530, 7.2390, fenceM = 150f),
        Station("Ochtrup", 52.2070, 7.1900, fenceM = 150f),
        Station("Gronau", 52.2107, 7.0224, fenceM = 200f),
        Station("Glanerbrug", 52.2150, 6.9720, fenceM = 150f),
        Station("De Eschmarke", 52.2190, 6.9310, fenceM = 150f),
        Station("Enschede", 52.2224, 6.8907, fenceM = 250f),
    )

    fun byName(n: String) = all.firstOrNull { it.name == n }

    /** Naechster Halt im Umkreis (Hbf ist ausgedehnt -> groesserer Radius). */
    fun near(lat: Double, lon: Double): Station? = all
        .map { it to it.distanceTo(lat, lon) }
        .filter { (s, d) -> d <= if (s.name == "Münster Hbf") 450f else 250f }
        .minByOrNull { it.second }?.first

    /** Liegt der Punkt im Bahnkorridor? (Punkte abseits werden nicht gespeichert) */
    fun inCorridor(lat: Double, lon: Double): Boolean {
        // Abstand zur Polyline der Halte, grob: zum naechsten Halt oder zur Verbindung zweier Halte
        for (i in 0 until all.size - 1) {
            if (distToSegment(lat, lon, all[i], all[i + 1]) < 1500.0) return true
        }
        return false
    }

    private fun distToSegment(lat: Double, lon: Double, a: Station, b: Station): Double {
        // lokale Projektion in Meter
        val k = 111_320.0
        val cos = Math.cos(Math.toRadians(lat))
        val px = (lon - a.lon) * k * cos; val py = (lat - a.lat) * k
        val bx = (b.lon - a.lon) * k * cos; val by = (b.lat - a.lat) * k
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return Math.sqrt(dx * dx + dy * dy)
    }
}
