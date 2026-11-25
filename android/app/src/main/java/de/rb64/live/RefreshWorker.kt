package de.rb64.live

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.rb64.live.widget.Rb64WidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zeichnet das Widget neu, damit abgefahrene Zuege nach oben wandern und um 13 Uhr die Richtung wechselt.
 *  Neue Daten fordert er nicht an: der Server schickt ohnehin spaetestens alle 5 min - eine Anfrage alle 15 min
 *  von jedem Geraet wuerde sonst jedes Mal eine Sonder-Sendung ausloesen. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Rb64WidgetProvider.updateAll(applicationContext)
        // am 1. des Monats: einmal melden, dass der Monatsrueckblick da ist
        runCatching { de.rb64.live.ride.MonthReviews.notifyIfDue(applicationContext) }
        return Result.success()
    }
}
