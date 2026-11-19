package de.rb64.live

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.rb64.live.widget.Rb64WidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fordert frische Daten an (Antwort kommt per Firebase) und zeichnet das Widget neu,
 *  damit abgefahrene Zuege nach oben wandern und um 13 Uhr die Richtung wechselt. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) { Repo.requestRefresh(applicationContext) }
        Rb64WidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}
