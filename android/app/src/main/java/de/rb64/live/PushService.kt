package de.rb64.live

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.rb64.live.widget.Rb64WidgetProvider

/** Empfaengt die Daten von der VM (ohne eigene Push-Anzeige von Firebase). */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "live") {          // Live-Positionen von Mitfahrern (nur an Teilende)
            LiveGps.onPush(applicationContext, message.data["l"])
            return
        }
        if (message.data["type"] == "msg") {          // Rundruf bzw. Hinweis von der VM
            Notifier.showMessage(applicationContext, message.data["t"] ?: "RB64 Live", message.data["b"] ?: "")
            return
        }
        // Nachricht aus der Firebase-Konsole (z. B. wenn die VM ausgefallen ist): ist die App im Hintergrund,
        // zeigt Android sie selbst an (Kanal "Hinweise", siehe Manifest); ist sie offen, landet sie hier
        message.notification?.let { n ->
            Notifier.showMessage(applicationContext, n.title ?: "RB64 Live", n.body ?: "")
            if (message.data.isEmpty()) return
        }
        val json = message.data["z"]?.let { runCatching { Repo.decode(it) }.getOrNull() }
            ?: message.data["p"] ?: return
        // Hinweis an alle kommt mit den Daten der VM mit (fehlt er, gibt es keinen)
        runCatching { StatusStore.fromPayload(applicationContext, org.json.JSONObject(json).optJSONObject("st")) }
        val snap = runCatching { Repo.save(applicationContext, json); Repo.parse(json) }
            .onFailure { Log.w("RB64", "Daten nicht lesbar", it) }.getOrNull() ?: return
        Rb64WidgetProvider.updateAll(applicationContext)
        runCatching { PCheckStore.record(applicationContext, snap) }   // Prognose-Check: Verlauf mitschreiben
        Notifier.onPayload(applicationContext, snap)
        Notifier.onPinned(applicationContext, snap)
        de.rb64.live.ride.RideService.refresh(applicationContext)
        // am 1. des Monats einmal: "Dein Oktober ist da" (kostet nichts, wenn nicht faellig)
        runCatching { de.rb64.live.ride.MonthReviews.notifyIfDue(applicationContext) }
    }

    override fun onNewToken(token: String) {
        Cloud.registerToken(applicationContext, force = true)
    }
}
