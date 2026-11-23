package app.kerkida.ui.common

/** Crests replaced by a better version of the same emblem. */
object CrestOverrides {
    /** ESPN's Olympiacos crest is see-through, so the laurel head lost its white background on dark screens. */
    private const val OLYMPIACOS_WHITE = "https://r2.thesportsdb.com/images/media/team/badge/xckasq1721291508.png"
    private val olympiacosEspn = listOf("/teamlogos/soccer/500/435.png", "/teamlogos/soccer/500-dark/435.png")

    fun resolve(url: String?): String? =
        if (url != null && olympiacosEspn.any { it in url }) OLYMPIACOS_WHITE else url
}
