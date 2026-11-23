package app.kerkida.data.demo

import app.kerkida.data.DataException
import app.kerkida.data.ProviderInfo
import app.kerkida.data.Sourced
import app.kerkida.data.SportsDataProvider
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Side
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Offline sample data. Every club, league, player and score here is invented.
 * All ids start with "demo-", league names carry a DEMO tag, and the UI shows a permanent banner.
 */
class DemoSportsDataProvider(
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SportsDataProvider {

    override val info = ProviderInfo(id = ID, displayName = "Demo", isDemo = true)

    private class DemoLeague(val ref: LeagueRef, val clubs: List<DemoClub>)
    private class DemoClub(val team: TeamRef, val shortName: String, val town: String, val ground: String, val founded: Int)

    private val leagues: List<DemoLeague> = listOf(
        league(
            index = 1,
            name = "DEMO · Πρωτάθλημα Αιγαίου",
            clubs = listOf(
                Triple("Θαλασσινοί Ακτής", "ΘΑΛ", "Ακτή"),
                Triple("Αετοί Κάμπου", "ΑΕΤ", "Κάμπος"),
                Triple("Λύκοι Βορρά", "ΛΥΚ", "Βόρεια"),
                Triple("Ήλιος Νότου", "ΗΛΙ", "Νότια"),
                Triple("Κύμα Λιμανιού", "ΚΥΜ", "Λιμάνι"),
                Triple("Δάφνη Λόφου", "ΔΑΦ", "Λόφος"),
                Triple("Πέτρα Φάρου", "ΠΕΤ", "Φάρος"),
                Triple("Άνεμοι Νησιού", "ΑΝΕ", "Νησί"),
            ),
        ),
        league(
            index = 2,
            name = "DEMO · Harbour League",
            clubs = listOf(
                Triple("Saltmere Rovers", "SAL", "Saltmere"),
                Triple("Ashby Forge", "ASH", "Ashby"),
                Triple("Kestrel Vale", "KES", "Kestrel"),
                Triple("Marlow Tide", "MAR", "Marlow"),
                Triple("Oakhaven Town", "OAK", "Oakhaven"),
                Triple("Brindle Athletic", "BRI", "Brindle"),
                Triple("Northgate Lanterns", "NOR", "Northgate"),
                Triple("Fennwick United", "FEN", "Fennwick"),
            ),
        ),
    )

    private fun league(index: Int, name: String, clubs: List<Triple<String, String, String>>): DemoLeague {
        val ref = LeagueRef(id = "demo-l$index", name = name)
        return DemoLeague(
            ref,
            clubs.mapIndexed { position, (club, short, town) ->
                DemoClub(
                    team = TeamRef(id = "demo-t$index-$position", name = club),
                    shortName = short,
                    town = town,
                    ground = "$town Arena (demo)",
                    founded = 1920 + index * 7 + position * 5,
                )
            },
        )
    }

    private fun today(): LocalDate = clock().atZone(zone()).toLocalDate()

    private fun <T> sourced(data: T) = Sourced(data, clock())

    /** Round-robin pairings for one round, home and away swapped in the second half. */
    private fun pairings(size: Int, round: Int): List<Pair<Int, Int>> {
        val rounds = size - 1
        val zeroBased = (round - 1).mod(rounds * 2)
        val leg = zeroBased % rounds
        val order = (1 until size).toMutableList()
        repeat(leg) { order.add(0, order.removeAt(order.lastIndex)) }
        val ring = listOf(0) + order
        return (0 until size / 2).map { slot ->
            val a = ring[slot]
            val b = ring[size - 1 - slot]
            val swap = (slot + leg) % 2 == 1
            val pair = if (swap) b to a else a to b
            if (zeroBased >= rounds) pair.second to pair.first else pair
        }
    }

    private fun roundCount(league: DemoLeague) = (league.clubs.size - 1) * 2

    private fun roundOn(league: DemoLeague, date: LocalDate) = date.toEpochDay().mod(roundCount(league)) + 1

    private fun dateOfRound(league: DemoLeague, round: Int, reference: LocalDate): LocalDate =
        reference.plusDays((round - roundOn(league, reference)).toLong())

    private fun buildMatch(league: DemoLeague, date: LocalDate, slot: Int): Match {
        val round = roundOn(league, date)
        val (homeIndex, awayIndex) = pairings(league.clubs.size, round)[slot]
        val leagueNumber = leagues.indexOf(league)
        val kickoffTime = LocalTime.of(13 + (slot * 2 + leagueNumber) % 9, if (slot % 2 == 0) 0 else 30)
        val kickoff = date.atTime(kickoffTime).atZone(zone()).toInstant()
        val random = seeded(league, date, slot)
        val finalHome = random.nextInt(0, 4)
        val finalAway = random.nextInt(0, 3)
        val elapsed = Duration.between(kickoff, clock()).toMinutes()

        val status: MatchStatus
        var minute: Int? = null
        var homeScore: Int? = null
        var awayScore: Int? = null
        when {
            elapsed < 0 -> status = MatchStatus.SCHEDULED
            elapsed >= MATCH_LENGTH_MINUTES -> {
                status = MatchStatus.FINISHED
                homeScore = finalHome
                awayScore = finalAway
            }
            else -> {
                val clockMinute = when {
                    elapsed <= 45 -> elapsed.toInt()
                    elapsed <= 60 -> 45
                    else -> (elapsed - 15).toInt().coerceAtMost(90)
                }
                status = if (elapsed in 46..60) MatchStatus.HALF_TIME else MatchStatus.LIVE
                minute = clockMinute
                val goals = goalEvents(finalHome, finalAway, league, date, slot).filter { (it.minute ?: 0) <= clockMinute }
                homeScore = goals.count { it.side == Side.HOME }
                awayScore = goals.count { it.side == Side.AWAY }
            }
        }

        return Match(
            id = "demo-m${leagueNumber + 1}-${date.toEpochDay()}-$slot",
            league = league.ref,
            home = league.clubs[homeIndex].team,
            away = league.clubs[awayIndex].team,
            kickoff = kickoff,
            date = date,
            status = status,
            minute = minute,
            homeScore = homeScore,
            awayScore = awayScore,
            round = round,
            season = "DEMO",
            venue = league.clubs[homeIndex].ground,
        )
    }

    private fun seeded(league: DemoLeague, date: LocalDate, slot: Int) =
        Random(date.toEpochDay() * 31 + leagues.indexOf(league) * 1009 + slot)

    private fun goalEvents(home: Int, away: Int, league: DemoLeague, date: LocalDate, slot: Int): List<MatchEvent> {
        val random = seeded(league, date, slot + 100)
        val goals = List(home) { Side.HOME } + List(away) { Side.AWAY }
        return goals.map { side ->
            MatchEvent(
                minute = random.nextInt(3, 90),
                type = MatchEventType.GOAL,
                side = side,
                player = playerName(random),
            )
        }
    }

    private fun playerName(random: Random): String =
        "${FIRST_NAMES[random.nextInt(FIRST_NAMES.size)]} ${LAST_NAMES[random.nextInt(LAST_NAMES.size)]} (demo)"

    private fun eventsFor(match: Match, league: DemoLeague, date: LocalDate, slot: Int): List<MatchEvent> {
        if (match.status == MatchStatus.SCHEDULED) return emptyList()
        val rng = seeded(league, date, slot)
        val finalHome = rng.nextInt(0, 4)
        val finalAway = rng.nextInt(0, 3)
        val limit = match.minute ?: 90
        val random = seeded(league, date, slot + 200)
        val cards = List(random.nextInt(1, 4)) {
            MatchEvent(
                minute = random.nextInt(10, 88),
                type = if (random.nextInt(8) == 0) MatchEventType.RED_CARD else MatchEventType.YELLOW_CARD,
                side = if (random.nextBoolean()) Side.HOME else Side.AWAY,
                player = playerName(random),
            )
        }
        return (goalEvents(finalHome, finalAway, league, date, slot) + cards)
            .filter { (it.minute ?: 0) <= limit }
            .sortedBy { it.minute }
    }

    private fun standingsFor(league: DemoLeague): Standings {
        val today = today()
        val tally = league.clubs.associate { it.team.id to IntArray(6) }
        for (round in 1..roundCount(league)) {
            val date = dateOfRound(league, round, today).let { if (it > today) it.minusDays(roundCount(league).toLong()) else it }
            repeat(league.clubs.size / 2) { slot ->
                val match = buildMatch(league, date, slot)
                val home = match.homeScore
                val away = match.awayScore
                if (match.status == MatchStatus.FINISHED && home != null && away != null) {
                    record(tally.getValue(match.home.id), home, away)
                    record(tally.getValue(match.away.id), away, home)
                }
            }
        }
        val rows = league.clubs
            .map { club -> club to tally.getValue(club.team.id) }
            .sortedWith(
                compareByDescending<Pair<DemoClub, IntArray>> { it.second[1] * 3 + it.second[2] }
                    .thenByDescending { it.second[4] - it.second[5] }
                    .thenByDescending { it.second[4] }
                    .thenBy { it.first.team.name },
            )
            .mapIndexed { index, (club, t) ->
                StandingRow(
                    rank = index + 1,
                    team = club.team,
                    played = t[0],
                    won = t[1],
                    drawn = t[2],
                    lost = t[3],
                    goalsFor = t[4],
                    goalsAgainst = t[5],
                    goalDifference = t[4] - t[5],
                    points = t[1] * 3 + t[2],
                )
            }
        return Standings(league.ref, "DEMO", rows, isPartial = false)
    }

    private fun record(t: IntArray, scored: Int, conceded: Int) {
        t[0]++
        when {
            scored > conceded -> t[1]++
            scored == conceded -> t[2]++
            else -> t[3]++
        }
        t[4] += scored
        t[5] += conceded
    }

    private fun leagueById(id: String) = leagues.firstOrNull { it.ref.id == id } ?: throw DataException.NotFound()

    private fun clubById(id: String): Pair<DemoLeague, DemoClub> {
        for (league in leagues) {
            league.clubs.firstOrNull { it.team.id == id }?.let { return league to it }
        }
        throw DataException.NotFound()
    }

    private fun DemoClub.toTeam(league: DemoLeague) = Team(
        id = team.id,
        name = team.name,
        shortName = shortName,
        stadium = ground,
        location = town,
        foundedYear = founded,
        league = league.ref,
        description = DESCRIPTION,
    )

    private fun matchesOn(date: LocalDate): List<Match> =
        leagues.flatMap { league -> List(league.clubs.size / 2) { slot -> buildMatch(league, date, slot) } }
            .sortedBy { it.kickoff }

    override fun matchDay(date: LocalDate, priorityLeagueIds: List<String>, refresh: Boolean): Flow<MatchDay> = flow {
        emit(MatchDay(matchesOn(date), clock(), loadedSources = 1, totalSources = 1))
    }

    override suspend fun matchDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val parts = matchId.removePrefix("demo-m").split("-")
        if (parts.size != 3) throw DataException.NotFound()
        val league = leagues.getOrNull((parts[0].toIntOrNull() ?: 0) - 1) ?: throw DataException.NotFound()
        val date = parts[1].toLongOrNull()?.let(LocalDate::ofEpochDay) ?: throw DataException.NotFound()
        val slot = parts[2].toIntOrNull()?.takeIf { it in 0 until league.clubs.size / 2 } ?: throw DataException.NotFound()
        val match = buildMatch(league, date, slot)
        return sourced(
            MatchDetails(
                match = match,
                events = eventsFor(match, league, date, slot),
                standings = standingsFor(league),
                description = DESCRIPTION,
            ),
        )
    }

    override suspend fun featuredLeagues(): Sourced<List<LeagueRef>> = sourced(leagues.map { it.ref })

    override suspend fun league(leagueId: String, refresh: Boolean): Sourced<LeagueDetails> {
        val league = leagueById(leagueId)
        return sourced(LeagueDetails(league.ref, "DEMO", DESCRIPTION))
    }

    override suspend fun standings(leagueId: String, season: String?, refresh: Boolean): Sourced<Standings> =
        sourced(standingsFor(leagueById(leagueId)))

    override suspend fun fixtures(
        leagueId: String,
        season: String?,
        round: Int?,
        refresh: Boolean,
    ): Sourced<LeagueFixtures> {
        val league = leagueById(leagueId)
        val today = today()
        val chosen = (round ?: roundOn(league, today)).coerceIn(1, roundCount(league))
        val date = dateOfRound(league, chosen, today)
        return sourced(
            LeagueFixtures(
                league = league.ref,
                season = "DEMO",
                round = chosen,
                matches = List(league.clubs.size / 2) { slot -> buildMatch(league, date, slot) },
                hasPreviousRound = chosen > 1,
                hasNextRound = chosen < roundCount(league),
            ),
        )
    }

    override suspend fun team(teamId: String, refresh: Boolean): Sourced<Team> {
        val (league, club) = clubById(teamId)
        return sourced(club.toTeam(league))
    }

    override suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val (league, _) = clubById(teamId)
        val today = today()
        val nearby = (-10..10).flatMap { offset ->
            val date = today.plusDays(offset.toLong())
            List(league.clubs.size / 2) { slot -> buildMatch(league, date, slot) }
        }.filter { it.home.id == teamId || it.away.id == teamId }
        val recent = nearby.filter { it.status == MatchStatus.FINISHED }.sortedByDescending { it.kickoff }.take(5)
        val upcoming = nearby.filter { it.status != MatchStatus.FINISHED }.sortedBy { it.kickoff }.take(5)
        return sourced(TeamMatches(recent, upcoming))
    }

    override suspend fun search(query: String): Sourced<SearchResults> {
        val needle = query.trim()
        val teams = leagues.flatMap { league ->
            league.clubs.filter { it.team.name.contains(needle, ignoreCase = true) }.map { it.toTeam(league) }
        }
        val found = leagues.filter { it.ref.name.contains(needle, ignoreCase = true) }.map { it.ref }
        return sourced(SearchResults(teams, found))
    }

    companion object {
        const val ID = "demo"
        private const val MATCH_LENGTH_MINUTES = 110L
        private const val DESCRIPTION =
            "Δοκιμαστικά δεδομένα: οι ομάδες, οι παίκτες και τα αποτελέσματα είναι φανταστικά. / " +
                "Demo data: these teams, players and results are invented."
        private val FIRST_NAMES = listOf("Νίκος", "Alex", "Μάριος", "Sam", "Πέτρος", "Jordan", "Στέλιος", "Robin")
        private val LAST_NAMES = listOf("Δοκιμής", "Sample", "Παράδειγμα", "Testwell", "Πρότυπος", "Placeholder")
    }
}
