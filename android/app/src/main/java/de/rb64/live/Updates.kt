package de.rb64.live

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update-Hinweis: Die Webseite (GitHub Pages) legt bei jeder Version docs/version.json ab.
 * Die App fragt sie hoechstens alle 6 h ab (beim Oeffnen) und zeigt einen Hinweis, wenn es eine neuere gibt.
 * Uebertragen wird dabei nur die normale Web-Anfrage (IP-Adresse an GitHub), keine Kennung.
 */
object Updates {
    private const val URL_JSON = "https://simonroev.github.io/rb64-live/version.json"
    private const val EVERY_MS = 6 * 3600_000L

    data class Info(val code: Int, val name: String, val url: String, val page: String, val notes: String)

    /** Gespeicherten Stand lesen: neuere Version als die installierte? */
    fun available(ctx: Context): Info? {
        val p = Repo.prefs(ctx)
        val code = p.getInt("upd_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        return Info(code, p.getString("upd_name", "") ?: "", p.getString("upd_url", "") ?: "",
            p.getString("upd_page", "") ?: "", p.getString("upd_notes", "") ?: "")
    }

    fun lastChecked(ctx: Context) = Repo.prefs(ctx).getLong("upd_checked", 0L)

    /** Im Hintergrund pruefen (force = auch wenn erst kuerzlich geprueft). onDone(true) = Abfrage gelungen. */
    fun check(ctx: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!force && System.currentTimeMillis() - lastChecked(app) < EVERY_MS) { onDone?.invoke(true); return }
        Thread {
            val ok = runCatching {
                val c = (URL(URL_JSON).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val o = c.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
                Repo.prefs(app).edit()
                    .putInt("upd_code", o.getInt("versionCode"))
                    .putString("upd_name", o.optString("versionName"))
                    .putString("upd_url", o.optString("url"))
                    .putString("upd_page", o.optString("page"))
                    .putString("upd_notes", o.optString("notes"))
                    .putLong("upd_checked", System.currentTimeMillis())
                    .apply()
            }.onFailure { Log.i("RB64", "Update-Pruefung fehlgeschlagen: ${it.message}") }.isSuccess
            onDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(ok) } }
        }.start()
    }

    /** Neue Version herunterladen (Browser laedt die APK; danach oeffnen und installieren). */
    fun open(ctx: Context, info: Info) {
        val target = info.url.ifBlank { info.page }.ifBlank { "https://simonroev.github.io/rb64-live/#download" }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
