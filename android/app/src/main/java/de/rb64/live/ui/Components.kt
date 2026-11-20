package de.rb64.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Schatten nach Ebene; im Dunkeln keine Schatten (Tiefe ueber hellere Flaechen). */
@Composable
fun Modifier.elevation(level: Dp, shape: Shape): Modifier =
    if (Rb.c.dark || level == 0.dp || !LocalVis.current.shadows) this
    else this.shadow(level, shape, clip = false, ambientColor = Color(0xFF101828), spotColor = Color(0xFF101828))

/** Karte: E1, Ecken 16, Flaeche surface. */
@Composable
fun RbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = Dim.s16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dim.rCard)
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && onClick != null && LocalVis.current.animations) 0.98f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 600f), label = "feder")
    Column(
        modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .elevation(Elev.e1, shape).clip(shape).background(Rb.c.surface)
            .then(if (onClick != null) Modifier.clickable(interactionSource = src, indication = androidx.compose.material3.ripple(),
                onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Pille (rund) fuer Status. */
/**
 * Detailblatt von unten. Bleibt unter der Statusleiste (oben ein Rand, damit man sieht, dass es ein Blatt ist).
 * Der Inhalt muss selbst scrollen (z. B. [SheetBody]); die Navigationsleiste unten beruecksichtigt [SheetBody].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RbSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding().padding(top = Dim.s24),
        contentWindowInsets = { WindowInsets(0) },
        containerColor = Rb.c.surface3,
        shape = RoundedCornerShape(topStart = Dim.rSheet, topEnd = Dim.rSheet),
        content = content,
    )
}

/** Fester Kopf + scrollender Inhalt fuer [RbSheet]. Beim Scrollen trennt eine Linie den Kopf ab. */
@Composable
fun SheetBody(header: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    // Hochscrollen soll oben anhalten: Nur eine Geste, die schon oben beginnt, darf das Blatt nach unten ziehen.
    // Rest-Scrollen und Schwung aus einer Geste von weiter unten schluckt diese Verbindung.
    val startedAtTop = androidx.compose.runtime.remember { booleanArrayOf(true) }
    val stopAtTop = androidx.compose.runtime.remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                                      source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
                if (available.y > 0 && (!startedAtTop[0] || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput))
                    available else androidx.compose.ui.geometry.Offset.Zero
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) =
                if (available.y > 0 && !startedAtTop[0]) available else androidx.compose.ui.unit.Velocity.Zero
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dim.s16).padding(bottom = Dim.s12), content = header)
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.value > 0) Rb.c.line else Color.Transparent))
        Column(
            Modifier.weight(1f, fill = false)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        startedAtTop[0] = scroll.value == 0
                    }
                }
                .nestedScroll(stopAtTop).verticalScroll(scroll)
                .padding(horizontal = Dim.s16).padding(top = Dim.s12, bottom = Dim.s24).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dim.s16), content = body,
        )
    }
}

/** Abschnitt, der sich auf- und zuklappen laesst (Kopfzeile antippbar, mind. 48 dp). */
@Composable
fun Collapsible(title: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
                badge: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dim.touch).clip(RoundedCornerShape(Dim.rButton)).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
            Text(title, style = Ts.textStrong, modifier = Modifier.weight(1f))
            badge?.invoke()
            Icon(if (open) RbIcons.chevronUp else RbIcons.chevronDown, if (open) "zuklappen" else "aufklappen",
                tint = Rb.c.muted, modifier = Modifier.size(Dim.icon))
        }
        content()
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(text, style = Ts.smallBold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = Dim.s12, vertical = Dim.s4))
}

/** Kleine runde Ziffer/Bezeichnung ohne Status (z. B. "Ersatzbus"). */
@Composable
fun NeutralPill(text: String) = Pill(text, Rb.c.muted, Rb.c.line)

/** Knopf 48 hoch: gefuellt (Hauptknopf, einer je Bildschirm) oder getoent. */
@Composable
fun RbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(Dim.rButton)
    val bg = if (primary) Rb.c.primary else Rb.c.primaryTint
    val fg = if (primary) Rb.c.onPrimary else Rb.c.primary
    Row(
        modifier.heightIn(min = Dim.touch).clip(shape).background(if (enabled) bg else Rb.c.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Dim.s16),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(Dim.icon)); Spacer(Modifier.width(Dim.s8)) }
        Text(text, style = Ts.textStrong, color = fg)
    }
}

/** Icon-Knopf mit 48er Tippflaeche und Beschreibung fuer TalkBack. */
/** Zweitaktion als Textknopf mit Symbol (mind. 48 dp hoch), z. B. unter dem Hauptknopf. */
@Composable
fun TextAction(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = Dim.touch).clip(RoundedCornerShape(Dim.rButton))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dim.s8),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Rb.c.primary, modifier = Modifier.size(Dim.iconSmall)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Ts.smallStrong, color = Rb.c.primary)
    }
}

@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Rb.c.onSurface, busy: Boolean = false) {
    Box(
        Modifier.size(Dim.touch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) MorphIndicator(tint)
        else Icon(icon, null, tint = tint, modifier = Modifier.size(Dim.icon))
    }
}

/** Oeffnet die Einstellungen (Zahnrad in jeder Kopfleiste); null = gerade in den Einstellungen. */
val LocalOpenSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Kopfleiste (E2): Petrol, Schatten nach unten, unter die Statusleiste gezogen. */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().elevation(Elev.e2, RectangleShape).background(Rb.c.header).statusBarsPadding()
            .padding(start = if (onBack != null) Dim.s4 else Dim.s16, end = Dim.s8, top = Dim.s8, bottom = Dim.s8),
    ) {
        Row(Modifier.fillMaxWidth().height(Dim.touch), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(RbIcons.back, "Zurück", onBack, tint = Rb.c.onHeader)
            Text(title, style = Ts.titleBold, color = Rb.c.onHeader, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
            LocalOpenSettings.current?.let { IconBtn(RbIcons.settings, "Einstellungen", it, tint = Rb.c.onHeader) }
        }
        below?.invoke(this)
    }
}

/** Chip in der Kopfleiste (Ecken 8, sichtbar 32 hoch, Tippflaeche 48). */
@Composable
fun HeaderChip(text: String, selected: Boolean, first: Boolean = true, last: Boolean = true, onClick: () -> Unit) {
    // Material 3 Expressive "verbundene Knopfgruppe": aussen rund, innen eng; die Auswahl wird zur Pille
    val spec = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.7f, stiffness = 500f)
    val full = 18.dp
    val l by androidx.compose.animation.core.animateDpAsState(if (selected) full else if (first) 12.dp else 4.dp, spec, label = "l")
    val r by androidx.compose.animation.core.animateDpAsState(if (selected) full else if (last) 12.dp else 4.dp, spec, label = "r")
    Box(Modifier.height(Dim.touch).clickable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Ts.smallStrong, maxLines = 1,
            color = if (selected) Rb.c.header else Rb.c.onHeader,
            modifier = Modifier.clip(RoundedCornerShape(topStart = l, bottomStart = l, topEnd = r, bottomEnd = r))
                .background(if (selected) Rb.c.onHeader else Rb.c.onHeader.copy(alpha = 0.16f))
                .padding(horizontal = Dim.s12, vertical = 7.dp))
    }
}

/** Hinweisstreifen (Ecken 12). kind: warn | bad | info */
@Composable
fun Banner(text: String, kind: String = "warn", sub: String? = null, onClick: (() -> Unit)? = null) {
    val (fg, bg) = when (kind) {
        "bad" -> Rb.c.bad to Rb.c.badBg
        "info" -> Rb.c.primary to Rb.c.primaryTint
        else -> Rb.c.warn to Rb.c.warnBg
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(Dim.s12),
        horizontalArrangement = Arrangement.spacedBy(Dim.s8),
    ) {
        Icon(RbIcons.warning, null, tint = fg, modifier = Modifier.size(Dim.iconSmall).padding(top = 1.dp))
        Column {
            Text(text, style = if (sub != null) Ts.smallStrong else Ts.small, color = fg)
            if (sub != null) Text(sub, style = Ts.small, color = fg)
        }
    }
}

/** Abschnittskopf 48 hoch, optional mit Aktion rechts. */
@Composable
fun SectionHeader(text: String, action: String? = null, actionIcon: ImageVector? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(Dim.touch), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Ts.smallStrong, color = Rb.c.muted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Row(
            Modifier.height(Dim.touch).clip(RoundedCornerShape(Dim.rChip)).clickable(onClick = onAction).padding(horizontal = Dim.s8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s4),
        ) {
            if (actionIcon != null) Icon(actionIcon, null, tint = Rb.c.primary, modifier = Modifier.size(Dim.iconSmall))
            Text(action, style = Ts.smallStrong, color = Rb.c.primary)
        }
    }
}

/** Liste in einer Karte: Zeilen mit 1-px-Trennlinie. */
@Composable
fun <T> ListCard(items: List<T>, row: @Composable (T) -> Unit) {
    val line = Rb.c.line
    RbCard(padding = 0.dp) {
        items.forEachIndexed { i, it ->
            Box(Modifier.fillMaxWidth().then(if (i < items.size - 1) Modifier.drawBehind {
                drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            } else Modifier)) { row(it) }
        }
    }
}

/** Navigation unten: 80 hoch, kein Schatten, 1-px-Linie oben. Der mittlere Eintrag ist hervorgehoben:
 *  runder Petrol-Knopf (56), leicht ueber die Leiste gehoben (E2). */
@Composable
fun BottomNav(items: List<Pair<String, ImageVector>>, selected: Int, center: Int = -1, onSelect: (Int) -> Unit) {
    val line = Rb.c.line
    Row(
        Modifier.fillMaxWidth().background(Rb.c.surface)
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding().height(Dim.nav),
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            if (i == center) {
                Column(
                    Modifier.weight(1f).height(Dim.nav).clickable(role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.offset(y = (-16).dp).size(56.dp).elevation(Elev.e2, CircleShape).clip(CircleShape)
                            .background(Rb.c.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = Rb.c.onPrimary, modifier = Modifier.size(28.dp)) }
                    Text(label, style = Ts.smallStrong, color = if (on) Rb.c.primary else Rb.c.onSurface,
                        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.offset(y = (-10).dp))
                }
                return@forEachIndexed
            }
            Column(
                Modifier.weight(1f).height(Dim.nav).clickable(role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (on) Rb.c.primaryTint else Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (on) Rb.c.primary else Rb.c.muted, modifier = Modifier.size(Dim.icon))
                }
                Spacer(Modifier.height(Dim.s4))
                Text(label, style = if (on) Ts.smallStrong else Ts.small, color = if (on) Rb.c.primary else Rb.c.muted,
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** Restzeit ohne "in": "gleich" unter 1 min, "13 min", ab 60 min "1 h 03". */
fun relShort(epoch: Long, now: Long = System.currentTimeMillis()): String {
    val m = Math.floorDiv(epoch - now, 60_000L)
    return when {
        m < 1 -> "gleich"
        m < 60 -> "$m min"
        else -> "${m / 60} h %02d".format(m % 60)
    }
}

/** Ladeanzeige im Stil von Material 3 Expressive: abgerundetes Quadrat, das sich dreht und zum Kreis verformt. */
@Composable
fun MorphIndicator(color: Color, size: Dp = 22.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "morph")
    val rot by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "dreh")
    val k by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "form")
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val side = s * (0.70f + 0.12f * k)
        rotate(rot) {
            drawRoundRect(color, Offset((s - side) / 2, (s - side) / 2), androidx.compose.ui.geometry.Size(side, side),
                androidx.compose.ui.geometry.CornerRadius(side * (0.22f + 0.28f * k)))
        }
    }
}

/** "jetzt" unter 2 min, "in 13 min", ab 60 min "in 1 h 03". */
fun relTime(epoch: Long, now: Long = System.currentTimeMillis()): String {
    val m = Math.floorDiv(epoch - now, 60_000L)
    return when {
        m < 2 -> "jetzt"
        m < 60 -> "in $m min"
        else -> "in ${m / 60} h %02d".format(m % 60)
    }
}
