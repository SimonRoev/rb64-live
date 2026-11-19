package de.rb64.live

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Banner
import de.rb64.live.ui.BottomNav
import de.rb64.live.ui.Dim
import de.rb64.live.ui.HeaderChip
import de.rb64.live.ui.IconBtn
import de.rb64.live.ui.ListCard
import de.rb64.live.ui.Rb
import de.rb64.live.ui.Rb64Theme
import de.rb64.live.ui.isDark
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.SectionHeader
import de.rb64.live.ui.TopBar
import de.rb64.live.ui.Ts
import de.rb64.live.widget.Rb64WidgetProvider
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refresh(this)                       // beim Oeffnen frische Daten anfordern
        setContent { ThemedRoot(this) }
        if (intent?.getBooleanExtra("summary", false) == true) UiRequests.summary.value = true
        intent?.getStringExtra("train")?.let { UiRequests.openTrain.value = it to (intent.getStringExtra("dep") ?: "") }
    }

    override fun onResume() {
        super.onResume()
        // Zurueck in die App mit alten Daten -> still nachladen (Antwort mit hoher Prioritaet)
        val snap = Repo.load(this)
        if (!Repo.isRefreshing(this) && (snap == null || snap.ageMs() > Snapshot.AUTO_REFRESH_MS)) refresh(this)
        Updates.check(this)      // hoechstens alle 6 h: neue Version auf der Webseite?
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("summary", false)) UiRequests.summary.value = true
        intent.getStringExtra("train")?.let { UiRequests.openTrain.value = it to (intent.getStringExtra("dep") ?: "") }
    }
}

/** Anfragen von aussen (z. B. Benachrichtigung "Fahrt gespeichert" -> Auswertung zeigen). */
object UiRequests {
    val summary = androidx.compose.runtime.mutableStateOf(false)
    /** Zug, dessen Detailblatt geoeffnet werden soll (aus Widget oder Benachrichtigung): Zugnummer, Plan-Abfahrt. */
    val openTrain = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
    /** Gespeicherte Fahrt, deren Auswertung im Tab "Fahrten" offen ist. */
    val openRide = androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** Aktualisieren anstossen: Anzeige "Aktualisiere …" in App und Widget, Antwort kommt per Firebase. */
fun refresh(ctx: android.content.Context) {
    Repo.markRefreshing(ctx)
    Rb64WidgetProvider.updateAll(ctx)
    Rb64WidgetProvider.scheduleRender(ctx, 42)
    Repo.requestRefresh(ctx)
}

private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)

/** Farbschema und Effekte aus den Einstellungen; Statusleisten-Symbole passend zur Kopfleiste. */
@Composable
private fun ThemedRoot(act: ComponentActivity) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "theme_mode" || k == "onboarded" || k?.startsWith("vis_") == true) tick++ }
        Repo.prefs(ctx).registerOnSharedPreferenceChangeListener(l)
        onDispose { Repo.prefs(ctx).unregisterOnSharedPreferenceChangeListener(l) }
    }
    val mode = remember(tick) { Repo.themeMode(ctx) }
    val vis = remember(tick) { Repo.visuals(ctx) }
    val dark = isDark(mode)
    val onboarded = remember(tick) { Repo.prefs(ctx).getBoolean("onboarded", false) }
    val headerDark = dark || (vis.colorHeader && onboarded)    // Einfuehrung hat keine farbige Kopfleiste
    SideEffect {
        val t = android.graphics.Color.TRANSPARENT
        act.enableEdgeToEdge(
            statusBarStyle = if (headerDark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
            navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
        )
    }
    Rb64Theme(mode, vis) {
        if (onboarded) AppRoot()
        else Onboarding(onDone = { Repo.prefs(ctx).edit().putBoolean("onboarded", true).apply() })
    }
}

private const val TAB_DEP = 0
private const val TAB_LIVE = 1
private const val TAB_RIDES = 2
private const val TAB_SETTINGS = 3

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }       // bei jeder Aenderung der gespeicherten Werte neu zeichnen
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        Repo.prefs(ctx).registerOnSharedPreferenceChangeListener(l)
        onDispose { Repo.prefs(ctx).unregisterOnSharedPreferenceChangeListener(l) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    val ride by de.rb64.live.ride.RideStore.state.collectAsState()
    var tab by remember { mutableIntStateOf(TAB_DEP) }
    var summaryReq by UiRequests.summary
    LaunchedEffect(summaryReq) {
        if (summaryReq) { tab = TAB_RIDES; UiRequests.openRide.value = de.rb64.live.ride.RideStore.history.value.firstOrNull()?.id; summaryReq = false }
    }
    val openReq by UiRequests.openTrain
    LaunchedEffect(openReq) { if (openReq != null) tab = TAB_DEP }

    // "Ich fahre mit": erst Standortberechtigung (waehrend der Nutzung), dann starten
    var pending by remember { mutableStateOf<de.rb64.live.ride.RideInfo?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending
        if (ok && p != null) { de.rb64.live.ride.RideService.start(ctx, p); tab = TAB_RIDES }
        pending = null
    }
    fun startRide(rel: Rel, row: Row) {
        val info = de.rb64.live.ride.RideService.infoFrom(rel, row)
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            de.rb64.live.ride.RideService.start(ctx, info); tab = TAB_RIDES
        } else { pending = info; askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    fun stopRide() = ctx.startService(Intent(ctx, de.rb64.live.ride.RideService::class.java).setAction(de.rb64.live.ride.RideService.ACTION_STOP))

    var prevTab by remember { mutableIntStateOf(TAB_DEP) }
    fun openSettings() { prevTab = if (tab == TAB_SETTINGS) prevTab else tab; tab = TAB_SETTINGS }
    BackHandler(enabled = tab != TAB_DEP) { tab = if (tab == TAB_SETTINGS) prevTab else TAB_DEP }
    // Navigation: Strecke | Abfahrten (Mitte, hervorgehoben) | Fahrten; Einstellungen ueber das Zahnrad oben
    val navTabs = listOf(TAB_LIVE, TAB_DEP, TAB_RIDES)
    Column(Modifier.fillMaxSize().background(Rb.c.background)) {
        Box(Modifier.weight(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(
                de.rb64.live.ui.LocalOpenSettings provides (if (tab == TAB_SETTINGS) null else ::openSettings),
            ) {
                when (tab) {
                    TAB_LIVE -> LiveScreen(tick)
                    TAB_RIDES -> RidesScreen(ride, onGoDepartures = { tab = TAB_DEP })
                    TAB_SETTINGS -> SettingsScreen(onBack = { tab = prevTab })
                    else -> DeparturesScreen(tick, now, ride, onStartRide = ::startRide, onStopRide = ::stopRide,
                        onOpenRide = { tab = TAB_RIDES }, onAddRoute = ::openSettings)
                }
            }
        }
        BottomNav(
            listOf("Strecke" to RbIcons.route, "Abfahrten" to RbIcons.departures,
                (if (ride != null) "Fahrt läuft" else "Fahrten") to RbIcons.rides),
            navTabs.indexOf(tab), center = 1,
        ) { tab = navTabs[it] }
    }
}

/* ================================================================== Abfahrten */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DeparturesScreen(
    tick: Int, now: Long,
    ride: de.rb64.live.ride.RideState?,
    onStartRide: (Rel, Row) -> Unit, onStopRide: () -> Unit, onOpenRide: () -> Unit, onAddRoute: () -> Unit,
) {
    val ctx = LocalContext.current
    var showPast by remember { mutableStateOf(false) }
    var sheetRow by remember { mutableStateOf<Row?>(null) }
    val snap = remember(tick) { Repo.load(ctx) }
    val favs = remember(tick) { Repo.favs(ctx) }
    val favIdx = remember(tick) { Repo.favIndex(ctx) }
    val route = remember(tick) { Repo.currentRoute(ctx) }
    val rel = remember(tick, now / 30_000) { Repo.currentRel(ctx, snap) }
    val refreshing = remember(tick, now) { Repo.isRefreshing(ctx) }
    val past = rel?.let { Repo.pastRows(it, now) }.orEmpty()
    val upcoming = rel?.let { Repo.visibleRows(it, false, now) }.orEmpty()
    val age = snap?.ageMs(now) ?: 0L
    val stale = snap?.stale(now) == true
    val weekday = remember(tick) {
        java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            .let { it != java.util.Calendar.SATURDAY && it != java.util.Calendar.SUNDAY }
    }
    val pinned = remember(tick, now / 30_000) { Repo.pinnedTrain(ctx) }
    // Zug aus Widget/Benachrichtigung: Detailblatt oeffnen
    val openReq by UiRequests.openTrain
    LaunchedEffect(openReq, rel) {
        val q = openReq ?: return@LaunchedEffect
        val hit = rel?.rows?.firstOrNull { it.train == q.first && (q.second.isEmpty() || it.depPlanned == q.second) }
            ?: rel?.rows?.firstOrNull { it.train == q.first }
        if (hit != null) sheetRow = hit
        if (rel != null) UiRequests.openTrain.value = null
    }
    val rideDeparted = ride != null && de.rb64.live.ride.Analyzer.departures(ride.events).containsKey(ride.info.from)
    val lastRide by de.rb64.live.ride.RideStore.last.collectAsState()
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Notifier.onPinned(ctx, snap) }
    fun togglePin(r: Row) {
        if (r.train == pinned) Repo.unpin(ctx) else rel?.let { Repo.pin(ctx, r, it.id) }
        Rb64WidgetProvider.updateAll(ctx)
        if (r.train != pinned && !Notifier.permitted(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Notifier.onPinned(ctx, snap)
    }
    fun rideHere(r: Row) = ride?.info?.train == r.train
    fun canRide(r: Row) = ride == null && !r.cancelled && upcoming.indexOf(r) in 0..1

    Column(Modifier.fillMaxSize()) {
        val sameHome = favs.all { it.a == favs.first().a }
        TopBar(
            title = "${rel?.from ?: Line.name(route.first)} → ${rel?.to ?: Line.name(route.second)}",
            actions = {
                IconBtn(RbIcons.swap, "Richtung tauschen", { Repo.toggleDirection(ctx); Rb64WidgetProvider.updateAll(ctx) }, tint = Rb.c.onHeader)
                IconBtn(RbIcons.refresh, "Aktualisieren", { if (!refreshing) refresh(ctx) }, tint = Rb.c.onHeader, busy = refreshing)
            },
            below = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dim.s8),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        favs.forEachIndexed { i, f ->
                            HeaderChip(if (sameHome) Line.name(f.b) else "${Line.name(f.a)} – ${Line.name(f.b)}", i == favIdx,
                                first = i == 0, last = i == favs.size - 1) {
                                Repo.setFavIndex(ctx, i); Rb64WidgetProvider.updateAll(ctx)
                            }
                        }
                    }
                    Box(Modifier.size(Dim.touch).clickable(role = Role.Button, onClick = onAddRoute), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(Dim.rChip)).background(Rb.c.onHeader.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center) {
                            Icon(RbIcons.plus, "Strecke hinzufügen", tint = Rb.c.onHeader, modifier = Modifier.size(Dim.iconSmall))
                        }
                    }
                }
            },
        )

        // Ladekreis nur nach eigenem Herunterziehen (automatisches Aktualisieren zeigt nur den Kreis oben)
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) { if (!refreshing) pulled = false }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = pulled && refreshing,
            onRefresh = { pulled = true; refresh(ctx) },
            modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dim.s16, top = Dim.s16, end = Dim.s16, bottom = Dim.s24 + Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8),
        ) {
            if (ride != null) item {
                Banner(if (rideDeparted) "Fahrt läuft: ${ride.info.train} → ${ride.info.to}" else "Du wartest auf ${ride.info.train} → ${ride.info.to}",
                    kind = "info", sub = "Antippen zum Ansehen", onClick = onOpenRide)
            }
            // gerade angekommen: kurze Rueckmeldung mit Weg zur Auswertung
            val lr = lastRide
            if (ride == null && lr?.ended != null && now - lr.ended!! < 10 * 60_000) item {
                val arr = lr.events.lastOrNull { it.station == lr.info.to }?.arr
                Banner("Angekommen in ${lr.info.to}" + (arr?.let { " um ${HM.format(Date(it))}" } ?: ""), kind = "info",
                    sub = "Auswertung ansehen", onClick = { UiRequests.openRide.value = lr.info.id; onOpenRide() })
            }
            if (snap != null && stale) item {
                Banner("Datenstand ${HM.format(Date(snap.fresh))} (${ageText(age)})", kind = "bad",
                    sub = if (refreshing) "Frage den Server …" else "Zeiten können veraltet sein. Antippen zum Aktualisieren.",
                    onClick = { if (!refreshing) refresh(ctx) })
            }
            snap?.notes?.let { notes -> items(notes) { Banner(it) } }
            // neue App-Version auf der Webseite (nicht waehrend einer Fahrt)
            val upd = Updates.available(ctx)      // nur ein Lesen aus den Einstellungen
            if (upd != null && ride == null) item {
                Banner("Neue Version ${upd.name} verfügbar", kind = "info",
                    sub = (upd.notes.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + "Antippen zum Herunterladen",
                    onClick = { Updates.open(ctx, upd) })
            }

            // angehefteter Zug steht oben, solange er in der Liste ist
            val hero = upcoming.firstOrNull { it.train == pinned } ?: upcoming.firstOrNull()
            if (hero == null) item {
                RbCard {
                    Text(if (snap == null) "Noch keine Daten – einen Moment …" else "Keine Fahrten in den nächsten Stunden",
                        style = Ts.text, color = Rb.c.muted)
                }
            } else item(key = "hero") {
                HeroCard(hero, now, stale, rideHere(hero), canRide(hero),
                    onOpen = { sheetRow = hero }, onRide = { rel?.let { onStartRide(it, hero) } }, onStopRide = onStopRide,
                    pinned = hero.train == pinned, rideDeparted = rideDeparted)
            }

            val rest = upcoming.filter { it !== hero }
            if (rest.isNotEmpty() || past.isNotEmpty()) item {
                SectionHeader("Danach",
                    action = if (past.isEmpty()) null else if (showPast) "Frühere ausblenden" else "Frühere Züge",
                    actionIcon = if (showPast) RbIcons.chevronDown else RbIcons.chevronUp,
                    onAction = { showPast = !showPast })
            }
            if (showPast && past.isNotEmpty()) item { ListCard(past) { r -> TripRow(r, stale) { sheetRow = r } } }
            if (rest.isNotEmpty()) item { ListCard(rest) { r -> TripRow(r, stale, pinned = r.train == pinned) { sheetRow = r } } }

            // Datenstand: bis 5 min grau, ab 5 gelb, ab 15 Warnbanner (oben)
            item {
                Text(snap?.let { "Stand ${HM.format(Date(it.fresh))} · ${ageText(age)}" } ?: "",
                    style = Ts.small, color = if (age > 5 * 60_000) Rb.c.warn else Rb.c.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = Dim.s8))
            }
        }
        }
    }

    val sr = sheetRow
    if (sr != null && rel != null) {
        val cur = rel.rows.firstOrNull { it.train == sr.train && it.depPlanned == sr.depPlanned } ?: sr
        ModalBottomSheet(onDismissRequest = { sheetRow = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Rb.c.surface3, shape = RoundedCornerShape(topStart = Dim.rSheet, topEnd = Dim.rSheet)) {
            TripSheet(rel, cur, now, stale, weekday, rideHere(cur), canRide(cur),
                pinned = cur.train == pinned,
                onTogglePin = { togglePin(cur) },
                stand = snap?.let { "Stand ${HM.format(Date(it.fresh))} · ${ageText(it.ageMs(now))}" },
                refreshing = refreshing, onRefresh = { refresh(ctx) },
                onRide = { sheetRow = null; onStartRide(rel, cur) }, onStopRide = { sheetRow = null; onStopRide() })
        }
    }
}

/* ================================================================== Strecke live */
@Composable
private fun LiveScreen(tick: Int) {
    val ctx = LocalContext.current
    val snap = remember(tick) { Repo.load(ctx) }
    val range = remember(tick) { Repo.liveRange(ctx) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Strecke live", below = {
            Row(Modifier.height(Dim.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                StationPicker("von", range.first, exclude = range.second, onHeader = true) { Repo.setLiveRange(ctx, it, range.second) }
                Text("–", style = Ts.text, color = Rb.c.onHeader)
                StationPicker("bis", range.second, exclude = range.first, onHeader = true) { Repo.setLiveRange(ctx, range.first, it) }
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
            val map = snap?.liveMap(range.first, range.second)
            if (map == null || map.stations.size < 2) RbCard { Text("Noch keine Streckendaten", style = Ts.text, color = Rb.c.muted) }
            else {
                // "dein Zug": laufende Aufzeichnung, sonst der naechste Zug der aktiven Strecke
                val ride = de.rb64.live.ride.RideStore.state.value
                val mine = Repo.pinnedTrain(ctx) ?: ride?.info?.train
                    ?: Repo.currentRel(ctx, snap)?.let { Repo.visibleRows(it, false).firstOrNull()?.train }
                LiveMapCard(map, range.first, range.second, snap.crossings(range.first, range.second), mine)
            }
            // Darstellung der Linie direkt hier umschalten (gleicher Schalter wie in Einstellungen > Darstellung)
            val vis = de.rb64.live.ui.LocalVis.current
            RbCard(padding = Dim.s8) {
                ToggleRow("Gerade Linie", "Gleiche Abstände statt streckentreuer Verlauf", !vis.mapTrue) { on ->
                    Repo.setVisual(ctx, "map_true", !on)
                }
            }
            Spacer(Modifier.height(Dim.s24))
        }
    }
}

/* ================================================================== Fahrten */
@Composable
private fun RidesScreen(ride: de.rb64.live.ride.RideState?, onGoDepartures: () -> Unit) {
    val ctx = LocalContext.current
    val rides by de.rb64.live.ride.RideStore.history.collectAsState()
    var openId by UiRequests.openRide
    // Fahrt gerade beendet -> direkt ihre Auswertung zeigen
    var wasRiding by remember { mutableStateOf(ride != null) }
    LaunchedEffect(ride == null) {
        if (wasRiding && ride == null) openId = rides.firstOrNull()?.id
        wasRiding = ride != null
    }
    val open = remember(openId, rides) { openId?.let { de.rb64.live.ride.RideStore.load(ctx, it) } }
    BackHandler(enabled = ride == null && open != null) { openId = null }
    when {
        ride != null -> de.rb64.live.ride.RideScreen(ride)
        open != null -> de.rb64.live.ride.SummaryScreen(open, onBack = { openId = null })
        else -> de.rb64.live.ride.RideListScreen(rides, onOpen = { openId = it }, onGoDepartures = onGoDepartures)
    }
}

/* ================================================================== Einstellungen */
/**
 * Aufbau: Strecken (das Wichtigste) · Anzeige · Benachrichtigungen (Untermenue) · Mitfahren · Hilfe.
 * Selten Gebrauchtes liegt in Untermenues, damit die Hauptseite kurz bleibt.
 */
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var sub by rememberSaveable { mutableStateOf("") }
    when (sub) {
        "visuals" -> { BackHandler { sub = "" }; VisualsScreen(onBack = { sub = "" }); return }
        "notify" -> { BackHandler { sub = "" }; NotifySettingsScreen(onBack = { sub = "" }); return }
    }
    val muted = Rb.c.muted

    Column(Modifier.fillMaxSize()) {
        TopBar("Einstellungen", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8)) {

            // ------------------------------------------------ Meine Strecken
            var favs by remember { mutableStateOf(Repo.favs(ctx)) }
            fun saveFavs(n: List<Fav>) {
                favs = n; Repo.setFavs(ctx, n)
                de.rb64.live.ride.Fences.sync(ctx)
                Rb64WidgetProvider.updateAll(ctx)
            }
            SectionHeader("Meine Strecken")
            RbCard {
                Text("Links dein Start (z. B. zu Hause), rechts das Ziel. Die Rückfahrt gibt es automatisch dazu. " +
                    "Die Reihenfolge gilt für die Knöpfe auf der Abfahrten-Seite.", style = Ts.small, color = muted)
                favs.forEachIndexed { i, f ->
                    Spacer(Modifier.height(Dim.s8))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton)).background(Rb.c.background)
                        .padding(start = Dim.s8), verticalAlignment = Alignment.CenterVertically) {
                        StationPicker(null, f.a, exclude = f.b) { v -> saveFavs(favs.toMutableList().also { it[i] = f.copy(a = v) }) }
                        Text(" ↔ ", style = Ts.text, color = muted)
                        StationPicker(null, f.b, exclude = f.a) { v -> saveFavs(favs.toMutableList().also { it[i] = f.copy(b = v) }) }
                        Spacer(Modifier.weight(1f))
                        // Reihenfolge und Entfernen im Menue, damit jede Strecke eine Zeile bleibt
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconBtn(RbIcons.more, "Mehr zu dieser Strecke", { menu = true }, tint = muted)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Rb.c.surface3) {
                                if (i > 0) DropdownMenuItem(text = { Text("Nach oben", style = Ts.text) },
                                    leadingIcon = { Icon(RbIcons.chevronUp, null, tint = Rb.c.primary) },
                                    onClick = { menu = false; favs = Repo.moveFav(ctx, i, -1); Rb64WidgetProvider.updateAll(ctx) })
                                if (i < favs.size - 1) DropdownMenuItem(text = { Text("Nach unten", style = Ts.text) },
                                    leadingIcon = { Icon(RbIcons.chevronDown, null, tint = Rb.c.primary) },
                                    onClick = { menu = false; favs = Repo.moveFav(ctx, i, 1); Rb64WidgetProvider.updateAll(ctx) })
                                if (favs.size > 1) DropdownMenuItem(text = { Text("Entfernen", style = Ts.text, color = Rb.c.bad) },
                                    leadingIcon = { Icon(RbIcons.delete, null, tint = Rb.c.bad) },
                                    onClick = { menu = false; saveFavs(favs.filterIndexed { k, _ -> k != i }) })
                            }
                        }
                    }
                }
                if (favs.size < 4) {
                    Spacer(Modifier.height(Dim.s8))
                    RbButton("Strecke hinzufügen", {
                        // Vorschlag: gleiches "zu Hause", erstes Ziel, das noch keine Strecke hat
                        val home = favs.firstOrNull()?.a ?: 3
                        val used = favs.filter { it.a == home }.map { it.b }.toSet() + home
                        saveFavs(favs + Fav(home, Line.stations.first { it.seq !in used }.seq))
                    }, icon = RbIcons.plus, primary = false)
                }
            }

            // ------------------------------------------------ Anzeige
            SectionHeader("Anzeige")
            var auto by remember { mutableStateOf(Repo.autoReturn(ctx)) }
            var hour by remember { mutableIntStateOf(Repo.switchHour(ctx)) }
            var live by remember { mutableStateOf(Repo.liveRange(ctx)) }
            val vis = de.rb64.live.ui.LocalVis.current
            val modeName = mapOf("system" to "wie System", "light" to "hell", "dark" to "dunkel")[Repo.themeMode(ctx)] ?: "wie System"
            RbCard(padding = Dim.s8) {
                ToggleRow("Rückfahrt automatisch", "Ab $hour Uhr zeigen App und Widget die Rückrichtung. Tauschen gilt bis zum nächsten Wechsel.",
                    auto) { on -> auto = on; Repo.setAutoReturn(ctx, on, hour); Rb64WidgetProvider.updateAll(ctx) }
                if (auto) Row(Modifier.padding(horizontal = Dim.s8), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wechsel um", style = Ts.text, color = muted, modifier = Modifier.weight(1f))
                    TimeChip("ab", hour * 60, true) {
                        TimePickerDialog(ctx, { _, h, _ -> hour = h; Repo.setAutoReturn(ctx, true, h); Rb64WidgetProvider.updateAll(ctx) },
                            hour, 0, true).apply { setTitle("Rückrichtung ab (volle Stunde)") }.show()
                    }
                }
                Divider()
                Column(Modifier.padding(horizontal = Dim.s8, vertical = Dim.s8)) {
                    Text("Strecke live zeigen", style = Ts.text)
                    Text("Ausschnitt der Linie im Tab „Strecke“", style = Ts.small, color = muted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                        StationPicker("von", live.first, exclude = live.second) { Repo.setLiveRange(ctx, it, live.second); live = Repo.liveRange(ctx) }
                        Text("–", style = Ts.text, color = muted)
                        StationPicker("bis", live.second, exclude = live.first) { Repo.setLiveRange(ctx, live.first, it); live = Repo.liveRange(ctx) }
                    }
                }
                Divider()
                NavRow("Farbschema und Effekte", "Farbschema $modeName · ${vis.countOn} von ${VISUAL_OPTIONS.size} Effekten an") { sub = "visuals" }
            }

            // ------------------------------------------------ Benachrichtigungen (Untermenue)
            SectionHeader("Benachrichtigungen")
            val ns = remember { Settings.load(ctx) }
            val nSummary = if (!ns.enabled || !Notifier.permitted(ctx)) "Aus"
                else "${ns.rels.size} Richtung${if (ns.rels.size == 1) "" else "en"} · " +
                    (if (ns.onlyCancel) "nur bei Ausfall" else "ab +${ns.threshold} min") +
                    (if (Repo.pinNotify(ctx)) " · angehefteter Zug" else "")
            RbCard(padding = Dim.s8) {
                NavRow("Verspätungen und Ausfälle", nSummary) { sub = "notify" }
            }

            // ------------------------------------------------ Mitfahren
            SectionHeader("Mitfahren")
            var fenceOn by remember { mutableStateOf(de.rb64.live.ride.Fences.enabled(ctx) && de.rb64.live.ride.Fences.hasPermissions(ctx)) }
            fun setFence(on: Boolean) {
                Repo.prefs(ctx).edit().putBoolean("fence_enabled", on).apply()
                fenceOn = on && de.rb64.live.ride.Fences.hasPermissions(ctx)
                de.rb64.live.ride.Fences.sync(ctx)
            }
            val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> setFence(ok) }
            val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else setFence(false)
            }
            RbCard(padding = Dim.s8) {
                ToggleRow("Am Bahnhof „Ich fahre mit“ vorschlagen",
                    "An den Bahnhöfen deiner Strecken. Braucht Standort „Immer zulassen“. Der Standort wird dabei nicht gespeichert.",
                    fenceOn) { on ->
                    when {
                        !on -> setFence(false)
                        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                            askFine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else -> setFence(true)
                    }
                }
            }

            // ------------------------------------------------ Hintergrund (nur wo noetig, z. B. Samsung)
            if (remember { Battery.relevant(ctx) }) {
                SectionHeader("Hintergrund")
                BatteryCard()
            }

            // ------------------------------------------------ Hilfe (ganz unten)
            SectionHeader("Hilfe")
            RbCard(padding = Dim.s8) {
                NavRow("Einführung anzeigen", "Strecke wählen, Zeiten lesen und alle Funktionen") {
                    Repo.prefs(ctx).edit().putBoolean("onboarded", false).apply()
                }
                var updMsg by remember { mutableStateOf<String?>(null) }
                val upd = Updates.available(ctx)
                NavRow(if (upd != null) "Version ${upd.name} herunterladen" else "Nach Updates suchen",
                    updMsg ?: upd?.notes?.ifBlank { null } ?: "Installiert: ${BuildConfig.VERSION_NAME}") {
                    if (upd != null) Updates.open(ctx, upd)
                    else { updMsg = "Suche …"; Updates.check(ctx, force = true) { ok ->
                        updMsg = when {
                            !ok -> "Keine Verbindung zur Webseite"
                            Updates.available(ctx) != null -> null
                            else -> "Du hast die neueste Version (${BuildConfig.VERSION_NAME})"
                        }
                    } }
                }
                var feedback by remember { mutableStateOf(false) }
                NavRow("Feedback senden", "Fehler, Ideen, Lob – anonym, ohne Konto") { feedback = true }
                if (feedback) FeedbackDialog(context = null) { feedback = false }
            }

            // ------------------------------------------------ Sicherung
            var backupMsg by remember { mutableStateOf<String?>(null) }
            val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) backupMsg = runCatching { Backup.write(ctx, uri); "Gesichert: Einstellungen und " +
                    "${de.rb64.live.ride.RideStore.history.value.size} Fahrt(en)." }
                    .getOrElse { "Sichern fehlgeschlagen: ${it.message}" }
            }
            val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) backupMsg = runCatching { Backup.read(ctx, uri).let { r ->
                    "Wiederhergestellt: ${r.prefs} Einstellungen und ${r.rides} Fahrt(en)." } }
                    .getOrElse { "Wiederherstellen fehlgeschlagen: ${it.message}" }
            }
            SectionHeader("Sicherung")
            RbCard(padding = Dim.s8) {
                NavRow("Daten sichern", "Einstellungen und Fahrten als Datei, z. B. für ein neues Handy") {
                    saveBackup.launch("rb64-sicherung-${SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())}.json")
                }
                NavRow("Sicherung wiederherstellen", "Vorhandene Fahrten bleiben, Einstellungen werden übernommen") {
                    openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }
                backupMsg?.let { Text(it, style = Ts.small, color = Rb.c.primary, modifier = Modifier.padding(Dim.s8)) }
            }

            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            val shortId = remember { Backup.shortId() }
            Text("RB64 Live" + (version?.let { " · Version $it" } ?: "") + (shortId?.let { " · Kennung $it" } ?: ""),
                style = Ts.small, color = muted,
                modifier = Modifier.fillMaxWidth().padding(top = Dim.s8), textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))      // frei vom erhobenen Abfahrten-Knopf
        }
    }
}

/** Zeile, die ein Untermenue oeffnet: Titel, Zusammenfassung, Pfeil. */
@Composable
private fun NavRow(title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dim.row).clip(RoundedCornerShape(Dim.rButton)).clickable(onClick = onClick)
        .padding(horizontal = Dim.s8, vertical = Dim.s8), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Ts.text)
            if (sub != null) Text(sub, style = Ts.small, color = Rb.c.muted)
        }
        Icon(RbIcons.arrowRight, null, tint = Rb.c.muted, modifier = Modifier.size(Dim.icon))
    }
}

/** Duenne Trennlinie zwischen Zeilen einer Karte. */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = Dim.s8, vertical = Dim.s4).height(1.dp).background(Rb.c.line))

/* ================================================================== Einstellungen › Benachrichtigungen */
@Composable
private fun NotifySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Settings.load(ctx)) }
    var muteTick by remember { mutableIntStateOf(0) }
    fun upd(n: Settings) { s = n; n.save(ctx) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        upd(s.copy(enabled = granted))
    }
    val muted = Rb.c.muted

    /** Beginn oder Ende des Zeitfensters einer Strecke per Uhr waehlen. */
    fun pickTime(id: String, start: Boolean) {
        val w = s.windows[id] ?: return
        val cur = if (start) w.from else w.to
        TimePickerDialog(ctx, { _, h, m ->
            val v = h * 60 + m
            val nw = if (start) Window(v, maxOf(v, w.to)) else Window(minOf(w.from, v), v)
            upd(s.copy(windows = s.windows + (id to nw)))
        }, cur / 60, cur % 60, true).apply { setTitle(if (start) "Benachrichtigen ab" else "Benachrichtigen bis") }.show()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Benachrichtigungen", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8)) {

            SectionHeader("Deine Strecken")
            RbCard(padding = Dim.s8) {
                ToggleRow("Benachrichtigungen", "Nur für deine Strecken in ihrem Zeitfenster, kurz vor Abfahrt", s.enabled) { on ->
                    if (on && !Notifier.permitted(ctx)) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else upd(s.copy(enabled = on))
                }
            }
            Repo.directedRels(ctx).forEach { (id, name) ->
                val isMuted = remember(muteTick) { Notifier.isMuted(ctx, id) }
                val on = s.enabled && id in s.rels
                val w = s.windows[id]
                RbCard(padding = Dim.s12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = Ts.textStrong, color = if (s.enabled) Rb.c.onSurface else muted, modifier = Modifier.weight(1f))
                        Switch(checked = id in s.rels, enabled = s.enabled,
                            onCheckedChange = { v -> upd(s.copy(rels = if (v) s.rels + id else s.rels - id)) })
                    }
                    if (w != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                        TimeChip("von", w.from, on) { pickTime(id, true) }
                        TimeChip("bis", w.to, on) { pickTime(id, false) }
                    }
                    if (isMuted) Text("Heute stumm („Zug genommen“) · aufheben", style = Ts.smallStrong, color = Rb.c.primary,
                        modifier = Modifier.heightIn(min = Dim.touch).clickable { Notifier.unmute(ctx, id); muteTick++ }.padding(top = Dim.s12))
                }
            }

            SectionHeader("Wann melden")
            RbCard(padding = Dim.s8) {
                ToggleRow("Nur bei Ausfall", "Verspätungen ignorieren", s.onlyCancel, s.enabled) { upd(s.copy(onlyCancel = it)) }
                SliderRow("Ab Verspätung", "+${s.threshold} min", s.threshold, 2..20, s.enabled && !s.onlyCancel) { upd(s.copy(threshold = it)) }
                SliderRow("Nur vor Abfahrt", "${s.leadMin} min vorher", s.leadMin, 10..120, s.enabled) { upd(s.copy(leadMin = it)) }
                ToggleRow("Nur Montag bis Freitag", null, s.weekdaysOnly, s.enabled) { upd(s.copy(weekdaysOnly = it)) }
                ToggleRow("Vibration", "Sonst lautlos (nur in der Leiste)", s.vibrate, s.enabled) { upd(s.copy(vibrate = it)) }
            }

            SectionHeader("Angehefteter Zug")
            var pinLock by remember { mutableStateOf(Repo.pinLock(ctx)) }
            var pinNotify by remember { mutableStateOf(Repo.pinNotify(ctx)) }
            var pinThr by remember { mutableIntStateOf(Repo.pinThreshold(ctx)) }
            RbCard(padding = Dim.s8) {
                ToggleRow("Auf dem Sperrbildschirm zeigen", "Abfahrt, Gleis und Grund, still aktualisiert", pinLock) {
                    pinLock = it; Repo.prefs(ctx).edit().putBoolean("pin_lock", it).apply(); Notifier.onPinned(ctx, Repo.load(ctx))
                }
                ToggleRow("Bei Änderungen benachrichtigen", "Mit Vibration, auch bei Ausfall", pinNotify) {
                    pinNotify = it; Repo.prefs(ctx).edit().putBoolean("pin_notify", it).apply()
                }
                SliderRow("Ab Änderung um", "$pinThr min", pinThr, 1..10, pinNotify) {
                    pinThr = it; Repo.prefs(ctx).edit().putInt("pin_threshold", it).apply()
                }
            }

            Text("Pro Zug gibt es höchstens eine Benachrichtigung, die aktualisiert wird: bei Ausfall, " +
                    "ab der Schwelle, bei jeder weiteren Verschlechterung um 5 min und wenn er wieder pünktlich ist. " +
                    "„Zug genommen“ schaltet die Strecke für den Rest des Tages stumm.",
                style = Ts.small, color = muted, modifier = Modifier.padding(top = Dim.s8))
            RbButton("Android-Einstellungen für Benachrichtigungen", {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
            }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

/* ================================================================== Einstellungen › Darstellung */
@Composable
private fun VisualsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Repo.themeMode(ctx)) }
    var vis by remember { mutableStateOf(Repo.visuals(ctx)) }
    fun reload() { vis = Repo.visuals(ctx); Rb64WidgetProvider.updateAll(ctx) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Darstellung", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
            SectionHeader("Farbschema")
            RbCard(padding = Dim.s4) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("system" to "Wie System", "light" to "Hell", "dark" to "Dunkel").forEach { (k, label) ->
                        val on = mode == k
                        Box(Modifier.weight(1f).height(Dim.touch).clip(RoundedCornerShape(Dim.rButton))
                            .background(if (on) Rb.c.primaryTint else Color.Transparent)
                            .clickable(role = Role.RadioButton) { mode = k; Repo.setThemeMode(ctx, k) },
                            contentAlignment = Alignment.Center) {
                            Text(label, style = if (on) Ts.textStrong else Ts.text, color = if (on) Rb.c.primary else Rb.c.onSurface)
                        }
                    }
                }
            }
            Text("Das Widget folgt immer dem Farbschema des Systems.", style = Ts.small, color = Rb.c.muted)

            SectionHeader("Effekte")
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                RbButton("Alle an", { Repo.setAllVisuals(ctx, true); reload() }, Modifier.weight(1f), primary = false,
                    enabled = vis.countOn < VISUAL_OPTIONS.size)
                RbButton("Alle aus", { Repo.setAllVisuals(ctx, false); reload() }, Modifier.weight(1f), primary = false,
                    enabled = vis.countOn > 0)
            }
            RbCard(padding = Dim.s8) {
                VISUAL_OPTIONS.forEach { (k, title, sub) ->
                    ToggleRow(title, sub, vis.on(k)) { on -> Repo.setVisual(ctx, k, on); reload() }
                }
            }
        }
    }
}

/** Haltestelle der RB64 waehlen (Chip mit Namen, Liste klappt auf). */
@Composable
internal fun StationPicker(label: String?, seq: Int, exclude: Int? = null, onHeader: Boolean = false, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fg = if (onHeader) Rb.c.onHeader else Rb.c.primary
    val bg = if (onHeader) Rb.c.onHeader.copy(alpha = 0.16f) else Rb.c.primaryTint
    Box {
        Box(Modifier.height(Dim.touch).clickable(role = Role.DropdownList) { open = true }, contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(Dim.rChip)).background(bg).padding(horizontal = Dim.s12, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (label != null) Text("$label ", style = Ts.small, color = fg.copy(alpha = 0.8f))
                Text(Line.name(seq), style = Ts.smallStrong, color = fg)
                Icon(RbIcons.chevronDown, null, tint = fg, modifier = Modifier.padding(start = Dim.s4).size(Dim.iconSmall))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Rb.c.surface3) {
            Line.stations.filter { it.seq != exclude }.forEach { st ->
                DropdownMenuItem(text = { Text(st.name, style = if (st.seq == seq) Ts.textStrong else Ts.text) },
                    onClick = { open = false; onPick(st.seq) })
            }
        }
    }
}

/** Knopf mit Uhrzeit fuer das Zeitfenster ("von 06:00"). */
@Composable
private fun TimeChip(label: String, minutes: Int, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) Rb.c.primary else Rb.c.muted
    Box(Modifier.height(Dim.touch).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(Dim.rChip)).background(if (enabled) Rb.c.primaryTint else Rb.c.background)
            .padding(horizontal = Dim.s12, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label ", style = Ts.small, color = Rb.c.muted)
            Text("%02d:%02d".format(minutes / 60, minutes % 60), style = Ts.textStrong, color = fg)
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dim.touch).clip(RoundedCornerShape(Dim.rButton))
        .clickable(enabled = enabled, role = Role.Switch) { onChange(!value) }.padding(horizontal = Dim.s8, vertical = Dim.s8),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Ts.text, color = if (enabled) Rb.c.onSurface else Rb.c.muted)
            if (sub != null) Text(sub, style = Ts.small, color = Rb.c.muted)
        }
        Spacer(Modifier.width(Dim.s8))
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(title: String, valueText: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dim.s8, vertical = Dim.s4)) {
        Row {
            Text(title, style = Ts.text, modifier = Modifier.weight(1f), color = if (enabled) Rb.c.onSurface else Rb.c.muted)
            Text(valueText, style = Ts.text, color = Rb.c.muted)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled)
    }
}
