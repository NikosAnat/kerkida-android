package app.kerkida.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Kerkida's own line icons, drawn for this app on a 24-unit grid. */
object KerkidaIcons {
    val Matches by lazy {
        line("matches", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18Z", "M12 7.6l4 2.9l-1.5 4.7h-5L8 10.5Z", "M12 3v4.6M21 10l-5 .5M17.5 19l-3-3.8M6.5 19l3-3.8M3 10l5 .5")
    }
    val Leagues by lazy {
        line("leagues", "M4 20h16", "M5 20v-5h4v5M10 20V9h4v11M15 20v-8h4v8", "M12 3.2l.9 1.7l1.9.3l-1.4 1.3l.3 1.9", "M12 3.2l-.9 1.7l-1.9.3l1.4 1.3l-.3 1.9")
    }
    val Search by lazy { line("search", "M10.8 4a6.8 6.8 0 1 0 0 13.6a6.8 6.8 0 0 0 0-13.6Z", "M16 16l4.4 4.4") }
    val Star by lazy { line("star", STAR) }
    val StarFilled by lazy { solid("star_filled", STAR) }
    val Settings by lazy {
        line("settings", "M4 7h9M18 7h2M4 17h2M11 17h9", "M15.5 4.8a2.2 2.2 0 1 0 0 4.4a2.2 2.2 0 0 0 0-4.4Z", "M8.5 14.8a2.2 2.2 0 1 0 0 4.4a2.2 2.2 0 0 0 0-4.4Z")
    }
    val Back by lazy { line("back", "M19 12H5.5", "M11 6l-6 6l6 6") }
    val ChevronLeft by lazy { line("chevron_left", "M14.5 6l-6 6l6 6") }
    val ChevronRight by lazy { line("chevron_right", "M9.5 6l6 6l-6 6") }
    val ChevronDown by lazy { line("chevron_down", "M6 9.5l6 6l6-6") }
    val ChevronUp by lazy { line("chevron_up", "M6 14.5l6-6l6 6") }
    val Calendar by lazy {
        line("calendar", "M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z", "M4 10h16M8.5 3v4M15.5 3v4")
    }
    val Refresh by lazy { line("refresh", "M19.5 12a7.5 7.5 0 1 1-2.3-5.4", "M19.5 3.8v4.4h-4.4") }
    val Pin by lazy {
        line("pin", "M12 21s7-6.1 7-11.5a7 7 0 1 0-14 0C5 14.9 12 21 12 21Z", "M12 7.2a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 0 0 0-4.8Z")
    }
    val Shield by lazy { line("shield", "M12 3l7.5 2.6v5.6c0 4.6-3.1 8-7.5 9.8c-4.4-1.8-7.5-5.2-7.5-9.8V5.6Z") }
    val Offline by lazy {
        line("offline", "M3.5 3.5l17 17", "M5 10.5a11 11 0 0 1 3.4-2.2M11 6.6a11 11 0 0 1 8 3.9", "M8.3 14a6 6 0 0 1 3-1.6M15.2 13.2a6 6 0 0 1 .9.8", "M12 18.2v.1")
    }
    val Close by lazy { line("close", "M6 6l12 12M18 6L6 18") }
    val Clock by lazy { line("clock", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17Z", "M12 7.5V12l3 2") }
    val Swap by lazy { line("swap", "M7 4v15M7 19l-3-3M7 19l3-3", "M17 20V5M17 5l-3 3M17 5l3 3") }
    val Card by lazy { solid("card", "M8.5 3.5h7a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19V5a1.5 1.5 0 0 1 1.5-1.5Z") }
    val Whistle by lazy {
        line("whistle", "M9 8h11.5v3.5l-4.8 1a6 6 0 1 1-6.7-4.5Z", "M9.5 14a.6.6 0 1 0 0 .1", "M12 4.5V6M7.5 5l.8 1.2")
    }
    val Info by lazy { line("info", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17Z", "M12 11v5.2M12 7.8v.1") }
    val Check by lazy { line("check", "M5 12.5l4.6 4.6L19 7.5") }
    val Flask by lazy { line("flask", "M9.5 3.5h5M10.5 3.5v6L5 18.5a1.4 1.4 0 0 0 1.2 2h11.6a1.4 1.4 0 0 0 1.2-2l-5.5-9v-6", "M7.8 15h8.4") }

    val Eye by lazy {
        line("eye", "M2.5 12s3.6-6.5 9.5-6.5s9.5 6.5 9.5 6.5s-3.6 6.5-9.5 6.5S2.5 12 2.5 12Z", "M12 9.2a2.8 2.8 0 1 0 0 5.6a2.8 2.8 0 0 0 0-5.6Z")
    }
    val EyeOff by lazy {
        line("eye_off", "M4 4l16 16", "M9.8 5.8a9 9 0 0 1 2.2-.3c5.9 0 9.5 6.5 9.5 6.5a17 17 0 0 1-3 3.7M6.3 7.6A16 16 0 0 0 2.5 12s3.6 6.5 9.5 6.5a9 9 0 0 0 4-.9", "M10 10a2.8 2.8 0 0 0 4 4")
    }
    val Basketball by lazy {
        line("basketball", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17Z", "M3.5 12h17M12 3.5v17", "M6 6c2.6 3.3 2.6 8.7 0 12M18 6c-2.6 3.3-2.6 8.7 0 12")
    }
    val Tennis by lazy {
        line("tennis", "M10 3.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 0 0 0-13Z", "M14.7 14.7l5.8 5.8", "M5.4 5.4l9.2 9.2M3.8 10.3l5.9 5.9M10.3 3.8l5.9 5.9")
    }
    val RaceFlag by lazy { line("race_flag", "M5 21V4", "M5 4.5h14v9H5", "M9.7 4.5v9M14.3 4.5v9M5 9h14") }
    val AmericanFootball by lazy {
        line("american_football", "M4.5 19.5c-1.5-6 .5-11 4-13.5s7-2.7 11-2c1.5 6-.5 11-4 13.5s-7 2.7-11 2Z", "M9.5 14.5l5-5", "M10.3 11.2l2.5 2.5M12.2 9.3l2.5 2.5")
    }
    val Hockey by lazy { line("hockey", "M15 3l-4.5 14.5H4.5a1.5 1.5 0 0 0 0 3H11.5L17.5 3", "M16 18h4.5v2.5H16Z") }
    val Baseball by lazy {
        line("baseball", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17Z", "M6.2 5.8c2.7 3.6 2.7 8.8 0 12.4M17.8 5.8c-2.7 3.6-2.7 8.8 0 12.4")
    }
    val Glove by lazy {
        line("glove", "M7 11V7.5A3.5 3.5 0 0 1 10.5 4h3A4.5 4.5 0 0 1 18 8.5V14a4 4 0 0 1-4 4h-3a4 4 0 0 1-4-4Z", "M7 10.5H5.5a2 2 0 0 0 0 4H7", "M8 20.5h8.5")
    }
    val Volleyball by lazy {
        line("volleyball", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17Z", "M12 12c0-3 1.2-6 3.7-7.7M12 12c-2.6 1.5-5.8 2-8.4.8M12 12c2.6 1.5 4.6 4 5 7")
    }
    val Handball by lazy {
        line("handball", "M15.5 3.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7Z", "M12.5 9L6 13.5a2 2 0 0 0-.8 2.1L6.5 20.5", "M9.5 15.5l4 5")
    }
    val WaterPolo by lazy {
        line("water_polo", "M12 3.5a5 5 0 1 0 0 10a5 5 0 0 0 0-10Z", "M3 17c1.5-1.2 3-1.2 4.5 0s3 1.2 4.5 0s3-1.2 4.5 0s3 1.2 4.5 0", "M3 20.5c1.5-1.2 3-1.2 4.5 0s3 1.2 4.5 0s3-1.2 4.5 0s3 1.2 4.5 0")
    }
    val Golf by lazy { line("golf", "M9 18V3.5l8 3.5l-8 3.5", "M4.5 19c0-1.1 2-2 4.5-2s4.5.9 4.5 2s-2 2-4.5 2s-4.5-.9-4.5-2Z") }
    val Rugby by lazy {
        line("rugby", "M12 3c4.2 2 6 5.4 6 9s-1.8 7-6 9c-4.2-2-6-5.4-6-9s1.8-7 6-9Z", "M12 7.5v9M10 9.5h4M10 12h4M10 14.5h4")
    }
    val Lacrosse by lazy {
        line("lacrosse", "M16.5 3a4 4 0 0 1 4 4c0 2.2-2 3.7-3.8 3.7L13 8.2C13 5.5 14.4 3 16.5 3Z", "M14.5 10L4 20.5", "M15 5.5l3.5 3.5M18 4.2l2 2", "M5.5 11a2 2 0 1 0 0 4a2 2 0 0 0 0-4Z")
    }
    val SoundOn by lazy { line("sound_on", "M4 9.5v5h3.5L12 18.5v-13L7.5 9.5Z", "M15.5 9a4 4 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11") }
    val SoundOff by lazy { line("sound_off", "M4 9.5v5h3.5L12 18.5v-13L7.5 9.5Z", "M16 9.5l5 5M21 9.5l-5 5") }
    val Trophy by lazy {
        line("trophy", "M8 4h8v6a4 4 0 0 1-8 0Z", "M8 5.5H4.5c0 3 1.3 4.8 3.8 5.2M16 5.5h3.5c0 3-1.3 4.8-3.8 5.2", "M12 14v3.5M8.5 20h7M9.5 20c0-1.5 1-2.5 2.5-2.5s2.5 1 2.5 2.5")
    }
    val Tv by lazy {
        line("tv", "M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Z", "M8.5 3l3.5 4l3.5-4")
    }

    private const val STAR = "M12 3.6l2.6 5.3l5.8.8l-4.2 4.1l1 5.8L12 16.9l-5.2 2.7l1-5.8l-4.2-4.1l5.8-.8Z"

    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun solid(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = SolidColor(Color.Black),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.2f,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
