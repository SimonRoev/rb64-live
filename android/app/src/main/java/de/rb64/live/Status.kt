package de.rb64.live

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * Hinweis an alle (z. B. Stoerung oder Wartung) aus Firestore public/status - in der Firebase-Konsole bearbeitbar:
 *   text  (Text, leer = kein Hinweis), level ("info" oder "warn"), until (Zeitstempel, optional), id (optional).
 * - Laeuft die VM, liest sie das Dokument alle paar Minuten und schickt es mit den normalen Daten mit ("st") -
 *   das kostet die Handys keinen Lesezugriff.
 * - Ist die VM ausgefallen (Daten veraltet), liest die App das Dokument beim Oeffnen selbst (hoechstens alle 10 min).
 * Weggetippte Hinweise kommen nicht wieder (gemerkt je id).
 */
object StatusStore {
    data class Status(val id: String, val text: String, val warn: Boolean, val until: Long)

    private const val KEY = "status_json"
    @Volatile var version = 0
        private set

    fun current(ctx: Context, now: Long = System.currentTimeMillis()): Status? {
        @Suppress("UNUSED_VARIABLE") val v = version
        val p = Repo.prefs(ctx)
        val o = runCatching { JSONObject(p.getString(KEY, null) ?: return null) }.getOrNull() ?: return null
        val s = Status(o.optString("i"), o.optString("t").trim(), o.optString("l") == "warn", o.optLong("u", 0L))
        if (s.text.isEmpty() || (s.until > 0 && now > s.until)) return null
        if (p.getString("status_dismissed", null) == s.id) return null
        return s
    }

    fun dismiss(ctx: Context, s: Status) {
        Repo.prefs(ctx).edit().putString("status_dismissed", s.id).apply()
        version++
    }

    /** Aus den Daten der VM: "st" = {"i","t","l","u"} oder fehlt (= kein Hinweis). */
    fun fromPayload(ctx: Context, st: JSONObject?) = save(ctx, st?.takeIf { it.optString("t").isNotBlank() })

    /** VM liefert keine frischen Daten: Hinweis direkt aus Firestore holen (hoechstens alle 10 min). */
    fun fetchIfStale(ctx: Context) {
        val snap = Repo.load(ctx)
        if (snap != null && !snap.stale()) return
        val p = Repo.prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("status_fetched", 0L) < 10 * 60_000L) return
        p.edit().putLong("status_fetched", now).apply()
        val app = ctx.applicationContext
        Cloud.withUserPublic {
            FirebaseFirestore.getInstance().collection("public").document("status").get()
                .addOnSuccessListener { d ->
                    val text = d.getString("text")?.trim().orEmpty()
                    if (text.isEmpty()) { save(app, null); return@addOnSuccessListener }
                    val until = d.getTimestamp("until")?.toDate()?.time ?: 0L
                    // gleiche Kennung wie auf der VM (sha1 von Text + Stufe), damit ein weggetippter Hinweis weg bleibt
                    val level = if (d.getString("level") == "warn") "warn" else "info"
                    val id = d.getString("id") ?: java.security.MessageDigest.getInstance("SHA-1")
                        .digest((text.take(300) + level).toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
                    save(app, JSONObject().put("i", id).put("t", text.take(300))
                        .put("l", if (d.getString("level") == "warn") "warn" else "info").put("u", until))
                }
                .addOnFailureListener { Log.w("RB64", "Hinweis nicht geladen", it) }
        }
    }

    private fun save(ctx: Context, o: JSONObject?) {
        val p = Repo.prefs(ctx)
        val s = o?.toString()
        if (p.getString(KEY, null) == s) return
        p.edit().apply { if (s == null) remove(KEY) else putString(KEY, s) }.apply()
        version++
    }
}
