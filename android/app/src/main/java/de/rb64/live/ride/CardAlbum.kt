package de.rb64.live.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.rb64.live.ui.Dim
import de.rb64.live.ui.IconBtn
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.TextAction
import de.rb64.live.ui.Ts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kartenalbum im Stempelheft: alle gesammelten Fahrkarten als Raster (wie die Sammlung in digitalen Kartenspielen).
 * - Neueste zuerst mit Monatstrennern, oder nach Seltenheit.
 * - Filter erscheinen nur fuer Arten, von denen man schon eine Karte hat - nichts verraet, was es noch gibt.
 * - Antippen oeffnet die Karte gross (mit allen Gesten); hoch/runter wischen blaettert, seitlich dreht die Karte.
 */
private data class AlbumFilter(val id: String, val label: String, val test: (Ticket) -> Boolean)

private val FILTERS = listOf(
    AlbumFilter("all", "Alle") { true },
    AlbumFilter("var", "Varianten") { it.variant != null },
    AlbumFilter("first", "1. Klasse") { it.cls == TicketClass.FIRST },
    AlbumFilter("special", "Sonderfahrt") { it.cls == TicketClass.SPECIAL },
    AlbumFilter("doodle", "Kritzelei") { it.doodle },
    AlbumFilter("wear", "Mit Spuren") { it.wear != Wear.NONE },
)

/** Grobe Seltenheit fuer "Seltenste zuerst": Variante vor Spruch vor Klasse vor Kleinigkeiten. */
private fun score(t: Ticket): Int =
    (t.variant?.rarity?.ordinal?.plus(1) ?: 0) * 100 + t.spruchRarity.ordinal * 10 +
        (when (t.cls) { TicketClass.SPECIAL -> 8; TicketClass.FIRST -> 5; else -> 0 }) +
        (if (t.doodle) 3 else 0) + (if (t.wear != Wear.NONE) 2 else 0)

private val albumCache = java.util.concurrent.ConcurrentHashMap<String, Ticket>()
@Volatile private var albumSig: Pair<Int, String?>? = null

private val MONTH = SimpleDateFormat("MMMM yyyy", Locale.GERMANY)
private val DAY = SimpleDateFormat("dd.MM.", Locale.GERMANY)

@Composable
fun CardAlbum(rides: List<RideMeta>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val all by produceState<List<Ticket>?>(null, rides) {
        value = withContext(Dispatchers.IO) {
            // berechnete Karten merken (solange sich die Fahrtenliste nicht aendert): zweites Oeffnen ist sofort da
            val sig = rides.size to rides.firstOrNull()?.id
            if (albumSig != sig) { albumCache.clear(); albumSig = sig }
            rides.mapNotNull { m ->
                albumCache[m.id] ?: RideStore.load(ctx, m.id)?.let { s -> runCatching { Tickets.of(ctx, s, rides) }.getOrNull() }
                    ?.also { albumCache[m.id] = it }
            }
        }
    }
    var sort by rememberSaveable { mutableIntStateOf(0) }          // 0 = neueste, 1 = seltenste
    var filter by rememberSaveable { mutableStateOf("all") }
    var open by remember { mutableStateOf<Int?>(null) }
    val list = all
    if (list == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Rb.c.primary) }
        return
    }
    val filters = FILTERS.filter { f -> f.id == "all" || list.any(f.test) }
    val active = filters.firstOrNull { it.id == filter } ?: filters.first()
    val shown = list.filter(active.test).let { l ->
        if (sort == 1) l.sortedWith(compareByDescending<Ticket> { score(it) }.thenByDescending { it.started })
        else l.sortedByDescending { it.started }
    }

    LazyVerticalGrid(GridCells.Fixed(3), modifier.fillMaxWidth(), contentPadding = PaddingValues(Dim.s16),
        horizontalArrangement = Arrangement.spacedBy(Dim.s8), verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                    filters.forEach { f ->
                        val n = list.count(f.test)
                        Chip("${f.label} $n", f.id == active.id) { filter = f.id }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sortieren:", style = Ts.small, color = Rb.c.muted)
                    Chip("Neueste", sort == 0, quiet = true) { sort = 0 }
                    Chip("Seltenste", sort == 1, quiet = true) { sort = 1 }
                }
            }
        }
        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Nach deiner ersten Fahrt liegt hier deine erste Fahrkarte.", style = Ts.small, color = Rb.c.muted)
        }
        if (sort == 0) {
            // Monatstrenner wie Seiten im Album
            shown.groupBy { MONTH.format(Date(it.started)) }.forEach { (month, cards) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "m_$month") {
                    Text("$month · ${cards.size} ${if (cards.size == 1) "Karte" else "Karten"}", style = Ts.smallStrong,
                        color = Rb.c.muted, modifier = Modifier.padding(top = Dim.s4))
                }
                items(cards, key = { it.id }) { t -> Mini(t) { open = shown.indexOf(t) } }
            }
        } else items(shown, key = { it.id }) { t -> Mini(t) { open = shown.indexOf(t) } }
    }
    open?.let { start -> CardViewer(shown, start) { open = null } }
}

@Composable
private fun Chip(text: String, on: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    Text(text, style = Ts.smallStrong, color = if (on) Rb.c.onPrimary else Rb.c.primary,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) Rb.c.primary else if (quiet) Color.Transparent else Rb.c.primaryTint)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Mini(t: Ticket, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            TicketMini(t)
            // faengt alle Gesten ab: im Album wird nur angetippt, gelocht und gedreht wird in der grossen Ansicht
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick))
        }
        Text(DAY.format(Date(t.started)), style = Ts.small, color = Rb.c.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Grosse Ansicht: eine Karte je Seite, hoch/runter blaettern (seitlich wischen dreht die Karte um). */
@Composable
private fun CardViewer(cards: List<Ticket>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (cards.size - 1).coerceAtLeast(0))) { cards.size }
    val scope = rememberCoroutineScope()
    var share by remember { mutableStateOf<Ticket?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        de.rb64.live.ui.DialogEdgeToEdge(lightIcons = Rb.c.dark)
        Column(Modifier.fillMaxSize().background(Rb.c.background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dim.s8, vertical = Dim.s4), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(RbIcons.close, "Schließen", onClose, tint = Rb.c.muted)
                Text("${pager.currentPage + 1} von ${cards.size}", style = Ts.smallStrong, color = Rb.c.muted, modifier = Modifier.weight(1f))
                IconBtn(RbIcons.chevronUp, "Vorherige Karte", {
                    scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }, tint = Rb.c.primary)
                IconBtn(RbIcons.chevronDown, "Nächste Karte", {
                    scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(cards.size - 1)) } }, tint = Rb.c.primary)
            }
            VerticalPager(pager, Modifier.weight(1f), key = { cards[it].id }) { p ->
                val t = cards[p]
                Column(Modifier.fillMaxSize().padding(horizontal = Dim.s16), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    TicketCard(t)
                    Spacer(Modifier.height(Dim.s8))
                    Text("${DAY.format(Date(t.started))} · ${t.from} → ${t.to}", style = Ts.small, color = Rb.c.muted)
                    TextAction("Karte teilen", null, { share = t })
                }
            }
        }
    }
    share?.let { t -> TicketShareSheet(t) { share = null } }
}
