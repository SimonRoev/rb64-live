package de.rb64.live.ride

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.rb64.live.Battery
import de.rb64.live.Notifier
import de.rb64.live.Repo
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.TextAction
import de.rb64.live.ui.Ts

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** Android-Einstellungen dieser App (wenn eine Berechtigung schon dauerhaft abgelehnt wurde). */
private fun openAppSettings(ctx: Context) = runCatching {
    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Hinweis oben in "Fahrten": was fuer Fahrten fehlt (Standort, Benachrichtigung, Vorschlag am Bahnhof, Akku).
 * Erscheint nur, wenn etwas fehlt, und prueft beim Zurueckkehren aus den Android-Einstellungen neu.
 */
@Composable
fun RidePermissionsCard() {
    val ctx = LocalContext.current
    val prefs = Repo.prefs(ctx)
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // tick liest den Zustand nach jeder Rueckkehr neu
    val fine = remember(tick) { has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val background = remember(tick) { has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notify = remember(tick) { Build.VERSION.SDK_INT < 33 || Notifier.permitted(ctx) }
    val fence = remember(tick) { Fences.enabled(ctx) }
    val fenceSkipped = remember(tick) { prefs.getBoolean("perm_fence_skip", false) }
    val battery = remember(tick) { Battery.relevant(ctx) && !Battery.unrestricted(ctx) }

    // Erste Anfrage per Android-Dialog; wurde schon einmal abgelehnt, fragt Android nicht mehr -> Einstellungen oeffnen
    fun ask(launch: () -> Unit, perm: String) {
        val key = "perm_asked_$perm"
        if (prefs.getBoolean(key, false)) openAppSettings(ctx) else { prefs.edit().putBoolean(key, true).apply(); launch() }
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { prefs.edit().putBoolean("fence_enabled", true).apply(); Fences.sync(ctx) }
        tick++
    }
    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askFineThenBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION); tick++
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // freiwilliger Vorschlag erst, wenn der Standort fuers Mitfahren schon erlaubt ist
    val missingFence = (fence && !(fine && background)) || (!fence && !fenceSkipped && fine && !background)
    if (fine && notify && !missingFence && !battery) return

    RbCard {
        Text("Für Fahrten fehlt noch etwas", style = Ts.textStrong)
        Spacer(Modifier.size(Dim.s8))
        Column(verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
            if (!fine) Item(
                "Standort",
                "Ohne Standort kann „Ich fahre mit“ keine Fahrt aufzeichnen – es gibt dann weder Auswertung noch Fahrkarte.",
                "Standort erlauben", required = true,
            ) { ask({ askFine.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, Manifest.permission.ACCESS_FINE_LOCATION) }
            if (!notify) Item(
                "Benachrichtigungen",
                "Während der Fahrt zeigt die App Stand und Tempo als Benachrichtigung. Ohne Erlaubnis läuft die Aufzeichnung ohne sichtbare Benachrichtigung.",
                "Erlauben", required = true,
            ) { ask({ askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }, Manifest.permission.POST_NOTIFICATIONS) }
            if (battery) Item(
                "Im Hintergrund laufen",
                "${if (Battery.aggressiveVendor) Battery.vendorName else "Android"} kann die Aufzeichnung bei ausgeschaltetem Bildschirm anhalten.",
                "Zulassen", required = true,
            ) { for (i in Battery.requestIntents(ctx)) if (runCatching { batteryLauncher.launch(i) }.isSuccess) break }
            if (missingFence) Item(
                "Vorschlag am Bahnhof" + if (fence) "" else " (freiwillig)",
                if (fence) "Ist eingeschaltet, braucht aber Standort „Immer zulassen“ – sonst merkt das Handy nicht, dass du am Bahnhof bist."
                else "Stehst du an einem Bahnhof deiner Strecken, schlägt die App „Ich fahre mit“ vor. Braucht Standort „Immer zulassen“.",
                if (fence) "Immer zulassen" else "Einschalten", required = fence,
                onSkip = if (fence) null else ({ prefs.edit().putBoolean("perm_fence_skip", true).apply(); tick++ }),
            ) {
                when {
                    !fine -> ask({ askFineThenBackground.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, Manifest.permission.ACCESS_FINE_LOCATION)
                    !background -> ask({ askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    else -> { prefs.edit().putBoolean("fence_enabled", true).apply(); Fences.sync(ctx); tick++ }
                }
            }
        }
    }
}

@Composable
private fun Item(title: String, text: String, action: String, required: Boolean, onSkip: (() -> Unit)? = null, onClick: () -> Unit) {
    Row {
        Icon(if (required) RbIcons.warning else RbIcons.location, null, tint = if (required) Rb.c.warn else Rb.c.muted,
            modifier = Modifier.padding(top = 2.dp).size(Dim.iconSmall))
        Spacer(Modifier.width(Dim.s8))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ts.smallStrong, color = Rb.c.onSurface)
            Text(text, style = Ts.small, color = Rb.c.muted)
            Row {
                TextAction(action, null, onClick)
                if (onSkip != null) TextAction("Nicht nötig", null, onSkip)
            }
        }
    }
}
