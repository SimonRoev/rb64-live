package de.rb64.live.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Knoepfe im Widget (Richtung, fruehere Zuege, Aktualisieren). Nicht exportiert: nur die eigenen PendingIntents erreichen ihn. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Rb64WidgetProvider.handleAction(ctx, intent.action)
}
