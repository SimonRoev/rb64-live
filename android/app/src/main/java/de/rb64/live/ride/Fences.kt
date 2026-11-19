package de.rb64.live.ride

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import de.rb64.live.Notifier
import de.rb64.live.R
import de.rb64.live.Repo
import de.rb64.live.Settings
import de.rb64.live.statusShort
import java.util.Calendar

/**
 * Geofences um Haeger (100 m), Muenster Hbf (250 m) und Burgsteinfurt (150 m).
 * Bist du ~1 min dort, schlaegt eine leise Benachrichtigung "Ich fahre mit" fuer den
 * naechsten Zug vor. Es wird nichts aufgezeichnet, bevor du tippst; der Standort wird nicht gespeichert.
 */
object Fences {
    private const val TAG = "RB64"

    /** Welche Richtungen meiner Strecken an diesem Bahnhof beginnen: erst Hinfahrten, dann Rueckfahrten. */
    private fun relsAt(ctx: Context, station: String): Pair<List<String>, List<String>> {
        val seq = de.rb64.live.Line.seqOf(station) ?: return emptyList<String>() to emptyList()
        val favs = Repo.favs(ctx)
        return favs.filter { it.a == seq }.map { it.hinId } to favs.filter { it.b == seq }.map { it.backId }
    }

    /** Bahnhoefe mit Geofence: beide Enden jeder Strecke. */
    private fun fenceStations(ctx: Context) = Repo.favs(ctx).flatMap { listOf(it.a, it.b) }.toSet()
        .mapNotNull { Stations.byName(de.rb64.live.Line.name(it)) }

    fun enabled(ctx: Context) = Repo.prefs(ctx).getBoolean("fence_enabled", false)

    fun hasPermissions(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, FenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,   // Geofence-Intents muessen veraenderbar sein
    )

    /** Geofences (neu) anlegen bzw. entfernen, je nach Einstellung und Berechtigung. */
    fun sync(ctx: Context) {
        val client = LocationServices.getGeofencingClient(ctx)
        if (!enabled(ctx) || !hasPermissions(ctx)) { client.removeGeofences(pending(ctx)); return }
        val fences = fenceStations(ctx).filter { it.fenceM > 0f }.map {
            Geofence.Builder().setRequestId(it.name)
                .setCircularRegion(it.lat, it.lon, it.fenceM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(60_000)
                .build()
        }
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
        try {
            client.addGeofences(req, pending(ctx))
                .addOnSuccessListener { Log.i(TAG, "Geofences aktiv: ${fences.size}") }
                .addOnFailureListener { Log.w(TAG, "Geofences nicht gesetzt", it) }
        } catch (e: SecurityException) { Log.w(TAG, "Keine Standortberechtigung", e) }
    }

    /** So lange nach der erwarteten Abfahrt gilt ein Zug im Vorschlag noch als "steht vielleicht noch da". */
    private const val GRACE_MS = 6 * 60_000L

    /** Am Bahnhof angekommen: ggf. Vorschlag zeigen. */
    fun onDwell(ctx: Context, station: String) {
        if (!enabled(ctx) || !Notifier.permitted(ctx)) return
        RideStore.init(ctx)
        if (RideStore.state.value != null) return                                   // faehrt schon
        val p = Repo.prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("ride_ended", 0) < 15 * 60_000) return                  // gerade mit dem Zug angekommen
        if (now - p.getLong("prompt_$station", 0) < 45 * 60_000) return             // schon vorgeschlagen
        val s = Settings.load(ctx)
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (s.weekdaysOnly && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) return
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val snap = Repo.load(ctx) ?: return
        val (hin, back) = relsAt(ctx, station)
        val ids = if (cal.get(Calendar.HOUR_OF_DAY) >= Repo.switchHour(ctx)) back + hin else hin + back
        if (ids.isEmpty()) return
        // Ein verspaeteter Zug kann noch am Bahnsteig stehen, obwohl die letzten Daten seine Abfahrt schon vorbei
        // sehen (die Verspaetung waechst schneller, als Daten kommen) -> erst einige Minuten danach als weg werten.
        fun stillHere(r: de.rb64.live.Row) = !r.cancelled && !r.bus && r.depEpoch > now - GRACE_MS

        // angehefteter Zug faehrt hier ab -> nur ihn vorschlagen (keine Auswahl verschiedener Zuege)
        val pin = Repo.pinnedTrain(ctx)
        val pinned = pin?.let { t ->
            ids.firstNotNullOfOrNull { id ->
                Repo.relById(snap, id)?.let { rel -> rel.rows.firstOrNull { it.train == t && stillHere(it) }?.let { rel to it } }
            }
        }
        // sonst: naechster Zug je Strecke, nur Strecken mit passendem Zeitfenster (+/- 30 min Spielraum)
        val options = if (pinned != null) listOf(pinned) else ids.mapNotNull { id ->
            val win = s.windows[id]
            if (win != null && (minute < win.from - 30 || minute > win.to + 30)) return@mapNotNull null
            val rel = Repo.relById(snap, id) ?: return@mapNotNull null
            val row = rel.rows.firstOrNull { stillHere(it) } ?: return@mapNotNull null
            rel to row
        }
        if (options.isEmpty()) return
        p.edit().putLong("prompt_$station", now).apply()

        val (rel0, row0) = options.first()
        val b = Notification.Builder(ctx, RideService.CH_PROMPT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (pinned != null) "Dein Zug: ${row0.depExpected} → ${rel0.to} (${row0.statusShort()})" +
                    (row0.platform?.let { " · Gleis $it" } ?: "")
                else "$station: nächster Zug ${row0.depExpected} → ${rel0.to} (${row0.statusShort()})")
            .setContentText("Mitfahren und Fahrt aufzeichnen? „Los“ geht auch ohne Entsperren.")
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setTimeoutAfter(maxOf(5 * 60_000L, row0.depEpoch - now + 5 * 60_000L))
        options.take(3).forEachIndexed { k, (rel, row) ->
            val info = RideService.infoFrom(rel, row)
            val pi = PendingIntent.getForegroundService(ctx, 100 + k, RideService.startIntent(ctx, info),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val label = if (options.size == 1) "▶ Los, ich fahre mit" else "▶ Los: ${row.depExpected} → ${rel.to}"
            b.addAction(Notification.Action.Builder(null, label, pi).setAuthenticationRequired(false).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(RideService.PROMPT_NOTIF_ID, b.build())
    }
}

class FenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError() || ev.geofenceTransition != Geofence.GEOFENCE_TRANSITION_DWELL) return
        ev.triggeringGeofences?.firstOrNull()?.requestId?.let { Fences.onDwell(ctx, it) }
    }
}

/** Geofences ueberleben keinen Neustart -> danach neu anlegen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Fences.sync(ctx)
    }
}
