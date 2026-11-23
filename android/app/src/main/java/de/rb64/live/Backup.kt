package de.rb64.live

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sicherung: Einstellungen und aufgezeichnete Fahrten als eine JSON-Datei (z. B. fuer einen Handywechsel
 * oder den Wechsel von einer Test- auf die offizielle Version). Zwischenspeicher wie Zugdaten oder
 * Push-Token werden nicht gesichert - die kommen nach dem Wiederherstellen von selbst neu.
 */
object Backup {
    private const val APP = "rb64-live"
    private const val FORMAT = 1

    /** Nicht sichern: Caches, Geraete-Bindung, Tageszustaende. */
    private fun skip(key: String) = key in setOf("payload", "token_sent", "ride_upload_pending", "ride_ended", "refreshing",
        "past_until", "feedback_times", "pin_train", "pin_rel", "pin_until", "pin_last_delay",
        "news_seen") ||     // "Neu in" haengt am Geraet, nicht an der Sicherung
        key.startsWith("prompt_") || key.startsWith("mute_") || key.startsWith("refresh")

    fun export(ctx: Context): String {
        de.rb64.live.ride.RideStore.init(ctx)
        val prefs = JSONArray()
        Repo.prefs(ctx).all.toSortedMap().forEach { (k, v) ->
            if (skip(k) || v == null) return@forEach
            val (t, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "ss" to JSONArray(v.map { it.toString() })
                else -> return@forEach
            }
            prefs.put(JSONObject().put("k", k).put("t", t).put("v", value))
        }
        val rides = JSONArray()
        de.rb64.live.ride.RideStore.history.value.forEach { m ->
            de.rb64.live.ride.RideStore.load(ctx, m.id)?.let { rides.put(de.rb64.live.ride.RideStore.toJson(it)) }
        }
        return JSONObject().put("app", APP).put("format", FORMAT).put("created", System.currentTimeMillis())
            .put("version", BuildConfig.VERSION_NAME).put("prefs", prefs).put("rides", rides).toString()
    }

    data class Result(val prefs: Int, val rides: Int)

    /** Wiederherstellen: Einstellungen ueberschreiben, Fahrten ergaenzen (vorhandene bleiben). */
    fun import(ctx: Context, text: String): Result {
        val o = JSONObject(text)
        require(o.optString("app") == APP) { "Keine Sicherung von RB64 Live" }
        require(o.optInt("format") <= FORMAT) { "Sicherung von einer neueren App-Version" }
        val e = Repo.prefs(ctx).edit()
        var n = 0
        o.optJSONArray("prefs")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val k = p.getString("k")
                if (skip(k)) continue
                when (p.getString("t")) {
                    "b" -> e.putBoolean(k, p.getBoolean("v"))
                    "i" -> e.putInt(k, p.getInt("v"))
                    "l" -> e.putLong(k, p.getLong("v"))
                    "f" -> e.putFloat(k, p.getDouble("v").toFloat())
                    "s" -> e.putString(k, p.getString("v"))
                    "ss" -> e.putStringSet(k, p.getJSONArray("v").let { s -> (0 until s.length()).map { s.getString(it) }.toSet() })
                    else -> continue
                }
                n++
            }
        }
        e.apply()
        var r = 0
        o.optJSONArray("rides")?.let { a ->
            for (i in 0 until a.length()) {
                val s = runCatching { de.rb64.live.ride.RideStore.fromJson(a.getJSONObject(i)) }.getOrNull() ?: continue
                if (de.rb64.live.ride.RideStore.importRide(ctx, s)) r++
            }
        }
        de.rb64.live.ride.Fences.sync(ctx)
        de.rb64.live.widget.Rb64WidgetProvider.updateAll(ctx)
        return Result(n, r)
    }

    fun write(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export(ctx).toByteArray()) }
    }

    fun read(ctx: Context, uri: Uri): Result =
        import(ctx, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })

    /** Kurze anonyme Kennung (wie auf dem Server gespeichert), z. B. fuer Rueckfragen zu Feedback oder Fahrten. */
    fun shortId(): String? = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
        java.security.MessageDigest.getInstance("SHA-1").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
