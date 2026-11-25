package de.rb64.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Pill
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import de.rb64.live.widget.Rb64WidgetProvider
import kotlinx.coroutines.launch

/** Einfuehrung beim ersten Start (und ueber Einstellungen erneut): Strecke waehlen, Zeiten lesen, Funktionen. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pages = 7
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    // Berechtigungen fragt die Seite "Einschalten" gezielt ab - beim Beenden nichts mehr nachfragen
    fun finish() = onDone()

    Column(Modifier.fillMaxSize().background(Rb.c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Dim.s16), verticalAlignment = Alignment.CenterVertically) {
            Text("RB64 Live", style = Ts.textStrong, color = Rb.c.primary, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages - 1) Text("Überspringen", style = Ts.textStrong, color = Rb.c.muted,
                modifier = Modifier.clip(RoundedCornerShape(Dim.rButton)).clickable { finish() }.padding(Dim.s12))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dim.s24, vertical = Dim.s8),
                verticalArrangement = Arrangement.spacedBy(Dim.s16)) {
                when (p) {
                    0 -> PageWelcome()
                    1 -> PageTimes()
                    2 -> PageLive()
                    3 -> PageRide()
                    4 -> PageWidgets()
                    5 -> PagePrivacy()
                    else -> PageFeatures()
                }
            }
        }
        // Punkte + Weiter
        Row(Modifier.fillMaxWidth().padding(Dim.s16), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages) { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "punkt")
                    Box(Modifier.height(8.dp).width(w).clip(CircleShape)
                        .background(if (i == pager.currentPage) Rb.c.primary else Rb.c.line))
                }
            }
            val last = pager.currentPage == pages - 1
            RbButton(if (last) "Los geht's" else "Weiter", {
                if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, icon = if (last) null else RbIcons.arrowRight)
        }
    }
}

/* ------------------------------------------------------------------ Seiten */

@Composable
private fun Head(icon: ImageVector, title: String, sub: String) {
    Spacer(Modifier.height(Dim.s8))
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(Dim.rCard)).background(Rb.c.primaryTint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Rb.c.primary, modifier = Modifier.size(28.dp))
    }
    Text(title, style = Ts.big, color = Rb.c.onSurface)
    Text(sub, style = Ts.text, color = Rb.c.muted)
}

/** Erklaer-Zeile: kleines Beispiel links, Text rechts. */
@Composable
private fun Explain(sample: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterStart) { sample() }
        Text(text, style = Ts.small, color = Rb.c.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Bullet(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.s12)) {
        Icon(icon, null, tint = Rb.c.primary, modifier = Modifier.padding(top = 2.dp).size(Dim.icon))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ts.textStrong, color = Rb.c.onSurface)
            Text(text, style = Ts.small, color = Rb.c.muted)
        }
    }
}

@Composable
private fun ColumnScope.PageWelcome() {
    val ctx = LocalContext.current
    var fav by remember { mutableStateOf(Repo.favs(ctx).firstOrNull() ?: Fav(3, 1)) }
    fun save(f: Fav) {
        fav = f
        val rest = Repo.favs(ctx).drop(1)
        Repo.setFavs(ctx, listOf(f) + rest.filter { it != f })
        de.rb64.live.ride.Fences.sync(ctx)
        Rb64WidgetProvider.updateAll(ctx)
    }
    Head(RbIcons.departures, "Moin!",
        "RB64 Live zeigt dir, wann dein Zug zwischen Münster und Enschede wirklich fährt – oft früher als die DB es meldet.")
    RbCard {
        Text("Deine Strecke", style = Ts.textStrong)
        Text("Links dein Start (z. B. zu Hause), rechts das Ziel. Die Rückfahrt gibt es automatisch dazu, weitere Strecken in den Einstellungen.",
            style = Ts.small, color = Rb.c.muted)
        Spacer(Modifier.height(Dim.s8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StationPicker(null, fav.a, exclude = fav.b) { save(fav.copy(a = it)) }
            Text("  ↔  ", style = Ts.text, color = Rb.c.muted)
            StationPicker(null, fav.b, exclude = fav.a) { save(fav.copy(b = it)) }
        }
    }
}

@Composable
private fun ColumnScope.PageTimes() {
    Head(RbIcons.clock, "Zeiten lesen", "So liest du eine Abfahrt – in der App, im Widget und in den Meldungen gleich.")
    RbCard(padding = Dim.s16) {
        Text("Abfahrt in", style = Ts.small, color = Rb.c.muted)
        Text("13 min", style = Ts.hero, color = Rb.c.onSurface)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
            Text("08:15", style = Ts.title, color = Rb.c.warn)
            Text("Plan 08:12", style = Ts.small, color = Rb.c.muted, modifier = Modifier.padding(bottom = 2.dp))
            Text("→ 08:41", style = Ts.text, color = Rb.c.muted)
            Icon(RbIcons.live, null, tint = Rb.c.muted, modifier = Modifier.padding(bottom = 4.dp).size(Dim.iconSmall))
        }
    }
    Explain({ Text("13 min", style = Ts.title) }, "Groß: wie lange du noch hast. Unter 2 Minuten steht dort „Jetzt einsteigen“ mit Gleis.")
    Explain({ Text("08:15", style = Ts.title, color = Rb.c.warn) }, "Die Zeit, mit der du rechnen solltest. Farbig, wenn der Zug später ist.")
    Explain({ Text("Plan 08:12", style = Ts.small, color = Rb.c.muted) }, "Die Zeit aus dem Fahrplan – zur Einordnung, nur bei Verspätung.")
    Explain({ Text("→ 08:41", style = Ts.text, color = Rb.c.muted) }, "Ankunft an deinem Ziel.")
    Explain({ Pill("+3", Rb.c.warn, Rb.c.warnBg) }, "Verspätung in Minuten. Grün = pünktlich, Orange = später, Rot = viel später oder fällt aus.")
    Explain({
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
            Icon(RbIcons.live, null, tint = Rb.c.muted, modifier = Modifier.size(Dim.icon))
            Icon(RbIcons.clock, null, tint = Rb.c.muted, modifier = Modifier.size(Dim.icon))
        }
    }, "Funkwellen = Echtzeit vom Zug. Uhr = es gibt noch keine Daten, nur der Fahrplan.")
    Explain({ Text("Prognose +5\nDB +2", style = Ts.smallStrong, color = Rb.c.primary) },
        "Eigene Prognose: aus dem Umlauf und den Kreuzungen berechnet – meist früher richtig als die DB.")
    Explain({ Text("meist +4", style = Ts.small, color = Rb.c.muted) }, "So spät war dieser Zug in den letzten Wochen üblicherweise.")
    Explain({ Text("Stand 08:02", style = Ts.small, color = Rb.c.muted) }, "Wie alt die Daten sind. Ziehen zum Aktualisieren; nach 15 Minuten gelten sie als veraltet.")
}

@Composable
private fun ColumnScope.PageLive() {
    Head(RbIcons.route, "Die Strecke live", "Unter „Strecke“ siehst du alle Züge auf der Linie – wo sie gerade sind und wo sie sich begegnen.")
    Bullet(RbIcons.live, "Züge in Bewegung", "Jeder Zug wandert entlang der echten Strecke. Dein Zug hat einen Ring.")
    Bullet(RbIcons.swap, "Kreuzungen", "Die RB64 ist größtenteils eingleisig. Züge können sich nur an bestimmten Halten begegnen (Quadrat) – " +
        "ist einer spät, wartet der andere. Daher kommen viele Verspätungen, und daraus rechnet die App ihre Prognose.")
    Bullet(RbIcons.settings, "Ausschnitt wählen", "Oben stellst du ein, von wo bis wo die Linie gezeigt wird. Unten schaltest du zwischen echter Kurve und gerader Linie mit gleichen Abständen.")
}

@Composable
private fun ColumnScope.PageRide() {
    Head(RbIcons.pin, "Dein Zug", "Tippe auf einen Zug für alle Halte, Gründe und Gleise. Dort kannst du ihn anheften oder mitfahren.")
    Bullet(RbIcons.pin, "Anheften", "Der Zug steht oben als „Dein Zug“ und auf dem Sperrbildschirm. Ändert sich die Abfahrt um mehr als ein paar Minuten, kommt eine Meldung (einstellbar).")
    Bullet(RbIcons.play, "Ich fahre mit", "Ein Knopf – in der App oder in der Meldung deines angehefteten Zugs. Startet eine Aufzeichnung per GPS: " +
        "bis der Zug kommt „Warte auf …“, danach nächster Halt, Ankunft und Geschwindigkeit. Hast du in den Einstellungen " +
        "„Live-Position teilen“ an, teilt jede Fahrt automatisch mit – dafür siehst du andere Mitfahrer live.")
    Bullet(RbIcons.signal, "Unterwegs melden", "Funkloch oder voller Zug? Mit einem Tipp in der Meldung festhalten. Deine Fahrten helfen, die Prognose zu verbessern.")
    Bullet(RbIcons.rides, "Automatisch", "Stehst du an deinem Bahnhof, fragt die App, ob du mitfährst – „Los“ geht direkt vom Sperrbildschirm.")
    Bullet(RbIcons.trend, "Fahrkarte und Rückblick", "Nach jeder Fahrt gibt es eine Fahrkarte zum Sammeln, unter „Fahrten“ dein Stempelheft. " +
        "Fährst du in einem Monat mindestens 8-mal mit, kommt am 1. dein Monatsrückblick.")
}

@Composable
private fun ColumnScope.PageWidgets() {
    Head(RbIcons.departures, "Widgets", "Damit du die App kaum öffnen musst.")
    Bullet(RbIcons.departures, "Groß oder klein", "Groß (3×3) mit den nächsten Zügen oder klein (2×1) nur mit dem nächsten. Lange auf den Startbildschirm drücken → Widgets → RB64 Live.")
    Bullet(RbIcons.arrowRight, "Direkt zum Zug", "Tippen auf einen Zug im Widget öffnet ihn mit allen Halten.")
    Bullet(RbIcons.settings, "Alles einstellbar", "Strecken, Zeitfenster, Farbschema und Effekte findest du über den Regler oben rechts (das Symbol mit den drei Schiebern). Diese Einführung auch, ganz unten.")
}

/* ------------------------------------------------------------------ Datenschutz */

@Composable
private fun ColumnScope.PagePrivacy() {
    Head(RbIcons.lock, "Deine Daten", "Kurz und ehrlich: Was die App speichert und wohin es geht.")
    Bullet(RbIcons.check, "Kein Konto, keine Werbung, kein Tracking",
        "Die App meldet sich mit einer zufälligen Kennung an, damit der Server dir Zugdaten schicken kann. Name, E-Mail oder Telefonnummer kennt sie nicht.")
    Bullet(RbIcons.route, "Strecken und Einstellungen bleiben auf dem Handy",
        "Welche Strecken du fährst und wann du Meldungen willst, wird nicht übertragen. Die Meldungen entstehen auf deinem Handy.")
    Bullet(RbIcons.rides, "Fahrten nur, wenn du mitfährst",
        "Erst nach „Ich fahre mit“ – oder, wenn du es einschaltest, automatisch für deinen angehefteten Zug – zeichnet die App per GPS auf – nur Punkte entlang der Bahnstrecke, dazu Halte und deine Markierungen. " +
            "Die Fahrt geht anonym an unseren Server, damit die Prognose besser wird. Beim Löschen wählst du: nur auf dem Handy oder überall.")
    Bullet(RbIcons.live, "Live-Position nur, wenn du sie teilst",
        "Freiwillig und standardmäßig aus: Während du mitfährst, sendet die App alle 30 s nur, wo auf der Strecke dein Zug ist " +
            "und wie schnell er fährt – ohne Koordinaten, anonym. Das sehen nur andere, die selbst teilen; am Fahrtende wird es gelöscht.")
    Bullet(RbIcons.announce, "Teilen nur, wenn du es willst",
        "Fahrkarte, Stempelheft und Rückblick entstehen auf deinem Handy. Teilst du eine Karte als Bild, siehst du vorher genau, was drauf ist – " +
            "Uhrzeiten, Datum, Zug und Platz nur, wenn du sie einschaltest.")
    Bullet(RbIcons.location, "Standort am Bahnhof wird nicht gespeichert",
        "Für den Mitfahr-Vorschlag meldet Android der App nur „du bist am Bahnhof X“. Der Standort wird dabei weder gespeichert noch gesendet.")
    Bullet(RbIcons.lock, "Wo die Daten liegen",
        "Übertragung und Push über Google Firebase, Auswertung auf einem Server der FH Münster. Zugdaten stammen von DB und NS.")
}

/* ------------------------------------------------------------------ Funktionen einschalten */

private fun granted(ctx: android.content.Context, perm: String) =
    ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

private fun notifyOn(ctx: android.content.Context) =
    Settings.load(ctx).enabled && Notifier.permitted(ctx)

private fun fenceOn(ctx: android.content.Context) =
    de.rb64.live.ride.Fences.enabled(ctx) && de.rb64.live.ride.Fences.hasPermissions(ctx)

@Composable
private fun ColumnScope.PageFeatures() {
    val ctx = LocalContext.current
    var nOn by remember { mutableStateOf(notifyOn(ctx)) }
    var fOn by remember { mutableStateOf(fenceOn(ctx)) }
    var fDenied by remember { mutableStateOf(false) }

    // 1) Meldungen: Erlaubnis -> Benachrichtigungen fuer alle Richtungen an
    fun enableNotify() {
        Settings.load(ctx).copy(enabled = true).save(ctx)
        // noch nichts selbst eingestellt -> Standard: deine Strecken (hin morgens, zurueck nachmittags)
        if (!Repo.prefs(ctx).getBoolean("n_rules_custom", false)) Rules.save(ctx, Rules.defaults(ctx))
        nOn = notifyOn(ctx)
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) enableNotify() }

    // 2) Mitfahr-Vorschlag: erst Standort "waehrend der Nutzung", dann "Immer zulassen"
    fun enableFence() {
        Repo.prefs(ctx).edit().putBoolean("fence_enabled", true).apply()
        de.rb64.live.ride.Fences.sync(ctx)
        fOn = fenceOn(ctx); fDenied = !fOn
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enableFence() }
    val askFine = rememberLauncherForActivityResult(de.rb64.live.ui.FineLocation()) { ok ->
        if (ok) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else fDenied = true
    }

    val battery = remember { Battery.relevant(ctx) }
    Head(RbIcons.check, "Einschalten",
        (if (battery) "Drei Dinge brauchen" else "Zwei Funktionen brauchen") + " deine Erlaubnis. Alles geht auch später in den Einstellungen.")

    FeatureCard(
        title = "Meldungen bei Verspätung",
        text = "Nur für deine Strecken in ihren Zeitfenstern und nur, wenn sich etwas ändert: Verspätung, Ausfall, Gleiswechsel. Während einer Fahrt ist Ruhe.",
        permission = "Android fragt nach „Benachrichtigungen“.",
        on = nOn,
        preview = {
            NotifPreview("Häger → Münster Hbf: 07:45 statt 07:33 (+12)", "Wartet in Altenberge auf den verspäteten Gegenzug", null)
        },
        extra = if (nOn) ({
            Spacer(Modifier.height(Dim.s12))
            Text("Deine Benachrichtigungen – Zeiten und Tage antippen zum Anpassen. Weitere, auch für einzelne Züge, " +
                "in den Einstellungen.", style = Ts.small, color = Rb.c.muted)
            Spacer(Modifier.height(Dim.s8))
            RulesEditor(enabled = true, compact = true)
        }) else null,
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS))
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else enableNotify()
    }

    FeatureCard(
        title = "Mitfahren am Bahnhof vorschlagen",
        text = "Stehst du an einem Bahnhof deiner Strecken, schlägt die App den passenden Zug vor – ist einer angeheftet, genau diesen. „Los“ startet die Aufzeichnung direkt vom Sperrbildschirm.",
        permission = "Android fragt zweimal nach dem Standort: zuerst „Bei Nutzung der App“, danach „Immer zulassen“. Nur so merkt das Handy, dass du am Bahnhof bist.",
        on = fOn,
        preview = { NotifPreview("Dein Zug: 07:45 → Münster Hbf (+12) · Gleis 2", "Mitfahren und Fahrt aufzeichnen?", "▶ Los, ich fahre mit") },
    ) {
        when {
            !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> askFine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> enableFence()
        }
    }
    if (fDenied && !fOn) Text("Ohne „Immer zulassen“ bleibt der Vorschlag aus. „Ich fahre mit“ funktioniert trotzdem.",
        style = Ts.small, color = Rb.c.warn)

    var liveOn by remember { mutableStateOf(LiveGps.enabled(ctx)) }
    FeatureCard(
        title = "Live-Position teilen",
        text = "Während du mitfährst, siehst du die Züge anderer Mitfahrer live auf der Strecke – wenn du selbst teilst. " +
            "Gesendet wird nur, wo auf der Strecke dein Zug ist, ohne Koordinaten und anonym.",
        permission = "Keine weitere Berechtigung. Gilt nur während „Ich fahre mit“, jederzeit in den Einstellungen abschaltbar.",
        on = liveOn,
        preview = null,
    ) { LiveGps.setEnabled(ctx, true); liveOn = true }

    if (battery) BatteryCard()
}

/**
 * Akku-Optimierung abschalten (vor allem Samsung legt Apps sonst schlafen). Zustand wird beim Zurueckkehren
 * aus den Android-Einstellungen neu geprueft. Den Herstellerschritt kann die App nicht pruefen -> nur Hinweis + Knopf.
 */
@Composable
internal fun BatteryCard() {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(Battery.unrestricted(ctx)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ok = Battery.unrestricted(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { ok = Battery.unrestricted(ctx) }
    fun request() {
        for (i in Battery.requestIntents(ctx)) if (runCatching { launcher.launch(i) }.isSuccess) break
    }
    FeatureCard(
        title = "Im Hintergrund laufen lassen",
        text = "${if (Battery.aggressiveVendor) Battery.vendorName else "Android"} legt Apps gern schlafen, um Akku zu sparen. " +
            "Dann kommen Meldungen zu spät, das Widget bleibt stehen und der Vorschlag am Bahnhof fehlt. " +
            "RB64 Live wacht nur kurz auf, wenn neue Zugdaten kommen.",
        permission = "Android fragt „Im Hintergrund ausführen lassen?“ – dort „Zulassen“ wählen.",
        on = ok,
        preview = null,
        extra = Battery.vendorHint()?.let { hint -> {
            Spacer(Modifier.height(Dim.s12))
            Text(hint, style = Ts.small, color = Rb.c.onSurface)
            Text("${Battery.vendorName}-Einstellungen öffnen", style = Ts.smallStrong, color = Rb.c.primary,
                modifier = Modifier.padding(top = Dim.s4).clip(RoundedCornerShape(Dim.rButton))
                    .clickable { Battery.openVendorSettings(ctx) }.padding(vertical = Dim.s8))
        } },
        onEnable = ::request,
    )
}

/** Funktion mit Vorschau, Erklaerung zur Berechtigung und Schalter-Knopf. */
@Composable
private fun FeatureCard(title: String, text: String, permission: String, on: Boolean,
                        preview: (@Composable () -> Unit)?, extra: (@Composable () -> Unit)? = null, onEnable: () -> Unit) {
    RbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ts.textStrong, modifier = Modifier.weight(1f))
            if (on) Pill("An", Rb.c.ok, Rb.c.okBg)
        }
        Spacer(Modifier.height(Dim.s8))
        if (preview != null) { preview(); Spacer(Modifier.height(Dim.s8)) }
        Text(text, style = Ts.small, color = Rb.c.onSurface)
        Spacer(Modifier.height(Dim.s4))
        Text(permission, style = Ts.small, color = Rb.c.muted)
        if (!on) {
            Spacer(Modifier.height(Dim.s12))
            RbButton("Einschalten", onEnable, Modifier.fillMaxWidth(), primary = false)
        }
        extra?.invoke()
    }
}

/** Nachgebaute Benachrichtigung als Vorschau. */
@Composable
private fun NotifPreview(title: String, text: String, action: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton)).background(Rb.c.background).padding(Dim.s12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Rb.c.primary), contentAlignment = Alignment.Center) {
                Icon(RbIcons.departures, null, tint = Rb.c.onPrimary, modifier = Modifier.size(12.dp))
            }
            Text("  RB64 Live · jetzt", style = Ts.small, color = Rb.c.muted)
        }
        Spacer(Modifier.height(Dim.s4))
        Text(title, style = Ts.smallStrong, color = Rb.c.onSurface)
        Text(text, style = Ts.small, color = Rb.c.muted)
        if (action != null) Text(action, style = Ts.smallStrong, color = Rb.c.primary, modifier = Modifier.padding(top = Dim.s8))
    }
}
