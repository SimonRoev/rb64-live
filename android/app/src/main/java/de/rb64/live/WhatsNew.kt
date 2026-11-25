package de.rb64.live

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import kotlinx.coroutines.launch

/**
 * "Neu in dieser Version": nach einem Update einmal zeigen, was seit der zuletzt gesehenen Version dazukam.
 * Neue Version = neuer Eintrag oben in [RELEASES] (versionCode wie in build.gradle).
 * Versteckte Kleinigkeiten (Easter Eggs) werden hier bewusst nicht verraten.
 */
object WhatsNew {
    data class Item(val icon: ImageVector, val title: String, val text: String)
    data class Release(val code: Int, val name: String, val items: List<Item>)

    val RELEASES: List<Release> = listOf(
        Release(16, "0.16", listOf(
            Item(RbIcons.rides, "Alle Karten auf einen Blick",
                "Im Stempelheft unter „Karten“: alle deine Fahrkarten, nach Monat oder Seltenheit sortiert. Antippen zeigt sie groß, " +
                    "hoch und runter wischen blättert."),
            Item(RbIcons.play, "Automatisch aufzeichnen",
                "Unter Einstellungen › Mitfahren: Bist du am Bahnhof und dein angehefteter Zug fährt bald, startet die Aufzeichnung " +
                    "von selbst. Steigst du nicht ein, wird sie verworfen."),
            Item(RbIcons.lock, "Sparsamer mit deinen Daten",
                "Hochgeladen wird nur noch die eigentliche Zugfahrt, nicht der Weg zum Bahnhof. „Überall löschen“ klappt " +
                    "auch nach einem Handywechsel, und beim Wiederherstellen einer Sicherung musst du Live-Teilen und " +
                    "Automatik selbst wieder einschalten."),
            Item(RbIcons.live, "Dein Zug live bis zum Ziel",
                "Während du mitfährst, zeigt die Strecke deinen Zug jetzt bis zum Ausstieg nach deinem GPS – nur auf deinem Handy."),
            Item(RbIcons.check, "Kleiner und schlauer",
                "Die App ist nur noch ein Fünftel so groß. Hast du beim Mitfahren aus Versehen die falsche Richtung gewählt, " +
                    "merkt die Aufzeichnung das jetzt und stellt auf den richtigen Zug um."),
        )),
        Release(15, "0.15", listOf(
            Item(RbIcons.trend, "Dein Monatsrückblick",
                "Bist du in einem Monat mindestens 8-mal mitgefahren, kommt am 1. dein Rückblick als kleine Story: Fahrten, Kilometer, " +
                    "Kreuzungen und deine Lieblingszeit. Du findest ihn auch oben unter „Fahrten“."),
            Item(RbIcons.announce, "Karte und Rückblick teilen",
                "Unter der Fahrkarte und oben im Rückblick: „Teilen“ macht ein Bild daraus. Uhrzeiten, Datum, Zug und Platz " +
                    "sind nur dabei, wenn du „Details zeigen“ einschaltest."),
            Item(RbIcons.rides, "Mehr zum Sammeln",
                "Neue seltene Kartenvarianten, ab und zu eine Kritzelei und ein paar Überraschungen im Stempelheft."),
            Item(RbIcons.warning, "Fahrgastrechte",
                "Ab 20 Minuten Verspätung liegt der Karte ein Hinweis bei – jetzt mit Link zum Entschädigungsantrag der DB " +
                    "und wie lange du ihn noch stellen kannst."),
        )),
        Release(14, "0.14", listOf(
            Item(RbIcons.rides, "Deine Fahrkarte",
                "Nach jeder Fahrt gibt es eine Fahrkarte zum Sammeln – mit Platzreservierung, Stempel und einem Spruch. " +
                    "Manche Karten sind seltener als andere."),
            Item(RbIcons.check, "Stempelheft",
                "Unter „Fahrten“: ein Stempel für jeden Bahnhof, an dem du mitgefahren bist, dazu Sonderstempel und Sprüche. " +
                    "Auf Karte und Heft gibt es ein paar Kleinigkeiten zu entdecken."),
            Item(RbIcons.route, "Strecke eines Zuges ansehen",
                "In den Details eines Zuges zeigt „Auf der Strecke ansehen“ groß, wo er gerade ist – deine Einstellungen bleiben, wie sie sind."),
            Item(RbIcons.clock, "Kopfzeile nach Tageszeit",
                "Morgens warm, abends violett, nachts mit Sternen – nach Sonnenauf- und -untergang in Münster. Abschaltbar unter Anzeige."),
            Item(RbIcons.pin, "Angehefteter Zug",
                "„Ich fahre mit“ erscheint erst kurz vor der Abfahrt. Bist du am Bahnhof, wird dein Zug hervorgehoben statt einer zweiten Meldung. " +
                    "Tippen auf eine Meldung öffnet jetzt den richtigen Zug."),
        )),
        Release(13, "0.13", listOf(
            Item(RbIcons.live, "Live-Position teilen",
                "Wer beim Mitfahren seine Position teilt, sieht auch die Züge anderer live. Einstellbar unter „Mitfahren“."),
            Item(RbIcons.trend, "Prognose-Check",
                "Bei angehefteten Zügen siehst du hinterher, wie gut die Prognose gegenüber der DB lag."),
        )),
    )

    /** Vor dieser Funktion gab es keine Markierung - wer schon eingerichtet war, kommt mindestens von 0.13. */
    private const val BEFORE = 13

    fun pending(ctx: Context): List<Release> {
        val p = Repo.prefs(ctx)
        if (!p.getBoolean("onboarded", false)) return emptyList()
        val seen = p.getInt("news_seen", BEFORE)
        return RELEASES.filter { it.code in (seen + 1)..BuildConfig.VERSION_CODE }
    }

    fun markSeen(ctx: Context) = Repo.prefs(ctx).edit().putInt("news_seen", BuildConfig.VERSION_CODE).apply()

    /** Aus den Einstellungen: die Neuerungen der aktuellen Version noch einmal zeigen. */
    fun showAgain(ctx: Context) = Repo.prefs(ctx).edit().putInt("news_seen", BuildConfig.VERSION_CODE - 1).apply()
}

/** Wie die Einfuehrung: eine Seite je Neuerung, neueste Version zuerst. */
@Composable
fun WhatsNewScreen(releases: List<WhatsNew.Release>, onDone: () -> Unit) {
    val pages = releases.flatMap { r -> r.items.map { r to it } }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Rb.c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Dim.s16), verticalAlignment = Alignment.CenterVertically) {
            Text("Neu in RB64 Live", style = Ts.textStrong, color = Rb.c.primary, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages.size - 1) Text("Überspringen", style = Ts.textStrong, color = Rb.c.muted,
                modifier = Modifier.clip(RoundedCornerShape(Dim.rButton)).clickable { onDone() }.padding(Dim.s12))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { p ->
            val (rel, item) = pages[p]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dim.s24, vertical = Dim.s8),
                verticalArrangement = Arrangement.spacedBy(Dim.s16)) {
                Spacer(Modifier.height(Dim.s8))
                Text("Version ${rel.name}", style = Ts.smallStrong, color = Rb.c.muted)
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(Dim.rCard)).background(Rb.c.primaryTint), contentAlignment = Alignment.Center) {
                    Icon(item.icon, null, tint = Rb.c.primary, modifier = Modifier.size(28.dp))
                }
                Text(item.title, style = Ts.big, color = Rb.c.onSurface)
                Text(item.text, style = Ts.text, color = Rb.c.muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(Dim.s16), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pages.size > 1) repeat(pages.size) { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "punkt")
                    Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) Rb.c.primary else Rb.c.line))
                }
            }
            val last = pager.currentPage >= pages.size - 1
            RbButton(if (last) "Los geht's" else "Weiter", {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, icon = if (last) null else RbIcons.arrowRight)
        }
    }
}
