package de.rb64.live

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.IconBtn
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Eine Benachrichtigungs-Regel: entweder fuer eine Strecke (alle Zuege im Zeitfenster) oder fuer einen
 * einzelnen Zug (Zugnummer, ersatzweise gleiche Abfahrtszeit am Start). Beliebig viele moeglich.
 * days = Calendar.DAY_OF_WEEK-Werte (So=1 ... Sa=7).
 */
data class NotifyRule(
    val id: String, val from: Int, val to: Int,
    val window: Window, val days: Set<Int>,
    val train: String? = null, val time: String? = null,
    val on: Boolean = true,
) {
    val relId get() = Snapshot.relId(from, to)
    val isTrain get() = train != null

    fun matches(r: Row): Boolean =
        if (train != null) r.train == train || (time != null && r.depPlanned == time)
        else Rules.minutes(r.depPlanned)?.let { window.contains(it) } == true

    fun title(): String = (if (train != null) "Zug ${time ?: ""} · " else "") + "${Line.name(from)} → ${Line.name(to)}"
    fun subtitle(): String = Rules.daysText(days) + if (train == null) " · $window" else " · Zug $train"
}

object Rules {
    val WEEKDAYS = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    val ALL_DAYS = (1..7).toSet()
    private val ORDER = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
    private val SHORT = mapOf(Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Di", Calendar.WEDNESDAY to "Mi",
        Calendar.THURSDAY to "Do", Calendar.FRIDAY to "Fr", Calendar.SATURDAY to "Sa", Calendar.SUNDAY to "So")

    fun minutes(hm: String): Int? = hm.split(":").takeIf { it.size == 2 }
        ?.let { runCatching { it[0].toInt() * 60 + it[1].toInt() }.getOrNull() }

    fun daysText(d: Set<Int>): String = when (d) {
        ALL_DAYS -> "täglich"
        WEEKDAYS -> "Mo–Fr"
        setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "Sa/So"
        else -> ORDER.filter { it in d }.joinToString(" ") { SHORT[it]!! }.ifBlank { "nie" }
    }

    // ---------------------------------------------------------------- Speichern
    fun load(ctx: Context): List<NotifyRule> {
        val p = Repo.prefs(ctx)
        val raw = p.getString("n_rules", null) ?: return migrate(ctx).also { save(ctx, it) }
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } } }
            .getOrElse { migrate(ctx) }
    }

    fun save(ctx: Context, rules: List<NotifyRule>) {
        Repo.prefs(ctx).edit().putString("n_rules", JSONArray().apply { rules.forEach { put(toJson(it)) } }.toString()).apply()
    }

    private fun toJson(r: NotifyRule) = JSONObject().put("id", r.id).put("from", r.from).put("to", r.to)
        .put("win", r.window.encode()).put("days", JSONArray(r.days.sorted())).put("on", r.on)
        .apply { r.train?.let { put("train", it) }; r.time?.let { put("time", it) } }

    private fun fromJson(o: JSONObject) = NotifyRule(
        o.getString("id"), o.getInt("from"), o.getInt("to"),
        Window.decode(o.optString("win")) ?: WINDOW_HIN,
        o.optJSONArray("days")?.let { a -> List(a.length()) { a.getInt(it) }.toSet() } ?: WEEKDAYS,
        if (o.has("train")) o.getString("train") else null,
        if (o.has("time")) o.getString("time") else null,
        o.optBoolean("on", true),
    )

    /** Aus den bisherigen Einstellungen (Richtungen je Lieblingsstrecke mit Zeitfenster) Regeln machen. */
    private fun migrate(ctx: Context): List<NotifyRule> {
        val p = Repo.prefs(ctx)
        val oldIds = mapOf("haeger_hbf" to "3-1", "hbf_haeger" to "1-3", "haeger_bst" to "3-8", "bst_haeger" to "8-3")
        val rels = p.getStringSet("n_rels", null)?.map { oldIds[it] ?: it }?.toSet()     // null = noch nie eingestellt
        val days = if (p.getBoolean("n_weekdays", true)) WEEKDAYS else ALL_DAYS
        fun stored(id: String) = Window.decode(p.getString("n_win_$id", null))
            ?: oldIds.entries.firstOrNull { it.value == id }?.let { Window.decode(p.getString("n_win_${it.key}", null)) }
        return Repo.favs(ctx).flatMap { f ->
            listOf(Triple(f.a, f.b, WINDOW_HIN), Triple(f.b, f.a, WINDOW_BACK)).map { (a, b, def) ->
                val id = Snapshot.relId(a, b)
                NotifyRule(UUID.randomUUID().toString(), a, b, stored(id) ?: def, days, on = rels?.contains(id) ?: true)
            }
        }
    }

    /** Standard-Regeln fuer die Lieblingsstrecken (Hin morgens, Rueck nachmittags), alle an. */
    fun defaults(ctx: Context): List<NotifyRule> = Repo.favs(ctx).flatMap { f ->
        listOf(NotifyRule(UUID.randomUUID().toString(), f.a, f.b, WINDOW_HIN, WEEKDAYS),
            NotifyRule(UUID.randomUUID().toString(), f.b, f.a, WINDOW_BACK, WEEKDAYS))
    }

    fun newRoute(ctx: Context): NotifyRule {
        val f = Repo.favs(ctx).firstOrNull() ?: Fav(3, 1)
        return NotifyRule(UUID.randomUUID().toString(), f.a, f.b, WINDOW_HIN, WEEKDAYS)
    }

    // ---------------------------------------------------------------- Einzelne Zuege
    fun forTrain(ctx: Context, train: String, depPlanned: String, relId: String): NotifyRule? =
        load(ctx).firstOrNull { it.isTrain && it.relId == relId && (it.train == train || it.time == depPlanned) }

    /** Zug an/abmelden; true = jetzt angemeldet. Neue Zug-Regeln gelten an den Tagen derselben Art (Mo–Fr oder Sa/So). */
    fun toggleTrain(ctx: Context, rel: Rel, r: Row): Boolean {
        val all = load(ctx)
        val hit = forTrain(ctx, r.train, r.depPlanned, rel.id)
        if (hit != null) { save(ctx, all.filter { it.id != hit.id }); return false }
        val (a, b) = Snapshot.parseRelId(rel.id) ?: return false
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val days = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) setOf(Calendar.SATURDAY, Calendar.SUNDAY) else WEEKDAYS
        save(ctx, all + NotifyRule(UUID.randomUUID().toString(), a, b, WINDOW_HIN, days, train = r.train, time = r.depPlanned))
        return true
    }
}

/* ================================================================== Editor */

/**
 * Liste der Regeln zum An-/Ausschalten und Bearbeiten (Strecke, Zeitfenster, Wochentage, Loeschen).
 * compact = fuer die Einfuehrung: ohne Hinzufuegen-Knopf und ohne Loeschen.
 */
@Composable
fun RulesEditor(enabled: Boolean, compact: Boolean = false) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(Rules.load(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    fun upd(n: List<NotifyRule>) {
        rules = n; Rules.save(ctx, n)
        Repo.prefs(ctx).edit().putBoolean("n_rules_custom", true).apply()
    }
    fun set(r: NotifyRule) = upd(rules.map { if (it.id == r.id) r else it })

    Column(verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
        if (rules.isEmpty()) Text("Noch keine Benachrichtigung angelegt.", style = Ts.small, color = Rb.c.muted)
        rules.forEach { r ->
            val isOpen = open == r.id
            RbCard(padding = Dim.s12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(Dim.rButton))
                        .clickable { open = if (isOpen) null else r.id }.padding(vertical = Dim.s4)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (r.isTrain) Icon(RbIcons.pin, null, tint = Rb.c.primary,
                                modifier = Modifier.padding(end = Dim.s4).size(Dim.iconSmall))
                            Text(r.title(), style = Ts.textStrong, color = if (enabled) Rb.c.onSurface else Rb.c.muted)
                        }
                        Text(r.subtitle() + if (isOpen) "" else " · bearbeiten", style = Ts.small, color = Rb.c.muted)
                    }
                    Switch(checked = r.on, enabled = enabled, onCheckedChange = { set(r.copy(on = it)) })
                }
                if (isOpen) {
                    Spacer(Modifier.height(Dim.s8))
                    if (!r.isTrain) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StationPicker(null, r.from, exclude = r.to) { set(r.copy(from = it)) }
                            Text("  →  ", style = Ts.text, color = Rb.c.muted)
                            StationPicker(null, r.to, exclude = r.from) { set(r.copy(to = it)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                            TimeChip("von", r.window.from, enabled) { pickTime(ctx, r.window.from, "Benachrichtigen ab") { v ->
                                set(r.copy(window = Window(v, maxOf(v, r.window.to)))) } }
                            TimeChip("bis", r.window.to, enabled) { pickTime(ctx, r.window.to, "Benachrichtigen bis") { v ->
                                set(r.copy(window = Window(minOf(r.window.from, v), v))) } }
                        }
                    } else Text("Meldet sich bei Verspätung oder Ausfall dieses Zugs (Zug ${r.train}, Abfahrt ${r.time}).",
                        style = Ts.small, color = Rb.c.muted)
                    Spacer(Modifier.height(Dim.s8))
                    DayChips(r.days, enabled) { set(r.copy(days = it)) }
                    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconBtn(RbIcons.delete, "Benachrichtigung löschen", { upd(rules.filter { it.id != r.id }); open = null },
                            tint = Rb.c.bad)
                    }
                }
            }
        }
        if (!compact) RbButton("Benachrichtigung hinzufügen", {
            val n = Rules.newRoute(ctx); upd(rules + n); open = n.id
        }, icon = RbIcons.plus, primary = false, enabled = enabled)
    }
}

/** Wochentage als Umschalter Mo ... So. */
@Composable
private fun DayChips(days: Set<Int>, enabled: Boolean, onChange: (Set<Int>) -> Unit) {
    val order = listOf(Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Di", Calendar.WEDNESDAY to "Mi", Calendar.THURSDAY to "Do",
        Calendar.FRIDAY to "Fr", Calendar.SATURDAY to "Sa", Calendar.SUNDAY to "So")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEach { (d, label) ->
            val on = d in days
            Text(label, style = if (on) Ts.smallStrong else Ts.small,
                color = if (on) Rb.c.onPrimary else Rb.c.muted,
                modifier = Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(Dim.rChip))
                    .background(if (on) Rb.c.primary else Rb.c.background)
                    .clickable(enabled = enabled, role = Role.Checkbox) { onChange(if (on) days - d else days + d) }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun pickTime(ctx: Context, cur: Int, title: String, onPick: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, cur / 60, cur % 60, true).apply { setTitle(title) }.show()
}
