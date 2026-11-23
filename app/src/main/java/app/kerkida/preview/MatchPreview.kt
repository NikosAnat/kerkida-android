package app.kerkida.preview

import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Standings
import app.kerkida.fantasy.MatchSquads
import app.kerkida.fantasy.Position
import app.kerkida.fantasy.SquadPlayer

enum class FormResult { WIN, DRAW, LOSS }

enum class InsightKind {
    WIN_STREAK, WINLESS_RUN, CLOSE_IN_TABLE, TABLE_GAP, HIGH_SCORING, LEAKY_DEFENCE, KEY_ABSENCE,
}

/** A translatable talking point: the screen turns the kind and values into a sentence. */
data class PreviewInsight(
    val kind: InsightKind,
    val team: String? = null,
    val player: String? = null,
    val number: Int? = null,
    val decimal: Double? = null,
)

/** Where a shown eleven came from, so an estimate is never presented as confirmed. */
enum class ElevenSource { OFFICIAL, LAST_MATCH, SEASON_STARTS, NONE }

data class TeamPreview(
    val form: List<FormResult>,
    val absences: List<SquadPlayer>,
    val probableEleven: List<SquadPlayer>,
    val elevenSource: ElevenSource = ElevenSource.NONE,
    val formation: String? = null,
)

data class KnownEleven(val players: List<SquadPlayer>, val source: ElevenSource, val formation: String? = null)

data class MatchPreview(
    val home: TeamPreview,
    val away: TeamPreview,
    val insights: List<PreviewInsight>,
    val hasSquadData: Boolean,
)

object PreviewBuilder {
    private const val FORM_LENGTH = 5
    private const val MAX_INSIGHTS = 3
    private const val LIKELY_TO_PLAY = 75

    fun build(
        match: Match,
        homeRecent: List<Match>,
        awayRecent: List<Match>,
        standings: Standings?,
        squads: MatchSquads?,
        homeEleven: KnownEleven? = null,
        awayEleven: KnownEleven? = null,
    ): MatchPreview {
        val homeGames = finished(homeRecent, match)
        val awayGames = finished(awayRecent, match)
        val homeForm = homeGames.mapNotNull { resultFor(it, match.home.id) }
        val awayForm = awayGames.mapNotNull { resultFor(it, match.away.id) }
        val homeOut = squads?.home.orEmpty().filter(::isAbsent).sortedByDescending { it.starts }
        val awayOut = squads?.away.orEmpty().filter(::isAbsent).sortedByDescending { it.starts }

        val insights = buildList {
            streak(match.home.name, homeForm)?.let(::add)
            streak(match.away.name, awayForm)?.let(::add)
            table(match, standings)?.let(::add)
            keyAbsence(match.home.name, homeOut)?.let(::add)
            keyAbsence(match.away.name, awayOut)?.let(::add)
            goals(match.home.name, match.home.id, homeGames)?.let(::add)
            goals(match.away.name, match.away.id, awayGames)?.let(::add)
        }.take(MAX_INSIGHTS)

        return MatchPreview(
            home = teamPreview(homeForm, homeOut, homeEleven, squads?.home.orEmpty()),
            away = teamPreview(awayForm, awayOut, awayEleven, squads?.away.orEmpty()),
            insights = insights,
            hasSquadData = squads != null,
        )
    }

    private fun teamPreview(
        form: List<FormResult>,
        absences: List<SquadPlayer>,
        known: KnownEleven?,
        squad: List<SquadPlayer>,
    ): TeamPreview {
        if (known?.source == ElevenSource.OFFICIAL) return TeamPreview(form, absences, known.players, known.source, known.formation)
        val estimate = probableEleven(squad)
        if (estimate.isNotEmpty()) return TeamPreview(form, absences, estimate, ElevenSource.SEASON_STARTS)
        return TeamPreview(form, absences, known?.players.orEmpty(), known?.source ?: ElevenSource.NONE, known?.formation)
    }

    /** Newest first. Only finished, scored games before this match count. */
    private fun finished(games: List<Match>, match: Match): List<Match> = games
        .filter { it.id != match.id && it.status == MatchStatus.FINISHED && it.homeScore != null && it.awayScore != null }
        .filter { game -> match.kickoff == null || game.kickoff == null || game.kickoff < match.kickoff }
        .sortedByDescending { it.kickoff }
        .take(FORM_LENGTH)

    fun resultFor(game: Match, teamId: String): FormResult? {
        val home = game.homeScore ?: return null
        val away = game.awayScore ?: return null
        val (own, other) = when (teamId) {
            game.home.id -> home to away
            game.away.id -> away to home
            else -> return null
        }
        return when {
            own > other -> FormResult.WIN
            own < other -> FormResult.LOSS
            else -> FormResult.DRAW
        }
    }

    private fun isAbsent(player: SquadPlayer): Boolean =
        !player.isAvailable && (player.chance ?: 0) < LIKELY_TO_PLAY && player.news.isNotBlank()

    private fun canStart(player: SquadPlayer): Boolean =
        player.isAvailable || (player.chance ?: 0) >= LIKELY_TO_PLAY

    fun probableEleven(squad: List<SquadPlayer>): List<SquadPlayer> {
        val pool = squad.filter(::canStart).sortedWith(compareByDescending<SquadPlayer> { it.starts }.thenByDescending { it.minutes })
        val keeper = pool.firstOrNull { it.position == Position.GKP } ?: return emptyList()
        val picked = mutableListOf(keeper)
        val minimums = mapOf(Position.DEF to 3, Position.MID to 2, Position.FWD to 1)
        val maximums = mapOf(Position.DEF to 5, Position.MID to 5, Position.FWD to 3)
        minimums.forEach { (position, count) -> picked += pool.filter { it.position == position }.take(count) }
        for (player in pool) {
            if (picked.size >= 11) break
            if (player.position == Position.GKP || player in picked) continue
            if (picked.count { it.position == player.position } < maximums.getValue(player.position)) picked += player
        }
        if (picked.size < 11) return emptyList()
        return picked.sortedBy { it.position.ordinal }
    }

    private fun streak(team: String, form: List<FormResult>): PreviewInsight? {
        val wins = form.takeWhile { it == FormResult.WIN }.size
        if (wins >= 3) return PreviewInsight(InsightKind.WIN_STREAK, team = team, number = wins)
        val winless = form.takeWhile { it != FormResult.WIN }.size
        if (winless >= 4) return PreviewInsight(InsightKind.WINLESS_RUN, team = team, number = winless)
        return null
    }

    private fun table(match: Match, standings: Standings?): PreviewInsight? {
        val home = standings?.rows?.firstOrNull { it.team.id == match.home.id } ?: return null
        val away = standings.rows.firstOrNull { it.team.id == match.away.id } ?: return null
        val gap = kotlin.math.abs(home.points - away.points)
        return when {
            gap <= 3 -> PreviewInsight(InsightKind.CLOSE_IN_TABLE, number = gap)
            gap >= 10 -> PreviewInsight(
                InsightKind.TABLE_GAP,
                team = if (home.points > away.points) match.home.name else match.away.name,
                number = gap,
            )
            else -> null
        }
    }

    private fun keyAbsence(team: String, absent: List<SquadPlayer>): PreviewInsight? =
        absent.firstOrNull { it.starts >= 5 }?.let {
            PreviewInsight(InsightKind.KEY_ABSENCE, team = team, player = it.name, number = it.starts)
        }

    private fun goals(team: String, teamId: String, games: List<Match>): PreviewInsight? {
        if (games.size < 3) return null
        var scored = 0
        var conceded = 0
        games.forEach { game ->
            val isHome = game.home.id == teamId
            scored += (if (isHome) game.homeScore else game.awayScore) ?: 0
            conceded += (if (isHome) game.awayScore else game.homeScore) ?: 0
        }
        val scoredAverage = scored.toDouble() / games.size
        val concededAverage = conceded.toDouble() / games.size
        return when {
            scoredAverage >= 2.0 -> PreviewInsight(InsightKind.HIGH_SCORING, team = team, number = games.size, decimal = scoredAverage)
            concededAverage >= 2.0 -> PreviewInsight(InsightKind.LEAKY_DEFENCE, team = team, number = games.size, decimal = concededAverage)
            else -> null
        }
    }
}
