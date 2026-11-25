package de.rb64.live

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Rueckkanal Handy -> VM ueber Firestore (die VM ist von aussen nicht erreichbar,
 * horcht aber ueber eine ausgehende Verbindung auf die Sammlung "devices").
 *
 *   devices/{uid} = { token, updated, refreshAt }
 *
 * uid = anonyme Firebase-Anmeldung; die Sicherheitsregeln erlauben nur das Schreiben des eigenen Dokuments.
 */
object Cloud {
    private const val TAG = "RB64"

    private fun withUser(block: (String) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val u = auth.currentUser
        if (u != null) { block(u.uid); return }
        auth.signInAnonymously()
            .addOnSuccessListener { r -> r.user?.uid?.let(block) }
            .addOnFailureListener { Log.w(TAG, "Anonyme Anmeldung fehlgeschlagen", it) }
    }

    /** Fuer Lesezugriffe auf oeffentliche Dokumente (Firestore-Regel verlangt eine Anmeldung). */
    fun withUserPublic(block: () -> Unit) = withUser { block() }

    private fun doc(uid: String) = FirebaseFirestore.getInstance().collection("devices").document(uid)

    /** Firebase-Token bei der VM anmelden (nur wenn neu). */
    fun registerToken(ctx: Context, force: Boolean = false) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!force && Repo.prefs(ctx).getString("token_sent", null) == token) return@addOnSuccessListener
            withUser { uid ->
                doc(uid).set(mapOf("token" to token, "updated" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .addOnSuccessListener { Repo.prefs(ctx).edit().putString("token_sent", token).apply() }
                    .addOnFailureListener { Log.w(TAG, "Token nicht gespeichert", it) }
            }
        }
    }

    // ---------------------------------------------------------------- Fahrten hochladen / loeschen
    /** Warteschlange aller noch nicht bestaetigt hochgeladenen Fahrten (nicht nur der letzten). */
    private const val QUEUE = "ride_upload_queue"
    private const val DEL_QUEUE = "ride_delete_queue"
    fun queueUpload(ctx: Context, id: String) {
        val p = Repo.prefs(ctx)
        p.edit().putStringSet(QUEUE, (p.getStringSet(QUEUE, emptySet()).orEmpty() + id)).apply()
    }
    private fun unqueue(ctx: Context, id: String) {
        val p = Repo.prefs(ctx)
        p.edit().putStringSet(QUEUE, p.getStringSet(QUEUE, emptySet()).orEmpty() - id).remove("ride_upload_pending").apply()
    }
    fun uploadPending(ctx: Context, id: String): Boolean =
        id in Repo.prefs(ctx).getStringSet(QUEUE, emptySet()).orEmpty() || Repo.prefs(ctx).getString("ride_upload_pending", null) == id

    /**
     * Loeschschluessel je Fahrt: liegt in der Fahrt-Kopie auf dem Server (nur die VM kann ihn lesen) und lokal
     * (auch in der Sicherung). Damit klappt "Ueberall loeschen" auch nach einem Handywechsel mit neuer Kennung.
     */
    fun delKey(ctx: Context, id: String): String {
        val p = Repo.prefs(ctx)
        return p.getString("rk_$id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("rk_$id", it).apply() }
    }

    /**
     * Fuer den Upload nur die eigentliche Zugfahrt: Abfahrt am Einstieg - 1 min bis Ankunft am Ziel + 1 min.
     * Der Weg zum Bahnhof (der Korridor ist 1,5 km breit) bleibt so auf dem Handy.
     */
    fun forUpload(s: de.rb64.live.ride.RideState): de.rb64.live.ride.RideState {
        val ev = s.events
        val dep = de.rb64.live.ride.Analyzer.departures(ev)[s.info.from] ?: ev.firstOrNull { it.station == s.info.from }?.dep
        // Ankunft am Ziel, sonst der letzte erkannte Bahnhof unterwegs - nie das Ende der Aufzeichnung (Weg nach Hause)
        val arr = ev.lastOrNull { it.station == s.info.to }?.arr
            ?: ev.lastOrNull { it.station != null && it.station != s.info.from }?.let { it.dep ?: it.arr }
        if (dep == null || arr == null || arr <= dep) {
            // Zugfahrt nicht sicher abgrenzbar: keine GPS-Punkte hochladen, nur Halte an Bahnhoefen und Markierungen ohne Ort
            return s.copy(points = emptyList(), tags = s.tags.map { it.copy(lat = null, lon = null) },
                events = ev.filter { it.station != null })
        }
        val a = dep - 60_000L; val b = arr + 60_000L
        return s.copy(points = s.points.filter { it.t in a..b }, tags = s.tags.filter { it.t in a..b },
            events = ev.filter { (it.dep ?: it.arr) >= a && it.arr <= b })
    }

    /** Aufgezeichnete Fahrt hochladen: rides/{rideId} (Zugfahrt als JSON-Text, siehe [forUpload]). */
    fun uploadRide(ctx: Context, ride: de.rb64.live.ride.RideState, onDone: (() -> Unit)? = null) {
        queueUpload(ctx, ride.info.id)
        withUser { uid ->
            val i = ride.info
            val up = forUpload(ride)
            FirebaseFirestore.getInstance().collection("rides").document(i.id).set(mapOf(
                "uid" to uid, "train" to i.train, "relId" to i.relId, "from" to i.from, "to" to i.to,
                "depPlanned" to i.depPlanned, "started" to up.started, "ended" to up.ended,
                "points" to up.points.size, "deleted" to false,
                "data" to de.rb64.live.ride.RideStore.toJson(up).toString(),
                "delKey" to delKey(ctx, i.id),
                "uploaded" to FieldValue.serverTimestamp(),
            )).addOnSuccessListener {
                Log.i(TAG, "Fahrt hochgeladen (${up.points.size} Punkte)")
                unqueue(ctx, i.id)
                onDone?.invoke()
            }.addOnFailureListener {
                // abgelehnt (Regeln): Wiederholen hilft nicht - sonst beim naechsten Start erneut versuchen
                if ((it as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) unqueue(ctx, i.id)
                Log.w(TAG, "Fahrt nicht hochgeladen", it); onDone?.invoke()
            }
        }
    }

    /** Alle noch nicht hochgeladenen Fahrten erneut senden (z. B. ohne Netz oder vor der ersten Anmeldung). */
    fun retryPendingRide(ctx: Context) {
        val p = Repo.prefs(ctx)
        p.getStringSet(DEL_QUEUE, emptySet()).orEmpty().forEach { deleteRide(ctx, it) }
        val ids = p.getStringSet(QUEUE, emptySet()).orEmpty() + listOfNotNull(p.getString("ride_upload_pending", null))
        if (ids.isEmpty()) return
        de.rb64.live.ride.RideStore.init(ctx)
        ids.forEach { id ->
            val ride = de.rb64.live.ride.RideStore.load(ctx, id)
            if (ride != null) uploadRide(ctx, ride) else unqueue(ctx, id)
        }
    }

    /**
     * Fahrt auf dem Server loeschen (die VM entfernt dann Messpunkte und Kennung). Mit Loeschschluessel geht das
     * auch von einem anderen Geraet aus; [onResult] meldet, ob es geklappt hat.
     */
    fun deleteRide(ctx: Context, id: String, onResult: (Boolean) -> Unit = {}) {
        // Noch nicht bestaetigt hochgeladen: aus der Warteschlange nehmen UND trotzdem "geloescht" senden - ein im
        // Firebase-SDK gepuffertes Hochladen (offline) wuerde sonst spaeter doch ankommen. Die Schreibreihenfolge ist
        // garantiert; gibt es die Fahrt oben gar nicht, zaehlt "nicht gefunden" als erledigt.
        if (uploadPending(ctx, id)) unqueue(ctx, id)
        val key = Repo.prefs(ctx).getString("rk_$id", null)
        withUser { _ ->
            val upd = mutableMapOf<String, Any>("deleted" to true, "uploaded" to FieldValue.serverTimestamp())
            if (key != null) upd["delProof"] = key
            FirebaseFirestore.getInstance().collection("rides").document(id).update(upd)
                .addOnSuccessListener {
                    val p = Repo.prefs(ctx)
                    p.edit().remove("rk_$id").putStringSet(DEL_QUEUE, p.getStringSet(DEL_QUEUE, emptySet()).orEmpty() - id).apply()
                    onResult(true)
                }
                .addOnFailureListener {
                    // z. B. ohne Netz: merken und beim naechsten Start erneut versuchen
                    val p = Repo.prefs(ctx)
                    val code = (it as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                    val missing = code == com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND
                    // abgelehnt (alte Fahrt ohne Schluessel von einem anderen Geraet) oder nie oben: Wiederholen hilft nicht
                    val final = missing || code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                    if (!missing) Log.w(TAG, "Loeschen fehlgeschlagen", it)
                    p.edit().putStringSet(DEL_QUEUE, p.getStringSet(DEL_QUEUE, emptySet()).orEmpty().let { q -> if (final) q - id else q + id }).apply()
                    onResult(missing)
                }
        }
    }

    /**
     * Feedback anonym senden: feedback/{auto} = { uid, text, version?, device?, context?, created }.
     * onDone(true) wenn gespeichert. Die VM holt es ab und meldet es dem Entwickler per Push.
     */
    fun sendFeedback(ctx: Context, text: String, withDevice: Boolean, context: String?, onDone: (Boolean) -> Unit) {
        withUser { uid ->
            val data = mutableMapOf<String, Any>("uid" to uid, "text" to text.take(4000), "created" to FieldValue.serverTimestamp())
            if (withDevice) {
                data["version"] = BuildConfig.VERSION_NAME
                data["device"] = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
            }
            context?.let { data["context"] = it }
            FirebaseFirestore.getInstance().collection("feedback").add(data)
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { Log.w(TAG, "Feedback nicht gesendet", it); onDone(false) }
        }
    }

    /** Frische Daten anfordern - die VM antwortet per Firebase Cloud Messaging. */
    /** Live-Position teilen an/aus: die VM schickt Live-Positionen nur an Geraete mit liveShare = true. */
    fun setLiveShare(ctx: Context, on: Boolean) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            withUser { uid ->
                doc(uid).set(mapOf("token" to token, "liveShare" to on, "updated" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .addOnFailureListener { Log.w(TAG, "Live-Einstellung nicht gespeichert", it) }
            }
        }
    }

    /** Bis wann ein Zug angeheftet ist (0 = keiner). Dann schickt die VM neue Daten mit hoher Prioritaet,
     *  damit Meldungen auch bei gesperrtem Handy sofort kommen. Welcher Zug, erfaehrt der Server nicht. */
    fun setPinUntil(ctx: Context, until: Long) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            withUser { uid ->
                doc(uid).set(mapOf("token" to token, "pinUntil" to until, "updated" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .addOnFailureListener { Log.w(TAG, "Anheften nicht gemeldet", it) }
            }
        }
    }

    fun requestRefresh(ctx: Context) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            withUser { uid ->
                doc(uid).set(mapOf("token" to token, "refreshAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .addOnFailureListener { Log.w(TAG, "Aktualisieren-Anfrage fehlgeschlagen", it) }
            }
        }
    }
}
