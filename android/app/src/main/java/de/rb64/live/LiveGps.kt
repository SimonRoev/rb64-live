package de.rb64.live

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import de.rb64.live.ride.Stations
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live-Position teilen (freiwillig, in den Einstellungen):
 * Waehrend "Ich fahre mit" schickt das Handy alle 30 s nur "Zug X ist bei Streckenposition Y, Z km/h" nach
 * Firestore live/{uid} - keine Koordinaten. Die VM leitet die Positionen an alle weiter, die selbst teilen
 * ("wer teilt, sieht"). Am Fahrtende wird das Dokument geloescht.
 */
object LiveGps {
    private const val TAG = "RB64Live"
    private const val SEND_MS = 30_000L
    private const val FRESH_MS = 90_000L
    private var lastSent = 0L

    fun enabled(ctx: Context) = Repo.prefs(ctx).getBoolean("live_share", false)

    /** Einstellung setzen; die VM erfaehrt es ueber devices/{uid}.liveShare. */
    fun setEnabled(ctx: Context, on: Boolean) {
        Repo.prefs(ctx).edit().putBoolean("live_share", on).apply()
        if (!on) { stop(); Repo.prefs(ctx).edit().remove("live_gps").apply() }
        Cloud.setLiveShare(ctx, on)
    }

    /** Streckenposition als seq (1 = Muenster Hbf ... 14 = Enschede), aus der Lage zwischen zwei Halten. */
    fun seqAt(lat: Double, lon: Double): Double? {
        val st = Stations.all
        var best: Pair<Double, Double>? = null      // (Abstand m, seq)
        for (i in 0 until st.size - 1) {
            val a = st[i]; val b = st[i + 1]
            val k = 111_320.0; val cos = Math.cos(Math.toRadians(lat))
            val px = (lon - a.lon) * k * cos; val py = (lat - a.lat) * k
            val bx = (b.lon - a.lon) * k * cos; val by = (b.lat - a.lat) * k
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val d = Math.hypot(px - t * bx, py - t * by)
            if (best == null || d < best.first) best = d to (i + 1 + t)
        }
        return best?.takeIf { it.first < 1500 }?.second
    }

    /** Aus dem Fahrt-Dienst bei jeder neuen Position aufrufen (nur unterwegs); sendet hoechstens alle 30 s.
     *  Position auf 0,05 Halte gerundet, kein Tempo - mehr braucht die Karte nicht. */
    fun onLocation(ctx: Context, train: String, lat: Double, lon: Double) {
        if (!enabled(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastSent < SEND_MS) return
        val seq = seqAt(lat, lon) ?: return
        main.removeCallbacks(pendingDelete)         // es wird wieder gesendet: kein spaetes Loeschen
        lastSent = now
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("live").document(uid).set(mapOf(
            "train" to train, "pos" to Math.round(seq * 20) / 20.0,
            "ts" to FieldValue.serverTimestamp(),
        )).addOnFailureListener { Log.w(TAG, "Live-Position nicht gesendet", it) }
    }

    /** Fahrt vorbei (oder Teilen aus): Dokument loeschen. */
    fun stop() {
        lastSent = 0L
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        // Die Regeln erlauben das Loeschen erst 20 s nach der letzten Meldung (Schutz der Sendebremse) - deshalb
        // 25 s warten; sendet bis dahin wieder jemand (neue Fahrt), faellt das Loeschen aus
        main.removeCallbacks(pendingDelete)
        pendingDelete = Runnable {
            FirebaseFirestore.getInstance().collection("live").document(uid).delete()
                .addOnFailureListener { Log.w(TAG, "Live-Position nicht geloescht (die VM raeumt sie nach 15 min weg)", it) }
        }
        main.postDelayed(pendingDelete, 25_000L)
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDelete = Runnable {}

    /** Push "live" von der VM: [[Zug, seq, km/h, Alter s], ...]; gespeichert (loest Neuzeichnen aus). */
    fun onPush(ctx: Context, json: String?) {
        if (!enabled(ctx) || json == null) return
        Repo.prefs(ctx).edit().putString("live_gps", JSONObject().put("at", System.currentTimeMillis())
            .put("l", JSONArray(json)).toString()).apply()
    }

    data class Pos(val seq: Double, val kmh: Int, val at: Long)

    /** Frische Live-Positionen je Zug (nur, wenn man selbst teilt). */
    fun positions(ctx: Context, now: Long = System.currentTimeMillis()): Map<String, Pos> {
        if (!enabled(ctx)) return emptyMap()
        val o = runCatching { JSONObject(Repo.prefs(ctx).getString("live_gps", null) ?: return emptyMap()) }.getOrNull()
            ?: return emptyMap()
        val at = o.optLong("at"); val a = o.optJSONArray("l") ?: return emptyMap()
        return (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONArray(i) ?: return@mapNotNull null
            val t = at - e.optInt(3) * 1000L
            if (now - t > FRESH_MS) null else e.optString(0) to Pos(e.optDouble(1), e.optInt(2), t)
        }.toMap()
    }
}

/** Snapshot mit Live-Positionen: Zugposition aus GPS statt aus Fahrplan/Echtzeit, markiert als gps. */
fun Snapshot.withLiveGps(ctx: Context): Snapshot {
    val live = LiveGps.positions(ctx).mapValues { it.value.seq }.toMutableMap()
    // eigene Fahrt: immer die eigene GPS-Position zeigen, bis zum Ziel (auch ohne Teilen - nur auf diesem Handy)
    de.rb64.live.ride.RideStore.state.value?.let { s ->
        val p = s.points.lastOrNull()
        if (p != null && System.currentTimeMillis() - p.t < 60_000L && de.rb64.live.ride.RideService.isDeparted(s))
            LiveGps.seqAt(p.lat, p.lon)?.let { live[s.info.train] = it }
    }
    if (live.isEmpty()) return this
    return copy(trips = trips.map { t -> live[t.train]?.let { t.copy(pos = it, gps = true) } ?: t })
}
