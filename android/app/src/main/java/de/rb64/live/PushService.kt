package de.rb64.live

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.rb64.live.widget.Rb64WidgetProvider

/** Empfaengt die Daten von der VM (ohne eigene Push-Anzeige von Firebase). */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val json = message.data["z"]?.let { runCatching { Repo.decode(it) }.getOrNull() }
            ?: message.data["p"] ?: return
        val snap = runCatching { Repo.save(applicationContext, json); Repo.parse(json) }
            .onFailure { Log.w("RB64", "Daten nicht lesbar", it) }.getOrNull() ?: return
        Rb64WidgetProvider.updateAll(applicationContext)
        Notifier.onPayload(applicationContext, snap)
        Notifier.onPinned(applicationContext, snap)
        de.rb64.live.ride.RideService.refresh(applicationContext)
    }

    override fun onNewToken(token: String) {
        Cloud.registerToken(applicationContext, force = true)
    }
}
