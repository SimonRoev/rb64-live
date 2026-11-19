package de.rb64.live

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.rb64.live.widget.Rb64WidgetProvider

/** Zeichnet nur das Widget neu (z. B. "Aktualisiere …" zuruecksetzen, fruehere Zuege wieder ausblenden). */
class RenderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Rb64WidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}
