package de.rb64.live

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import de.rb64.live.ride.Stations

/**
 * Gemeinsames Aussehen der Live Updates (Sperrbildschirm, Statusleisten-Chip) fuer "Dein Zug" und die Fahrt:
 * - Leiste mit einem Punkt je Halt, der Zug faehrt als Symbol darueber (nach GPS gleitend, sonst nach Daten).
 * - Dein Ausstieg bzw. dein Bahnhof ist rot markiert, ein Kreuzungshalt bernsteinfarben.
 * - Vor der Abfahrt zeigt die Leiste, wie der Zug auf deinen Bahnhof zufaehrt.
 * - Chip: kurz vor der Abfahrt ein Countdown, unterwegs die Verspaetung (ab +3) oder die Ankunftszeit.
 * Nur Android 16+ (ProgressStyle); aeltere Geraete behalten die normale Meldung.
 */
object LiveCard {
    /**
     * Leiste: [stops] in Fahrtrichtung (Punkte gleichmaessig), [progress] 0..1, [mark] = rot markierter Halt (Ziel),
     * [crossing] = Kreuzungshalt, [delay] faerbt den gefahrenen Teil, [start] = Start-Symbol (dein Einstieg).
     */
    data class Bar(val stops: List<String>, val progress: Double, val mark: Int?, val crossing: Int?,
                   val delay: Int? = null, val start: Boolean = false)

    /** Bild neben dem Text: Gleisschild vor der Abfahrt, unterwegs ein Stempel wie auf der Fahrkarte. */
    sealed class Badge {
        data class Platform(val number: String) : Badge()
        data class Delay(val minutes: Int) : Badge()
    }

    private const val TOTAL = 1000
    private const val AMBER = 0xFFBA7517.toInt()
    private const val RED = 0xFFC2410C.toInt()

    /** Position auf der Linie (1 = Muenster Hbf ... 14 = Enschede) eines Halts. */
    fun seqOf(name: String): Int = Stations.all.indexOfFirst { it.name == name } + 1

    /** Position [seq] als Anteil der Strecke [stops] (in Fahrtrichtung), begrenzt auf 0..1. */
    fun fraction(stops: List<String>, seq: Double): Double {
        if (stops.size < 2) return 0.0
        val a = seqOf(stops.first()).toDouble(); val b = seqOf(stops.last()).toDouble()
        if (a <= 0 || b <= 0 || a == b) return 0.0
        return ((seq - a) / (b - a)).coerceIn(0.0, 1.0)
    }

    /** Kreuzungshalt (Zug wartet dort auf den Gegenzug), der noch vor oder unter dem Zug liegt - als Index in [stops]. */
    fun crossingIndex(trip: Trip?, stops: List<String>, pos: Double?): Int? {
        val t = trip ?: return null
        if (stops.size < 2) return null
        val dir = if (seqOf(stops.last()) > seqOf(stops.first())) 1 else -1
        val r = t.reasons.firstOrNull { r -> r.crossing && (pos == null || pos < 0 || (r.seq - pos) * dir >= -0.05) } ?: return null
        return stops.indexOfFirst { seqOf(it) == r.seq }.takeIf { it >= 0 }
    }

    /**
     * Anfahrt vor der Abfahrt: bis zu 3 Halte vor deinem Bahnhof [home] (aus Richtung des Zugs) und dein Bahnhof.
     * [toHigher] = der Zug faehrt Richtung Enschede. [pos] = Zugposition als seq (-1 = noch nicht unterwegs).
     */
    fun approach(home: String, toHigher: Boolean, pos: Double): Bar? {
        val h = seqOf(home); if (h <= 0) return null
        val dir = if (toHigher) 1 else -1
        val first = (h - 3 * dir).coerceIn(1, Stations.all.size)
        if (first == h) return null                                   // Startbahnhof der Linie: nichts davor
        val seqs = if (dir > 0) (first..h).toList() else (first downTo h).toList()
        val names = seqs.map { Stations.all[it - 1].name }
        val prog = if (pos < 0) 0.0 else fraction(names, pos)
        return Bar(names, prog, names.size - 1, null)
    }

    /** Leiste, Zug-Symbol, Bild und Chip setzen. [countdownTo] = Epoche der Abfahrt fuer den Countdown im Chip, sonst [chip]. */
    fun apply(ctx: Context, b: Notification.Builder, bar: Bar, chip: String?, countdownTo: Long? = null, badge: Badge? = null) {
        if (android.os.Build.VERSION.SDK_INT < 36 || bar.stops.size < 2) return
        val primary = ctx.getColor(R.color.widget_primary)
        val n = bar.stops.size
        val p = (bar.progress.coerceIn(0.0, 1.0) * TOTAL).toInt()
        // Strecke in Abschnitten (je Halt einer), selbst eingefaerbt: gefahren kraeftig (bei Verspaetung waermer), voraus hell
        val done = when { (bar.delay ?: 0) >= 6 -> RED; (bar.delay ?: 0) >= 3 -> AMBER; else -> primary }
        // Android ignoriert Transparenz bei Abschnitten -> hellen Ton selbst mischen (40 % Petrol, 60 % Weiss)
        val ahead = mix(primary, 0xFFFFFFFF.toInt(), 0.6f)
        val segs = mutableListOf<Notification.ProgressStyle.Segment>()
        for (i in 0 until n - 1) {
            val s0 = i * TOTAL / (n - 1); val s1 = (i + 1) * TOTAL / (n - 1)
            when {
                s1 <= p -> segs += Notification.ProgressStyle.Segment(s1 - s0).setColor(done)
                s0 >= p -> segs += Notification.ProgressStyle.Segment(s1 - s0).setColor(ahead)
                else -> {
                    segs += Notification.ProgressStyle.Segment(p - s0).setColor(done)
                    segs += Notification.ProgressStyle.Segment(s1 - p).setColor(ahead)
                }
            }
        }
        // Ziel am Ende: als Flagge (ein Punkt ganz am Rand ist kaum zu sehen); Start und Ziel brauchen keinen Punkt
        val flag = bar.mark == n - 1
        val points = (0 until n).filter { it in 1 until n - 1 || (it == bar.mark && !flag) }.map { i ->
            val c = when (i) { bar.crossing -> AMBER; bar.mark -> RED; else -> if (i * TOTAL / (n - 1) <= p) done else primary }
            Notification.ProgressStyle.Point(i * TOTAL / (n - 1)).setColor(c)
        }
        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgressSegments(segs)
            .setProgressPoints(points)
            .setProgress(p)
            .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_tracker))
        if (flag) style.setProgressEndIcon(Icon.createWithResource(ctx, R.drawable.ic_mark))
        if (bar.start) style.setProgressStartIcon(Icon.createWithResource(ctx, R.drawable.ic_start))
        b.setStyle(style)
        badge?.let { runCatching { b.setLargeIcon(Icon.createWithBitmap(badgeBitmap(ctx, it))) } }
        b.setRequestPromotedOngoing(true)
        if (countdownTo != null && countdownTo - System.currentTimeMillis() >= 2 * 60_000L) {
            // Chip zaehlt in Minuten bis zur Abfahrt herunter ("12 min"); eine laufende Uhr "14:36" saehe aus wie eine Uhrzeit
            b.setWhen(countdownTo).setShowWhen(true)
        } else if (countdownTo != null) b.setShortCriticalText("jetzt")
        else chip?.let { b.setShortCriticalText(it) }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, sh: Int) = (x shr sh) and 0xFF
        fun m(sh: Int) = (ch(a, sh) * (1 - t) + ch(b, sh) * t).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Gleisschild (Petrol, weisse Ziffer) oder Stempel (+N rot, puenktlich gruen) als Bild, 64 dp. */
    private fun badgeBitmap(ctx: Context, badge: Badge): android.graphics.Bitmap {
        val d = ctx.resources.displayMetrics.density
        val size = (64 * d).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val cx = size / 2f
        when (badge) {
            is Badge.Platform -> {
                paint.color = 0xFF0B5563.toInt()
                c.drawRoundRect(android.graphics.RectF(4 * d, 4 * d, size - 4 * d, size - 4 * d), 12 * d, 12 * d, paint)
                text.color = android.graphics.Color.WHITE
                text.textSize = 11 * d
                c.drawText("Gleis", cx, 19 * d, text)
                text.textSize = (if (badge.number.length > 2) 22 else 30) * d
                c.drawText(badge.number, cx, 50 * d, text)
            }
            is Badge.Delay -> {
                val late = badge.minutes >= 3
                val col = if (late) RED else 0xFF15803D.toInt()
                paint.style = android.graphics.Paint.Style.STROKE
                paint.color = col
                paint.strokeWidth = 3.5f * d
                c.drawCircle(cx, cx, size / 2f - 4 * d, paint)
                paint.strokeWidth = 1.2f * d
                c.drawCircle(cx, cx, size / 2f - 9 * d, paint)
                text.color = col
                c.save(); c.rotate(-12f, cx, cx)
                if (late) {
                    text.textSize = 22 * d
                    c.drawText("+${badge.minutes}", cx, cx + 6 * d, text)
                    text.textSize = 8 * d
                    c.drawText("MIN", cx, cx + 18 * d, text)
                } else {
                    paint.strokeWidth = 4 * d
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    val path = android.graphics.Path().apply {
                        moveTo(cx - 11 * d, cx + 1 * d); lineTo(cx - 3 * d, cx + 9 * d); lineTo(cx + 12 * d, cx - 8 * d)
                    }
                    c.drawPath(path, paint)
                }
                c.restore()
            }
        }
        return bmp
    }

    /** Chip unterwegs: Verspaetung ab +3, sonst die Ankunftszeit. */
    fun chipFor(delay: Int?, arr: String?): String? =
        if (delay != null && delay >= 3) "+$delay" else arr?.let { "an $it" }

    /** Countdown im Chip erst in den letzten 15 Minuten vor der Abfahrt. */
    fun countdown(depEpoch: Long, now: Long = System.currentTimeMillis()): Long? =
        depEpoch.takeIf { it > now && it - now <= 15 * 60_000L }
}
