package de.rb64.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import de.rb64.live.ui.MorphIndicator
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.Ts

/** Bremse gegen versehentliches oder absichtliches Vielfach-Senden (der Server bremst zusaetzlich). */
object FeedbackLimit {
    const val COOLDOWN_MS = 2 * 60_000L
    const val PER_DAY = 5

    private fun times(ctx: android.content.Context): List<Long> {
        val now = System.currentTimeMillis()
        return Repo.prefs(ctx).getString("feedback_times", "").orEmpty().split(",")
            .mapNotNull { it.toLongOrNull() }.filter { now - it < 24 * 3600_000L }
    }

    /** Wie lange noch warten (ms); 0 = darf senden. */
    fun waitMs(ctx: android.content.Context): Long {
        val t = times(ctx)
        val now = System.currentTimeMillis()
        val cool = (t.maxOrNull() ?: 0L) + COOLDOWN_MS - now
        val day = if (t.size >= PER_DAY) t.min() + 24 * 3600_000L - now else 0L
        return maxOf(0L, cool, day)
    }

    fun record(ctx: android.content.Context) {
        val t = times(ctx) + System.currentTimeMillis()
        Repo.prefs(ctx).edit().putString("feedback_times", t.joinToString(",")).apply()
    }

    fun waitText(ms: Long): String = when {
        ms >= 3600_000L -> "Heute ist das Limit erreicht – morgen wieder."
        ms >= 60_000L -> "Bitte warte noch ${(ms + 59_999) / 60_000} min, bevor du wieder etwas schickst."
        else -> "Bitte warte noch ${(ms + 999) / 1000} s, bevor du wieder etwas schickst."
    }
}

/**
 * Feedback anonym senden (ohne Konto, ohne Mail). context = worauf es sich bezieht, z. B. eine Fahrt.
 * Zustaende: schreiben -> senden -> danke / Fehler (Text bleibt erhalten).
 */
@Composable
fun FeedbackDialog(context: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var withDevice by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf("edit") }      // edit | sending | done | error
    var wait by remember { mutableStateOf(FeedbackLimit.waitMs(ctx)) }
    androidx.compose.runtime.LaunchedEffect(wait > 0 && state != "done") {
        while (wait > 0 && state != "done") { kotlinx.coroutines.delay(1_000); wait = FeedbackLimit.waitMs(ctx) }
    }

    AlertDialog(
        onDismissRequest = { if (state != "sending") onClose() },
        containerColor = Rb.c.surface,
        title = { Text(if (state == "done") "Danke!" else "Feedback senden", style = Ts.title) },
        text = {
            if (state == "done") {
                Text("Ist angekommen. Wenn du eine Antwort möchtest und eine Kontaktmöglichkeit dazugeschrieben hast, melde ich mich.",
                    style = Ts.text)
            } else Column(verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
                if (context != null) Text(context, style = Ts.small, color = Rb.c.muted)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(4000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("Was ist dir aufgefallen? Fehler, Ideen, Lob …", style = Ts.text, color = Rb.c.muted) },
                    textStyle = Ts.text,
                    enabled = state != "sending",
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Rb.c.primary, cursorColor = Rb.c.primary,
                        focusedTextColor = Rb.c.onSurface, unfocusedTextColor = Rb.c.onSurface),
                )
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton))
                    .clickable(role = Role.Checkbox) { withDevice = !withDevice }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withDevice, onCheckedChange = { withDevice = it })
                    Text("App-Version und Handy-Modell mitsenden", style = Ts.small)
                }
                Text("Anonym: Name oder Mail werden nicht mitgeschickt. Wenn du eine Antwort möchtest, schreib eine Kontaktmöglichkeit in den Text.",
                    style = Ts.small, color = Rb.c.muted)
                if (state == "error") Text("Konnte nicht gesendet werden. Hast du Netz? Dein Text bleibt hier.",
                    style = Ts.small, color = Rb.c.bad)
                if (wait > 0 && state != "sending") Text(FeedbackLimit.waitText(wait), style = Ts.small, color = Rb.c.warn)
            }
        },
        confirmButton = {
            when (state) {
                "done" -> RbButton("Schließen", onClose)
                "sending" -> Row(Modifier.padding(Dim.s12)) { MorphIndicator(Rb.c.primary) }
                else -> RbButton("Senden", {
                    state = "sending"
                    Cloud.sendFeedback(ctx, text.trim(), withDevice, context) { ok ->
                        if (ok) FeedbackLimit.record(ctx)
                        state = if (ok) "done" else "error"
                    }
                }, enabled = text.isNotBlank() && wait == 0L)
            }
        },
        dismissButton = {
            if (state == "edit" || state == "error") Text("Abbrechen", style = Ts.textStrong, color = Rb.c.primary,
                modifier = Modifier.clip(RoundedCornerShape(Dim.rButton)).clickable(onClick = onClose).padding(Dim.s12))
        },
    )
}
