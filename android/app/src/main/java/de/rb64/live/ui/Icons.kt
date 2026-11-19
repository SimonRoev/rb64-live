package de.rb64.live.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Linien-Icons wie in den Entwuerfen: 24er Raster, Linienstaerke 1,75, keine Emojis/Unicode-Zeichen. */
object RbIcons {
    private fun line(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    private fun filled(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d -> addPath(addPathNodes(d), fill = SolidColor(Color.Black)) }
    }.build()

    val swap = line("swap", "M7 4v16", "M3 8l4-4 4 4", "M17 20V4", "M21 16l-4 4-4-4")
    val refresh = line("refresh", "M21 12a9 9 0 1 1-3-6.7", "M21 4v5h-5")
    val plus = line("plus", "M12 5v14M5 12h14")
    val play = filled("play", "M8 5v14l11-7z")
    val stop = filled("stop", "M7 7h10v10H7z")
    val trend = line("trend", "M3 17l6-6 4 4 8-8", "M14 7h7v7")
    val warning = line("warning", "M12 3l9 16H3z", "M12 10v4", "M12 17h.01")
    val chevronUp = line("chevronUp", "M6 15l6-6 6 6")
    val chevronDown = line("chevronDown", "M6 9l6 6 6-6")
    val arrowRight = line("arrowRight", "M5 12h14", "M13 6l6 6-6 6")
    val back = line("back", "M19 12H5", "M11 6l-6 6 6 6")
    val close = line("close", "M6 6l12 12M18 6L6 18")
    val more = line("more", "M12 5h.01", "M12 12h.01", "M12 19h.01")
    val lock = line("lock", "M6 11h12v9H6z", "M8 11V8a4 4 0 0 1 8 0v3")
    val check = line("check", "M5 12l5 5 9-10")
    val location = line("location", "M12 21s-7-6.5-7-12a7 7 0 0 1 14 0c0 5.5-7 12-7 12z", "M10 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val departures = line("departures", "M8 3h8a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z",
        "M5 11h14", "M8 21l2-4M16 21l-2-4")
    val route = line("route", "M4 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M16 19a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M6 7v4a4 4 0 0 0 4 4h4a4 4 0 0 1 4 4")
    val rides = line("rides", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 7v5l3 2")
    val settings = line("settings", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        "M14 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M8 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M16 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val pin = line("pin", "M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7")
    val live = line("live", "M11.99 12h.01", "M8.5 8.5a5 5 0 0 0 0 7", "M15.5 8.5a5 5 0 0 1 0 7",
        "M5.6 5.6a9 9 0 0 0 0 12.8", "M18.4 5.6a9 9 0 0 1 0 12.8")
    val clock = line("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 7v5l3 2")
    val delete = line("delete", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val signal = line("signal", "M9 3h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M11 7a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M11 11a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M12 16v5")
    val crowd = line("crowd", "M5 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M13 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M2 20c0-3 2.5-5 6-5s6 2 6 5", "M14 15.2c.6-.1 1.3-.2 2-.2 3.5 0 6 2 6 5")
    val announce = line("announce", "M3 10v4h4l6 4V6L7 10H3z", "M17 9a4 4 0 0 1 0 6")
}
