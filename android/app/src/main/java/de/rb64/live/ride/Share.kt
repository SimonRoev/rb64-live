package de.rb64.live.ride

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.TextAction
import de.rb64.live.ui.Ts
import kotlinx.coroutines.delay
import java.io.File

/**
 * Teilen von Fahrkarte und Rueckblick als Bild.
 * - Das Bild wird in einem festen Zustand gezeichnet (keine laufenden Animationen, kein Kippen) - [LocalShareMode].
 * - Ohne "Details" fehlen Uhrzeiten, Datum (nur Monat), Zugnummer, Platz, Seriennummer.
 * - Das Bild liegt nur im Zwischenspeicher (cache/share) und geht per FileProvider an die gewaehlte App.
 */
data class ShareMode(val details: Boolean)

/** null = normale Anzeige, sonst wird gerade ein Bild zum Teilen gezeichnet. */
val LocalShareMode = compositionLocalOf<ShareMode?> { null }

object Sharing {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "share")

    /** Alte Bilder wegraeumen (beim Start und vor jedem neuen Teilen). */
    fun cleanup(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** Als PNG ablegen (PNG aus Bitmap.compress hat keine Metadaten) und freigeben. */
    fun save(ctx: Context, bitmaps: List<Bitmap>): List<Uri> {
        cleanup(ctx)
        val d = dir(ctx).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        return bitmaps.mapIndexed { i, b ->
            val f = File(d, "rb64-$stamp-$i.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
        }
    }

    /** Android-Teilen-Auswahl mit einem oder mehreren Bildern. */
    fun send(ctx: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = "image/png"
        intent.clipData = ClipData.newUri(ctx.contentResolver, "RB64 Live", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Zeichnet [content] in fester Pixelgroesse (z. B. 1080 breit) und liefert das Bild.
 * Angezeigt wird es dabei verkleinert auf [previewWidth] - die Vorschau ist also genau das, was geteilt wird.
 */
@Composable
fun Capture(widthPx: Int, heightPx: Int, previewWidth: Dp, onBitmap: (Bitmap) -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val wDp = with(density) { widthPx.toDp() }
    val hDp = with(density) { heightPx.toDp() }
    val scale = previewWidth / wDp
    val layer = rememberGraphicsLayer()
    LaunchedEffect(Unit) {
        delay(600)                                   // erst fertig messen und zeichnen lassen
        val img = layer.toImageBitmap()
        // als normale Kopie (keine Hardware-Bitmap), damit sie sich speichern und teilen laesst
        onBitmap(img.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
    }
    Box(Modifier.size(previewWidth, hDp * scale).clip(RoundedCornerShape(6.dp))) {
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(wDp, hDp)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) { content() }
    }
}

/** Gemeinsamer Teilen-Dialog: Auswahl, Vorschau, Schalter "Details zeigen", Klartext, Teilen. */
@Composable
fun ShareFrame(
    title: String, choices: List<String>, choice: Int, onChoice: (Int) -> Unit,
    details: Boolean, onDetails: (Boolean) -> Unit, summary: String, ready: Boolean,
    onShare: () -> Unit, onClose: () -> Unit, preview: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(Dim.rSheet)).background(Rb.c.surface)
            .verticalScroll(rememberScrollState()).padding(Dim.s16), verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
            Text(title, style = Ts.title)
            if (choices.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                choices.forEachIndexed { i, c ->
                    val on = i == choice
                    Text(c, style = Ts.smallStrong, color = if (on) Rb.c.onPrimary else Rb.c.primary,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Rb.c.primary else Rb.c.primaryTint)
                            .clickable { onChoice(i) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(Rb.c.background)
                .border(1.dp, Rb.c.line, RoundedCornerShape(Dim.rCard)).padding(Dim.s12), contentAlignment = Alignment.Center) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dim.s12)) { preview() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Details zeigen", style = Ts.textStrong)
                    Text("Uhrzeiten, Datum, Zug und Platz", style = Ts.small, color = Rb.c.muted)
                }
                Switch(checked = details, onCheckedChange = onDetails)
            }
            Text(summary, style = Ts.small, color = Rb.c.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction("Abbrechen", null, onClose)
                Spacer(Modifier.width(Dim.s8))
                RbButton(if (ready) "Teilen" else "Einen Moment …", onShare, enabled = ready)
            }
        }
    }
}

/** Im Teilen-Bild: Inhalt mit festem Zustand und Datenschutz-Einstellung zeichnen. */
@Composable
fun SharedContent(details: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalShareMode provides ShareMode(details), content = content)
