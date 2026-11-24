package de.rb64.live

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        de.rb64.live.ride.RideService.createChannel(this)
        de.rb64.live.ride.MonthReviews.createChannel(this)
        runCatching { de.rb64.live.ride.Sharing.cleanup(this) }   // alte Teilen-Bilder aus dem Zwischenspeicher
        de.rb64.live.ride.RideStore.init(this)
        de.rb64.live.ride.Fences.sync(this)
        // Rueckfallebene: alle 15 min (Android-Minimum) frische Daten anfordern und Widget neu zeichnen
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "rb64-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build(),
        )
        // Firebase-Token bei der VM anmelden (ueber Firestore, nur wenn neu)
        Cloud.registerToken(this)
        Cloud.retryPendingRide(this)
    }
}
