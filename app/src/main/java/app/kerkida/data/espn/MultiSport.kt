package app.kerkida.data.espn

import app.kerkida.data.DataException
import app.kerkida.data.LeagueCatalog
import app.kerkida.data.Sourced
import app.kerkida.data.espn.EspnParsing.flag
import app.kerkida.data.espn.EspnParsing.list
import app.kerkida.data.espn.EspnParsing.logo
import app.kerkida.data.espn.EspnParsing.num
import app.kerkida.data.espn.EspnParsing.obj
import app.kerkida.data.espn.EspnParsing.str
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private enum class FeedKind { TEAMS, EUROLEAGUE, TENNIS, RACING, FIGHTS, GOLF }

private data class SportLeague(val ref: LeagueRef, val kind: FeedKind)

private const val PARALLEL_LEAGUES = 6

/** Parsing for sports other than football. Betting fields are never read. */
internal object MultiSportParsing {
    private fun competitorsOf(competition: JsonObject) = competition["competitors"].list().mapNotNull { it.obj() }

    private fun number(value: Double?) = value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

    /** "29-20 31-25 38-33" from each side's period scores. */
    fun periods(first: JsonObject, second: JsonObject): String? {
        val a = first["linescores"].list().mapNotNull { it.obj()?.num("value") }
        val b = second["linescores"].list().mapNotNull { it.obj()?.num("value") }
        if (a.isEmpty() || a.size != b.size) return null
        return a.indices.joinToString("  ") { "${number(a[it])}-${number(b[it])}" }
    }

    /** A team-sport game. Team ids carry the league, because numbers repeat between sports. */
    fun teamGame(event: JsonObject, league: LeagueRef): Match? {
        val base = EspnParsing.toMatch(event, league, null) ?: return null
        val competition = event["competitions"].list().firstOrNull().obj() ?: return null
        val sides = competitorsOf(competition)
        val home = sides.firstOrNull { it.str("homeAway") == "home" }
        val away = sides.firstOrNull { it.str("homeAway") == "away" }
        val status = competition["status"].obj() ?: event["status"].obj()
        return base.copy(
            home = base.home.copy(id = "${league.id}:${base.home.id}"),
            away = base.away.copy(id = "${league.id}:${base.away.id}"),
            minute = null,
            statusDetail = status?.get("type").obj()?.str("shortDetail") ?: base.statusDetail,
            resultText = if (home != null && away != null && base.homeScore != null) periods(home, away) else null,
        )
    }

    private fun personName(side: JsonObject): String? =
        side["athlete"].obj()?.str("displayName") ?: side["roster"].obj()?.str("shortDisplayName") ?: side["team"].obj()?.str("displayName")

    private fun personFlag(side: JsonObject): String? = side["athlete"].obj()?.get("flag").obj()?.str("href")

    private fun setsWon(first: JsonObject, second: JsonObject): Pair<Int, Int> {
        val a = first["linescores"].list().mapNotNull { it.obj() }
        val b = second["linescores"].list().mapNotNull { it.obj() }
        var one = 0
        var two = 0
        a.indices.filter { it < b.size }.forEach { index ->
            val x = a[index].num("value") ?: return@forEach
            val y = b[index].num("value") ?: return@forEach
            if (x > y) one++ else if (y > x) two++
        }
        return one to two
    }

    /** A tennis match or a fight: two people instead of two clubs, with no team page. */
    fun duel(competition: JsonObject, league: LeagueRef, sets: Boolean, note: String?): Match? {
        val id = competition.str("id") ?: return null
        val sides = competitorsOf(competition).sortedBy { it.num("order") ?: 9.0 }
        if (sides.size != 2) return null
        val firstName = personName(sides[0]) ?: return null
        val secondName = personName(sides[1]) ?: return null
        val statusObject = competition["status"].obj()
        val status = EspnParsing.statusOf(statusObject)
        val played = status == MatchStatus.LIVE || status == MatchStatus.FINISHED
        val score = if (sets && played) setsWon(sides[0], sides[1]) else null
        val winner = when {
            sides[0].flag("winner") -> firstName
            sides[1].flag("winner") -> secondName
            else -> null
        }
        val kickoff = EspnParsing.instant(competition.str("date"))
        return Match(
            id = EspnParsing.matchId(league.id, id),
            league = league,
            home = TeamRef("", firstName, personFlag(sides[0])),
            away = TeamRef("", secondName, personFlag(sides[1])),
            kickoff = kickoff,
            date = null,
            status = status,
            statusDetail = note ?: statusObject?.get("type").obj()?.str("shortDetail"),
            homeScore = score?.first,
            awayScore = score?.second,
            venue = competition["venue"].obj()?.str("fullName"),
            resultText = if (sets) periods(sides[0], sides[1]) else winner,
            stage = competition["round"].obj()?.str("displayName"),
        )
    }

    /** One racing session, such as qualifying or the race. */
    fun session(event: JsonObject, competition: JsonObject, league: LeagueRef): Match? {
        val id = competition.str("id") ?: return null
        val race = event.str("shortName") ?: event.str("name") ?: return null
        val part = competition["type"].obj()?.str("abbreviation") ?: return null
        val statusObject = competition["status"].obj()
        val status = EspnParsing.statusOf(statusObject)
        val leader = competitorsOf(competition).minByOrNull { it.num("order") ?: 999.0 }
        return Match(
            id = EspnParsing.matchId(league.id, id),
            league = league,
            home = TeamRef("", race),
            away = TeamRef("", part),
            kickoff = EspnParsing.instant(competition.str("date")),
            date = null,
            status = status,
            statusDetail = statusObject?.get("type").obj()?.str("shortDetail"),
            venue = event["circuit"].obj()?.str("fullName"),
            eventTitle = "$race · $part",
            resultText = if (status == MatchStatus.FINISHED) leader?.let(::personName) else null,
        )
    }

    /** A golf tournament, shown as one event with its current leader. */
    fun tournament(event: JsonObject, competition: JsonObject, league: LeagueRef): Match? {
        val id = competition.str("id") ?: event.str("id") ?: return null
        val name = event.str("shortName") ?: event.str("name") ?: return null
        val statusObject = competition["status"].obj() ?: event["status"].obj()
        val status = EspnParsing.statusOf(statusObject)
        val leader = competitorsOf(competition).minByOrNull { it.num("order") ?: 9999.0 }
        return Match(
            id = EspnParsing.matchId(league.id, id),
            league = league,
            home = TeamRef("", name),
            away = TeamRef("", league.name),
            kickoff = EspnParsing.instant(competition.str("date") ?: event.str("date")),
            date = null,
            status = status,
            statusDetail = statusObject?.get("type").obj()?.str("shortDetail"),
            eventTitle = name,
            resultText = if (status != MatchStatus.SCHEDULED) leader?.let(::personName) else null,
        )
    }

    /** A EuroLeague or EuroCup game from the competition's own feed. */
    fun euroGame(game: JsonObject, league: LeagueRef, now: Instant): Match? {
        val code = game.str("identifier") ?: return null
        val kickoff = runCatching { Instant.parse(game.str("utcDate")) }.getOrNull() ?: return null
        fun team(side: JsonObject): TeamRef? {
            val club = side["club"].obj() ?: return null
            val clubCode = club.str("code") ?: return null
            return TeamRef(
                "${league.id}:$clubCode",
                club.str("editorialName") ?: club.str("name") ?: return null,
                club["images"].obj()?.str("crest"),
            )
        }
        val localSide = game["local"].obj() ?: return null
        val roadSide = game["road"].obj() ?: return null
        val played = game.flag("played")
        val elapsed = Duration.between(kickoff, now)
        val running = !played && !elapsed.isNegative && elapsed < Duration.ofHours(3)
        val status = when {
            played -> MatchStatus.FINISHED
            running -> MatchStatus.LIVE
            else -> MatchStatus.SCHEDULED
        }
        val homeScore = localSide.num("score")?.toInt()?.takeIf { status != MatchStatus.SCHEDULED }
        val awayScore = roadSide.num("score")?.toInt()?.takeIf { status != MatchStatus.SCHEDULED }
        fun partials(side: JsonObject): List<Int> {
            val values = side["partials"].obj() ?: return emptyList()
            return (1..4).mapNotNull { values.num("partials$it")?.toInt() }
        }
        val a = partials(localSide)
        val b = partials(roadSide)
        return Match(
            id = EspnParsing.matchId(league.id, code),
            league = league,
            home = team(localSide) ?: return null,
            away = team(roadSide) ?: return null,
            kickoff = kickoff,
            date = null,
            status = status,
            statusDetail = game.str("roundAlias"),
            homeScore = homeScore,
            awayScore = awayScore,
            round = game.num("round")?.toInt(),
            venue = game["venue"].obj()?.str("name"),
            resultText = if (homeScore != null && a.isNotEmpty() && a.size == b.size) {
                a.indices.joinToString("  ") { "${a[it]}-${b[it]}" }
            } else {
                null
            },
        )
    }

    /** Tables across conferences, ordered by points where a sport has them and wins otherwise. */
    fun standings(root: JsonObject): List<StandingRow> {
        val groups = root["children"].list().mapNotNull { it.obj() }
        val entries = (if (groups.isEmpty()) listOf(root) else groups)
            .flatMap { it["standings"].obj()?.get("entries").list() }
            .mapNotNull { it.obj() }
        val rows = entries.mapNotNull { entry ->
            val team = entry["team"].obj() ?: return@mapNotNull null
            val id = team.str("id") ?: return@mapNotNull null
            val stats = entry["stats"].list().mapNotNull { it.obj() }.mapNotNull { stat ->
                val key = stat.str("name") ?: return@mapNotNull null
                stat.num("value")?.let { key to it }
            }.toMap()
            fun value(key: String) = stats[key]?.toInt() ?: 0
            val wins = value("wins")
            val losses = value("losses")
            StandingRow(
                rank = 0,
                team = TeamRef(id, team.str("displayName") ?: return@mapNotNull null, logo(team)),
                played = stats["gamesPlayed"]?.toInt() ?: (wins + losses + value("ties") + value("otLosses")),
                won = wins,
                drawn = value("ties") + value("otLosses"),
                lost = losses,
                goalsFor = value("pointsFor"),
                goalsAgainst = value("pointsAgainst"),
                goalDifference = value("pointDifferential"),
                points = stats["points"]?.toInt() ?: wins,
            )
        }
        return rows.sortedWith(compareByDescending<StandingRow> { it.points }.thenByDescending { it.goalDifference })
            .mapIndexed { index, row -> row.copy(rank = index + 1) }
    }
}

/** Results for every sport except football. Each league is one small, cached request. */
class MultiSportSource(
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val catalog: LeagueCatalog? = null,
) {
    private val builtin = listOf(
        SportLeague(LeagueRef("basketball/euroleague", "EuroLeague", country = "Europe", sport = Sport.BASKETBALL), FeedKind.EUROLEAGUE),
        SportLeague(LeagueRef("basketball/eurocup", "EuroCup", country = "Europe", sport = Sport.BASKETBALL), FeedKind.EUROLEAGUE),
        SportLeague(LeagueRef("basketball/nba", "NBA", country = "USA", sport = Sport.BASKETBALL), FeedKind.TEAMS),
        SportLeague(LeagueRef("basketball/wnba", "WNBA", country = "USA", sport = Sport.BASKETBALL), FeedKind.TEAMS),
        SportLeague(LeagueRef("tennis/atp", "ATP", sport = Sport.TENNIS), FeedKind.TENNIS),
        SportLeague(LeagueRef("tennis/wta", "WTA", sport = Sport.TENNIS), FeedKind.TENNIS),
        SportLeague(LeagueRef("racing/f1", "Formula 1", sport = Sport.MOTOR), FeedKind.RACING),
        SportLeague(LeagueRef("football/nfl", "NFL", country = "USA", sport = Sport.AMERICAN_FOOTBALL), FeedKind.TEAMS),
        SportLeague(LeagueRef("hockey/nhl", "NHL", country = "USA / Canada", sport = Sport.HOCKEY), FeedKind.TEAMS),
        SportLeague(LeagueRef("baseball/mlb", "MLB", country = "USA", sport = Sport.BASEBALL), FeedKind.TEAMS),
        SportLeague(LeagueRef("mma/ufc", "UFC", sport = Sport.MMA), FeedKind.FIGHTS),
    )

    private fun kindOf(id: String): FeedKind = when {
        id == "basketball/euroleague" || id == "basketball/eurocup" -> FeedKind.EUROLEAGUE
        id.startsWith("tennis/") -> FeedKind.TENNIS
        id.startsWith("racing/") -> FeedKind.RACING
        id.startsWith("mma/") -> FeedKind.FIGHTS
        id.startsWith("golf/") -> FeedKind.GOLF
        else -> FeedKind.TEAMS
    }

    private fun leagueFor(id: String): SportLeague? = builtin.firstOrNull { it.ref.id == id }
        ?: catalog?.find(id)?.takeIf { '/' in it.id }?.let { SportLeague(it.toRef(), kindOf(it.id)) }

    /** The chosen leagues. Without a catalog (in tests) the built-in ones are used. */
    private val leagues: List<SportLeague>
        get() = catalog?.selected?.value?.mapNotNull(::leagueFor)?.sortedBy { league -> builtin.indexOfFirst { it.ref.id == league.ref.id }.let { if (it < 0) Int.MAX_VALUE else it } } ?: builtin

    val sports: List<Sport>
        get() {
            val chosen = leagues.map { it.ref.sport }.toSet()
            return listOf(Sport.FOOTBALL) + Sport.entries.filter { it != Sport.FOOTBALL && it in chosen }
        }
    val featured: List<LeagueRef>
        get() = leagues.filter { it.kind == FeedKind.TEAMS || it.kind == FeedKind.EUROLEAGUE }.map { it.ref }

    /** Tennis, racing and fights open from their result, so it is remembered here. */
    private val seen = ConcurrentHashMap<String, Match>()

    private fun leagueOf(id: String) = leagueFor(id) ?: throw DataException.NotFound()

    private fun ttl(date: LocalDate): Long {
        val today = LocalDate.now(zone())
        return when {
            date == today -> TTL_TODAY
            date.isBefore(today) -> TTL_PAST
            else -> TTL_FUTURE
        }
    }

    private fun seasonCode(prefix: String, on: LocalDate): String {
        val year = if (on.monthValue >= 7) on.year else on.year - 1
        return "$prefix$year"
    }

    private fun euroPrefix(id: String) = if (id.endsWith("euroleague")) "E" else "U"

    private suspend fun euroGames(league: SportLeague, on: LocalDate, ttlMs: Long, refresh: Boolean): List<Match> {
        val prefix = euroPrefix(league.ref.id)
        val url = "$EURO/v2/competitions/$prefix/seasons/${seasonCode(prefix, on)}/games"
        val root = EspnParsing.root(http.get(url, ttlMs, refresh).body)
        val now = clock()
        return root["data"].list().mapNotNull { it.obj()?.let { game -> MultiSportParsing.euroGame(game, league.ref, now) } }
    }

    private suspend fun board(league: SportLeague, day: LocalDate, ttlMs: Long, refresh: Boolean): List<Match> {
        val url = "$SITE/${league.ref.id}/scoreboard?dates=${day.format(DAY)}"
        val root = EspnParsing.root(http.get(url, ttlMs, refresh).body)
        val events = root["events"].list().mapNotNull { it.obj() }
        return when (league.kind) {
            FeedKind.TEAMS -> events.mapNotNull { MultiSportParsing.teamGame(it, league.ref) }
            FeedKind.TENNIS -> events.flatMap { event ->
                val name = event.str("shortName") ?: event.str("name")
                val ref = league.ref.copy(name = listOfNotNull(league.ref.name, name).joinToString(" · "))
                event["groupings"].list().mapNotNull { it.obj() }.flatMap { group ->
                    val draw = group["grouping"].obj()?.str("displayName")
                    group["competitions"].list().mapNotNull { item ->
                        item.obj()?.let { MultiSportParsing.duel(it, ref, sets = true, note = draw) }
                    }
                }
            }
            FeedKind.FIGHTS -> events.flatMap { event ->
                val ref = league.ref.copy(name = event.str("name") ?: league.ref.name)
                event["competitions"].list().mapNotNull { item ->
                    item.obj()?.let { MultiSportParsing.duel(it, ref, sets = false, note = null) }
                }
            }
            FeedKind.RACING -> events.flatMap { event ->
                event["competitions"].list().mapNotNull { item ->
                    item.obj()?.let { MultiSportParsing.session(event, it, league.ref) }
                }
            }
            FeedKind.GOLF -> events.mapNotNull { event ->
                event["competitions"].list().firstOrNull().obj()?.let { MultiSportParsing.tournament(event, it, league.ref) }
            }
            FeedKind.EUROLEAGUE -> emptyList()
        }
    }

    fun day(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> = flow {
        val chosen = leagues.filter { it.ref.sport == sport }
        val found = LinkedHashMap<String, Match>()
        var loaded = 0
        var failed = 0
        var firstError: DataException? = null
        fun snapshot() = MatchDay(
            matches = found.values.sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            fetchedAt = clock(),
            loadedSources = loaded,
            totalSources = chosen.size,
            failedSources = failed,
            firstError = firstError,
        )
        if (chosen.isEmpty()) emit(snapshot())
        // Leagues load side by side. The client's rate limiter still spaces out the actual requests.
        val gate = Semaphore(PARALLEL_LEAGUES)
        channelFlow<Result<List<Match>>> {
            chosen.forEach { league ->
                launch {
                    send(
                        gate.withPermit {
                            try {
                                val games = if (league.kind == FeedKind.EUROLEAGUE) {
                                    euroGames(league, date, ttl(date), refresh)
                                } else {
                                    // A local evening can fall on the feed's next day, so both are read.
                                    listOf(date.minusDays(1), date, date.plusDays(1)).flatMap { board(league, it, ttl(date), refresh) }
                                }
                                Result.success(games.filter { it.kickoff?.atZone(zone())?.toLocalDate() == date })
                            } catch (error: DataException) {
                                Result.failure(error)
                            }
                        },
                    )
                }
            }
        }.collect { result ->
            result.onSuccess { games ->
                games.forEach {
                    found[it.id] = it
                    seen[it.id] = it
                }
                loaded++
            }.onFailure { error ->
                failed++
                firstError = firstError ?: error as DataException
            }
            emit(snapshot())
        }
    }

    suspend fun details(matchId: String, refresh: Boolean, standingsOf: suspend (String) -> Standings?): Sourced<MatchDetails> {
        val (leagueId, eventId) = EspnParsing.splitMatchId(matchId)
        val league = leagueOf(leagueId)
        val known = seen[matchId]
        val match = when (league.kind) {
            FeedKind.EUROLEAGUE -> {
                val day = known?.kickoff?.atZone(zone())?.toLocalDate() ?: LocalDate.now(zone())
                euroGames(league, day, TTL_TODAY, refresh).firstOrNull { it.id == matchId }
            }
            FeedKind.TEAMS -> {
                val fetched = http.get("$SITE/$leagueId/summary?event=$eventId", TTL_TODAY, refresh)
                val root = EspnParsing.root(fetched.body)
                root["header"].obj()?.let { MultiSportParsing.teamGame(it, league.ref) }
            }
            else -> {
                val day = known?.kickoff?.atZone(zone())?.toLocalDate() ?: LocalDate.now(zone())
                listOf(day.minusDays(1), day, day.plusDays(1)).flatMap { board(league, it, TTL_TODAY, refresh) }
                    .firstOrNull { it.id == matchId }
            }
        } ?: known ?: throw DataException.NotFound()
        seen[matchId] = match
        val table = if (league.kind == FeedKind.TEAMS || league.kind == FeedKind.EUROLEAGUE) {
            try {
                standingsOf(leagueId)
            } catch (_: DataException) {
                null
            }
        } else {
            null
        }
        return Sourced(MatchDetails(match = match, events = emptyList(), standings = table), clock())
    }

    fun league(id: String): Sourced<LeagueDetails> = Sourced(LeagueDetails(leagueOf(id).ref, null), clock())

    suspend fun standings(id: String, refresh: Boolean): Sourced<Standings> {
        val league = leagueOf(id)
        if (league.kind == FeedKind.EUROLEAGUE) return euroStandings(league, refresh)
        val fetched = http.get("$STANDINGS/$id/standings", TTL_STANDINGS, refresh)
        val rows = MultiSportParsing.standings(EspnParsing.root(fetched.body))
            .map { it.copy(team = it.team.copy(id = "$id:${it.team.id}")) }
        return Sourced(Standings(league.ref, null, rows, isPartial = false), Instant.ofEpochMilli(fetched.fetchedAtMs), fetched.isStale)
    }

    private suspend fun euroStandings(league: SportLeague, refresh: Boolean): Sourced<Standings> {
        val today = LocalDate.now(zone())
        val prefix = euroPrefix(league.ref.id)
        val round = euroGames(league, today, TTL_TODAY, false).filter { it.status == MatchStatus.FINISHED }.mapNotNull { it.round }.maxOrNull()
            ?: return Sourced(Standings(league.ref, null, emptyList(), isPartial = false), clock())
        val url = "$EURO/v3/competitions/$prefix/seasons/${seasonCode(prefix, today)}/rounds/$round/basicstandings"
        val fetched = http.get(url, TTL_STANDINGS, refresh)
        val rows = EspnParsing.root(fetched.body)["teams"].list().mapNotNull { it.obj() }.mapNotNull { row ->
            val club = row["club"].obj() ?: return@mapNotNull null
            val code = club.str("code") ?: return@mapNotNull null
            val won = row.num("gamesWon")?.toInt() ?: 0
            val scored = row.num("pointsFor")?.toInt() ?: 0
            val conceded = row.num("pointsAgainst")?.toInt() ?: 0
            StandingRow(
                rank = row.num("position")?.toInt() ?: 0,
                team = TeamRef("${league.ref.id}:$code", club.str("editorialName") ?: club.str("name") ?: code, club["images"].obj()?.str("crest")),
                played = row.num("gamesPlayed")?.toInt() ?: 0,
                won = won,
                drawn = 0,
                lost = row.num("gamesLost")?.toInt() ?: 0,
                goalsFor = scored,
                goalsAgainst = conceded,
                goalDifference = scored - conceded,
                points = won,
            )
        }.sortedBy { it.rank }
        return Sourced(Standings(league.ref, null, rows, isPartial = false), Instant.ofEpochMilli(fetched.fetchedAtMs), fetched.isStale)
    }

    /** Next games around today. These leagues have no simple rounds in their feeds. */
    suspend fun fixtures(id: String, refresh: Boolean): Sourced<LeagueFixtures> {
        val league = leagueOf(id)
        val today = LocalDate.now(zone())
        val games = if (league.kind == FeedKind.EUROLEAGUE) {
            val all = euroGames(league, today, TTL_TODAY, refresh)
            val round = all.firstOrNull { it.status != MatchStatus.FINISHED }?.round ?: all.lastOrNull()?.round
            all.filter { it.round == round }
        } else {
            (-1L..5L).flatMap { offset ->
                try {
                    board(league, today.plusDays(offset), TTL_FUTURE, refresh)
                } catch (_: DataException) {
                    emptyList()
                }
            }.distinctBy { it.id }
        }.sortedBy { it.kickoff }
        games.forEach { seen[it.id] = it }
        return Sourced(LeagueFixtures(league.ref, null, null, games, hasPreviousRound = false, hasNextRound = false), clock())
    }

    private fun splitTeam(id: String): Pair<String, String> {
        val index = id.lastIndexOf(':')
        if (index <= 0) throw DataException.NotFound()
        return id.substring(0, index) to id.substring(index + 1)
    }

    suspend fun team(teamId: String, refresh: Boolean): Sourced<Team> {
        val (leagueId, raw) = splitTeam(teamId)
        val league = leagueOf(leagueId)
        if (league.kind == FeedKind.EUROLEAGUE) {
            val games = euroGames(league, LocalDate.now(zone()), TTL_TODAY, refresh)
            val ref = games.flatMap { listOf(it.home, it.away) }.firstOrNull { it.id == teamId } ?: throw DataException.NotFound()
            return Sourced(Team(ref.id, ref.name, badgeUrl = ref.badgeUrl, league = league.ref), clock())
        }
        val fetched = http.get("$SITE/$leagueId/teams/$raw", TTL_TEAM, refresh)
        val team = EspnParsing.root(fetched.body)["team"].obj() ?: throw DataException.NotFound()
        return Sourced(
            Team(
                id = teamId,
                name = team.str("displayName") ?: throw DataException.BadData(),
                shortName = team.str("shortDisplayName"),
                badgeUrl = logo(team),
                location = team.str("location"),
                league = league.ref,
            ),
            Instant.ofEpochMilli(fetched.fetchedAtMs),
            fetched.isStale,
        )
    }

    suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val (leagueId, raw) = splitTeam(teamId)
        val league = leagueOf(leagueId)
        val games = if (league.kind == FeedKind.EUROLEAGUE) {
            euroGames(league, LocalDate.now(zone()), TTL_TODAY, refresh).filter { it.home.id == teamId || it.away.id == teamId }
        } else {
            val fetched = http.get("$SITE/$leagueId/teams/$raw/schedule", TTL_SCHEDULE, refresh)
            EspnParsing.root(fetched.body)["events"].list().mapNotNull { it.obj()?.let { event -> MultiSportParsing.teamGame(event, league.ref) } }
        }
        games.forEach { seen[it.id] = it }
        val (upcoming, recent) = games.partition { it.status == MatchStatus.SCHEDULED }
        return Sourced(TeamMatches(recent.sortedByDescending { it.kickoff }, upcoming.sortedBy { it.kickoff }), clock())
    }

    /** Clubs from these sports, for the search screen. */
    suspend fun searchTeams(needle: String): List<Team> {
        val out = LinkedHashMap<String, Team>()
        leagues.filter { it.kind == FeedKind.TEAMS || it.kind == FeedKind.EUROLEAGUE }.forEach { league ->
            try {
                if (league.kind == FeedKind.EUROLEAGUE) {
                    euroGames(league, LocalDate.now(zone()), TTL_TEAM, false).flatMap { listOf(it.home, it.away) }.distinctBy { it.id }
                        .filter { EspnProvider.searchKey(it.name).contains(needle) }
                        .forEach { out[it.id] = Team(it.id, it.name, badgeUrl = it.badgeUrl, league = league.ref) }
                } else {
                    val root = EspnParsing.root(http.get("$SITE/${league.ref.id}/teams?limit=200", TTL_TEAM).body)
                    root["sports"].list().firstOrNull().obj()?.get("leagues").list().firstOrNull().obj()?.get("teams").list().forEach { row ->
                        val team = row.obj()?.get("team").obj() ?: return@forEach
                        val id = team.str("id") ?: return@forEach
                        val name = team.str("displayName") ?: return@forEach
                        if (EspnProvider.searchKey(name).contains(needle)) {
                            val full = "${league.ref.id}:$id"
                            out[full] = Team(full, name, team.str("shortDisplayName"), logo(team), league = league.ref)
                        }
                    }
                }
            } catch (_: DataException) {
                // One league failing must not empty the search.
            }
        }
        return out.values.toList()
    }

    private companion object {
        const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
        const val STANDINGS = "https://site.api.espn.com/apis/v2/sports"
        const val EURO = "https://api-live.euroleague.net"
        const val TTL_TODAY = 25_000L
        const val TTL_PAST = 3_600_000L
        const val TTL_FUTURE = 900_000L
        const val TTL_STANDINGS = 600_000L
        const val TTL_SCHEDULE = 300_000L
        const val TTL_TEAM = 86_400_000L
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
