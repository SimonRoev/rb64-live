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
    private fun skip(key: String) = key in setOf("payload", "token_sent", "ride_upload_pending", "ride_upload_queue", "ride_delete_queue", "ride_boarded",
        "ride_ended", "refreshing", "past_until", "feedback_times", "pin_train", "pin_rel", "pin_until", "pin_last_delay",
        "pin_here", "pin_here_until", "news_seen", "ride_auto", "pin_sent_until", "live_gps", "hist_fetched", "checkin_nonce") ||
        key.startsWith("prompt_") || key.startsWith("mute_") || key.startsWith("refresh") || key.startsWith("upd_")

    /**
     * Beim Wiederherstellen nie uebernehmen: Einwilligungen (Live-Teilen, Autostart, Bahnhofs-Vorschlag) - die gibt man
     * auf jedem Geraet selbst, eine fremde oder manipulierte Datei darf sie nicht einschalten.
     */
    private val CONSENT = setOf("live_share", "auto_ride", "fence_enabled")

    /** Bekannte Einstellungen mit ihrem Typ (b, i, l, f, s, ss). Unbekanntes oder falsch Getyptes wird uebergangen. */
    private val TYPES = listOf(
        Regex("^vis_.+") to "b", Regex("^n_win_.+") to "s",
        Regex("^n_(enabled|only_cancel|vibrate|weekdays|rules_custom)$") to "b", Regex("^n_(lead|threshold)$") to "i",
        Regex("^n_rules$") to "s", Regex("^n_rels$") to "ss",
        Regex("^(favs|theme_mode|flip_half|pchecks)$") to "s",
        Regex("^(fav|live_from|live_to|switch_hour|pin_threshold|tk_since_leg|tk_since_rare|tk_vmax_rec)$") to "i",
        Regex("^(auto_return|manual_return|onboarded|perm_fence_skip|pin_lock|pin_notify)$") to "b",
        Regex("^tk_found$") to "ss", Regex("^tk_(var|sp|cls)_[A-Za-z0-9-]{8,64}$") to "s",
        Regex("^tk_(dd|torn)_[A-Za-z0-9-]{8,64}$") to "b", Regex("^tk_punch_[A-Za-z0-9-]{8,64}$") to "f",
        Regex("^tk_fgr_[A-Za-z0-9-]{8,64}$") to "i", Regex("^rk_[A-Za-z0-9-]{8,64}$") to "s", Regex("^review_notified_[0-9]{6}$") to "b", Regex("^review_opened_[0-9]{8,16}$") to "b",
    )
    private fun typeOf(key: String) = TYPES.firstOrNull { it.first.matches(key) }?.second
    private const val MAX_BYTES = 30_000_000          // ca. 1000 Fahrten - mehr ist keine echte Sicherung
    private const val MAX_STRING = 200_000

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

    /**
     * Wiederherstellen: Einstellungen ueberschreiben, Fahrten ergaenzen (vorhandene bleiben).
     * Erst alles pruefen, dann schreiben: nur bekannte Schluessel mit passendem Typ, keine Einwilligungen,
     * Fahrt-IDs als UUID (siehe RideStore.importRide).
     */
    fun import(ctx: Context, text: String): Result {
        require(text.length <= MAX_BYTES) { "Datei zu groß für eine Sicherung" }
        val o = JSONObject(text)
        require(o.optString("app") == APP) { "Keine Sicherung von RB64 Live" }
        require(o.optInt("format") <= FORMAT) { "Sicherung von einer neueren App-Version" }
        val ok = mutableListOf<Pair<String, (android.content.SharedPreferences.Editor) -> Unit>>()
        o.optJSONArray("prefs")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                val k = p.optString("k")
                val t = p.optString("t")
                if (k.isEmpty() || skip(k) || k in CONSENT || typeOf(k) != t) continue
                val put: ((android.content.SharedPreferences.Editor) -> Unit)? = runCatching {
                    when (t) {
                        "b" -> p.getBoolean("v").let { v -> { e: android.content.SharedPreferences.Editor -> e.putBoolean(k, v); Unit } }
                        "i" -> p.getInt("v").let { v -> { e: android.content.SharedPreferences.Editor -> e.putInt(k, v); Unit } }
                        "l" -> p.getLong("v").let { v -> { e: android.content.SharedPreferences.Editor -> e.putLong(k, v); Unit } }
                        "f" -> p.getDouble("v").toFloat().let { v -> { e: android.content.SharedPreferences.Editor -> e.putFloat(k, v); Unit } }
                        "s" -> p.getString("v").takeIf { it.length <= MAX_STRING }
                            ?.let { v -> { e: android.content.SharedPreferences.Editor -> e.putString(k, v); Unit } }
                        "ss" -> p.getJSONArray("v").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                            .takeIf { it.size <= 5000 }?.let { v -> { e: android.content.SharedPreferences.Editor -> e.putStringSet(k, v); Unit } }
                        else -> null
                    }
                }.getOrNull()
                if (put != null) ok += k to put
            }
        }
        val rides = mutableListOf<de.rb64.live.ride.RideState>()
        o.optJSONArray("rides")?.let { a ->
            for (i in 0 until minOf(a.length(), 1000)) {
                val r = runCatching { de.rb64.live.ride.RideStore.fromJson(a.getJSONObject(i)) }.getOrNull() ?: continue
                if (de.rb64.live.ride.RideStore.validId(r.info.id) && r.points.size <= 20_000) rides += r
            }
        }
        val e = Repo.prefs(ctx).edit()
        ok.forEach { it.second(e) }
        e.apply()
        val r = rides.count { de.rb64.live.ride.RideStore.importRide(ctx, it) }
        de.rb64.live.ride.Fences.sync(ctx)
        de.rb64.live.widget.Rb64WidgetProvider.updateAll(ctx)
        return Result(ok.size, r)
    }

    fun write(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export(ctx).toByteArray()) }
    }

    fun read(ctx: Context, uri: Uri): Result =
        import(ctx, ctx.contentResolver.openInputStream(uri)!!.use { input ->
            // hoechstens MAX_BYTES lesen - eine riesige Datei soll nicht den Speicher sprengen
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                buf.write(chunk, 0, n)
                require(buf.size() <= MAX_BYTES) { "Datei zu groß für eine Sicherung" }
            }
            buf.toByteArray().decodeToString()
        })

    /** Kurze anonyme Kennung (wie auf dem Server gespeichert), z. B. fuer Rueckfragen zu Feedback oder Fahrten. */
    fun shortId(): String? = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
        java.security.MessageDigest.getInstance("SHA-1").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
