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

    /** Aufgezeichnete Fahrt hochladen: rides/{rideId} (komplette Fahrt als JSON-Text). */
    fun uploadRide(ctx: Context, ride: de.rb64.live.ride.RideState) {
        withUser { uid ->
            val i = ride.info
            FirebaseFirestore.getInstance().collection("rides").document(i.id).set(mapOf(
                "uid" to uid, "train" to i.train, "relId" to i.relId, "from" to i.from, "to" to i.to,
                "depPlanned" to i.depPlanned, "started" to ride.started, "ended" to ride.ended,
                "points" to ride.points.size, "deleted" to false,
                "data" to de.rb64.live.ride.RideStore.toJson(ride).toString(),
                "uploaded" to FieldValue.serverTimestamp(),
            )).addOnSuccessListener {
                Log.i(TAG, "Fahrt hochgeladen (${ride.points.size} Punkte)")
                val p = Repo.prefs(ctx)
                if (p.getString("ride_upload_pending", null) == i.id) p.edit().remove("ride_upload_pending").apply()
            }.addOnFailureListener { Log.w(TAG, "Fahrt nicht hochgeladen (wird beim nächsten Start erneut versucht)", it) }
        }
    }

    /** Noch nicht hochgeladene letzte Fahrt erneut senden (z. B. nach fehlender Firestore-Regel oder ohne Netz). */
    fun retryPendingRide(ctx: Context) {
        val id = Repo.prefs(ctx).getString("ride_upload_pending", null) ?: return
        de.rb64.live.ride.RideStore.init(ctx)
        val ride = de.rb64.live.ride.RideStore.load(ctx, id)
        if (ride != null) uploadRide(ctx, ride) else Repo.prefs(ctx).edit().remove("ride_upload_pending").apply()
    }

    /** Fahrt als geloescht markieren (die VM ignoriert sie dann). */
    fun deleteRide(id: String) {
        withUser { uid ->
            FirebaseFirestore.getInstance().collection("rides").document(id)
                .update(mapOf("deleted" to true, "uploaded" to FieldValue.serverTimestamp()))
                .addOnFailureListener { Log.w(TAG, "Loeschen fehlgeschlagen", it) }
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
    fun requestRefresh(ctx: Context) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            withUser { uid ->
                doc(uid).set(mapOf("token" to token, "refreshAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .addOnFailureListener { Log.w(TAG, "Aktualisieren-Anfrage fehlgeschlagen", it) }
            }
        }
    }
}
