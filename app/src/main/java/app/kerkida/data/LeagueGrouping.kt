package app.kerkida.data

import app.kerkida.data.model.Sport
import java.text.Collator
import java.util.Locale

data class CountryGroup(val key: String, val code: String?, val title: String, val leagues: List<CatalogLeague>)

data class SportGroup(val sport: Sport, val countries: List<CountryGroup>) {
    val leagues: List<CatalogLeague> get() = countries.flatMap { it.leagues }
}

/** Orders the league list by sport, then by country, with Greece and international competitions first. */
object LeagueGrouping {
    const val INTERNATIONAL = "INT"

    /** The catalog leaves the country empty for many domestic leagues of the non-football sports. */
    private val domestic = mapOf(
        "football/cfl" to "CA", "football/college-football" to "US", "football/nfl" to "US",
        "football/ufl" to "US", "football/xfl" to "US",
        "australian-football/afl" to "AU",
        "baseball/college-baseball" to "US", "baseball/college-softball" to "US", "baseball/llb" to "US",
        "baseball/lls" to "US", "baseball/mlb" to "US", "baseball/dominican-winter-league" to "DO",
        "baseball/mexican-winter-league" to "MX", "baseball/puerto-rican-winter-league" to "PR",
        "baseball/venezuelan-winter-league" to "VE",
        "basketball/mens-college-basketball" to "US", "basketball/womens-college-basketball" to "US",
        "basketball/nba" to "US", "basketball/nba-development" to "US", "basketball/nba-summer-california" to "US",
        "basketball/nba-summer-golden-state" to "US", "basketball/nba-summer-las-vegas" to "US",
        "basketball/nba-summer-orlando" to "US", "basketball/nba-summer-sacramento" to "US",
        "basketball/nba-summer-utah" to "US", "basketball/wnba" to "US", "basketball/nbl" to "AU",
        "golf/champions-tour" to "US", "golf/lpga" to "US", "golf/ntw" to "US", "golf/pga" to "US",
        "golf/tgl" to "US", "golf/wtgl" to "US",
        "hockey/mens-college-hockey" to "US", "hockey/womens-college-hockey" to "US", "hockey/nhl" to "US",
        "hockey/pwhl" to "US",
        "lacrosse/mens-college-lacrosse" to "US", "lacrosse/womens-college-lacrosse" to "US",
        "lacrosse/nll" to "US", "lacrosse/pll" to "US",
        "racing/irl" to "US", "racing/nascar-premier" to "US", "racing/nascar-secondary" to "US",
        "racing/nascar-truck" to "US",
        "rugby/267979" to "GB", "rugby/270559" to "FR", "rugby/2009" to "AR", "rugby/289279" to "AR",
        "rugby/270563" to "NZ", "rugby/289262" to "US",
        "volleyball/mens-college-volleyball" to "US", "volleyball/womens-college-volleyball" to "US",
    )

    /** Two-letter country code, or null for an international competition. */
    fun countryOf(league: CatalogLeague): String? = league.country ?: domestic[league.id]

    private const val WOMEN = 1_000
    private const val CUP = 900
    private const val SUPER_CUP = 920
    private const val UNRANKED = 500

    private val knownLevels = mapOf(
        "grl.gbl" to 10, "grl.ts.elite" to 20, "grl.ts.vlm" to 10, "grl.ts.vlw" to WOMEN + 10,
        "grl.4640" to 20, "grl.5830" to CUP, "grl.las.a" to 40,
        "usa.usl.1" to 20, "usa.usl.l1" to 30, "usa.usl.l1.cup" to CUP, "usa.nwsl" to WOMEN + 10,
        "usa.nwsl.cup" to WOMEN + CUP, "usa.w.usl.1" to WOMEN + 20,
        "basketball/nba" to 10, "basketball/nbl" to 10, "basketball/nba-development" to 20, "basketball/wnba" to WOMEN + 10,
        "football/nfl" to 10, "football/cfl" to 10, "football/ufl" to 20, "football/xfl" to 20,
        "baseball/mlb" to 10, "baseball/llb" to 60, "baseball/lls" to WOMEN + 60, "baseball/college-softball" to WOMEN + 30,
        "hockey/nhl" to 10, "hockey/pwhl" to WOMEN + 10,
        "golf/pga" to 10, "golf/ntw" to 20, "golf/champions-tour" to 30, "golf/tgl" to 40,
        "golf/lpga" to WOMEN + 10, "golf/wtgl" to WOMEN + 40,
        "racing/nascar-premier" to 10, "racing/irl" to 10, "racing/nascar-secondary" to 20, "racing/nascar-truck" to 30,
        "lacrosse/nll" to 10, "lacrosse/pll" to 10,
    )
    private val superCup = Regex("super_?c|supercop|supercup|charity|campeon|trofeo|joan_gamper|world_challenge")
    private val cup = Regex("cup|copa|coppa|pokal|taca|coupe|\\.fa|tennents|\\.cis|challenge|trophy|\\.open")
    private val tierNumber = Regex("""\.(\d)(?:\.|$)""")
    private val localGamma = Regex("""^grl\.5[89]""")

    /** England comes before Scotland within the UK. Greek lower divisions sit with the Super League. */
    private fun nation(league: CatalogLeague): String {
        if (league.id.startsWith("grl.")) return "gre"
        return if ('/' in league.id) "" else league.id.substringBefore('.')
    }

    /** Lower numbers are higher divisions. Each gender runs league tiers first and then its cups. */
    fun level(league: CatalogLeague): Int {
        knownLevels[league.id]?.let { return it }
        val id = league.id
        val name = league.name.lowercase()
        val women = ".w." in id || "womens" in id || "women" in name || "femenil" in name
        val base = when {
            localGamma.containsMatchIn(id) -> 30
            id.startsWith("grl.ach.a") -> 40
            id.startsWith("grl.ach.b") -> 50
            id.startsWith("grl.ach.g") -> 60
            "college" in id || ".ncaa." in id -> 70
            "summer" in id -> 80
            ".camp." in id -> 85
            superCup.containsMatchIn(id) -> SUPER_CUP
            cup.containsMatchIn(id) -> CUP
            else -> {
                val tier = tierNumber.find(id)?.groupValues?.get(1)?.toInt()
                val playoff = "relegation" in id || ".ply." in id
                when {
                    tier != null -> tier * 10 + if (playoff) 5 else 0
                    playoff -> 15
                    else -> UNRANKED
                }
            }
        }
        return base + if (women) WOMEN else 0
    }

    /** Cups, super cups and friendlies are played without a league table. */
    fun isKnockout(league: CatalogLeague) = "friendly" in league.id || level(league) % WOMEN >= CUP

    fun group(
        leagues: List<CatalogLeague>,
        locale: Locale,
        international: String,
        countryName: (String) -> String,
    ): List<SportGroup> {
        val collator = Collator.getInstance(locale)
        return leagues.groupBy { it.sport }
            .toSortedMap(compareBy { it.ordinal })
            .map { (sport, ofSport) ->
                val countries = ofSport.groupBy(::countryOf).map { (code, inCountry) ->
                    CountryGroup(
                        key = "${sport.name}/${code ?: INTERNATIONAL}",
                        code = code,
                        title = code?.let(countryName) ?: international,
                        // Numbers are padded so that group 2 comes before group 10.
                        leagues = inCountry.sortedWith(
                            compareBy<CatalogLeague>({ nation(it) }, { level(it) })
                                .thenBy(collator) { league -> league.name.replace(Regex("\\d+")) { it.value.padStart(4, '0') } },
                        ),
                    )
                }.sortedWith(
                    compareBy<CountryGroup> { if (it.code == "GR") 0 else if (it.code == null) 1 else 2 }
                        .thenBy(collator) { it.title },
                )
                SportGroup(sport, countries)
            }
    }
}
