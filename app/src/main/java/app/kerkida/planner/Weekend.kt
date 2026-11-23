package app.kerkida.planner

import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.Place
import app.kerkida.stadium.Stadium
import app.kerkida.tv.TvLogic
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One thing the person could do this weekend: go to it, watch it, or both. */
data class WeekendEntry(
    val key: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val match: Match? = null,
    val channels: List<String> = emptyList(),
    val km: Double? = null,
    val ground: String? = null,
    val isMine: Boolean = false,
    val score: Double = 0.0,
    val isPick: Boolean = false,
    val clashesWith: String? = null,
)

/** A TV listing with no match page, already filtered to what the person can watch. */
data class WeekendListing(val key: String, val title: String, val startMs: Long, val endMs: Long, val channels: List<String>)

object WeekendLogic {
    private const val MINE = 100.0
    private const val IN_PERSON = 40.0
    private const val ON_TV = 20.0
    private const val TRAVEL_KMH = 45.0
    private const val TRAVEL_MARGIN_MIN = 20L

    /** Saturday and Sunday ahead. On a Sunday only today is left. */
    fun days(today: LocalDate): List<LocalDate> = when (today.dayOfWeek) {
        DayOfWeek.SUNDAY -> listOf(today)
        DayOfWeek.SATURDAY -> listOf(today, today.plusDays(1))
        else -> today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).let { listOf(it, it.plusDays(1)) }
    }

    fun usualMinutes(sport: Sport) = when (sport) {
        Sport.BASKETBALL -> 125
        else -> 115
    }

    /** When to leave for a ground: driving time at a modest speed, plus time to park and get in. */
    fun leaveAt(kickoff: Instant, km: Double): Instant =
        kickoff.minusSeconds(((km / TRAVEL_KMH) * 3600).toLong() + TRAVEL_MARGIN_MIN * 60)

    fun plan(
        matches: List<Match>,
        listings: List<WeekendListing>,
        place: Place?,
        radiusKm: Int,
        myTeams: Set<String>,
        now: Instant,
        stadiumOf: (Match) -> Stadium?,
        channelsOf: (Match) -> List<String>,
    ): List<WeekendEntry> {
        val fromGames = matches.asSequence().distinctBy { it.id }
            .filter { it.status != MatchStatus.FINISHED && it.status != MatchStatus.CANCELLED && it.status != MatchStatus.POSTPONED }
            .mapNotNull { game ->
                val start = game.kickoff?.takeIf { !it.isBefore(now.minusSeconds(2 * 3600)) } ?: return@mapNotNull null
                val stadium = stadiumOf(game)
                val lat = stadium?.lat
                val lng = stadium?.lng
                val km = if (place != null && lat != null && lng != null) {
                    NearbyLogic.distanceKm(place.lat, place.lng, lat, lng).takeIf { it <= radiusKm }
                } else {
                    null
                }
                val channels = channelsOf(game)
                val mine = game.home.id in myTeams || game.away.id in myTeams
                if (!mine && km == null && channels.isEmpty()) return@mapNotNull null
                val score = (if (mine) MINE else 0.0) +
                    (km?.let { IN_PERSON - it / 5 } ?: 0.0) +
                    (if (channels.isNotEmpty()) ON_TV else 0.0)
                WeekendEntry(
                    key = game.id,
                    title = "${game.home.name} – ${game.away.name}",
                    startMs = start.toEpochMilli(),
                    endMs = start.toEpochMilli() + usualMinutes(game.league.sport) * 60_000L,
                    match = game,
                    channels = channels,
                    km = km,
                    ground = stadium?.name,
                    isMine = mine,
                    score = score,
                )
            }.toList()
        val taken = fromGames.flatMap { it.channels.map { channel -> channel to it.startMs } }.toSet()
        val fromTv = listings.asSequence()
            .filter { it.endMs > now.toEpochMilli() }
            .filter { listing -> listing.channels.none { (it to listing.startMs) in taken } }
            // The same game can be written two ways by two guides, so it is folded into one row.
            .groupBy { it.startMs to TvLogic.phonetic(it.title) }
            .values.map { same ->
                val first = same.first()
                WeekendEntry(first.key, first.title, first.startMs, first.endMs, channels = same.flatMap { it.channels }.distinct(), score = ON_TV)
            }
        return markPicks(fromGames + fromTv)
    }

    /** The best entries that do not overlap become picks. Each other entry names what it clashes with. */
    fun markPicks(entries: List<WeekendEntry>): List<WeekendEntry> {
        val picks = mutableListOf<WeekendEntry>()
        entries.sortedWith(compareBy({ -it.score }, { it.startMs })).forEach { entry ->
            if (picks.none { overlaps(it, entry) }) picks += entry
        }
        val chosen = picks.map { it.key }.toSet()
        return entries.map { entry ->
            if (entry.key in chosen) {
                entry.copy(isPick = true)
            } else {
                entry.copy(clashesWith = picks.firstOrNull { overlaps(it, entry) }?.title)
            }
        }.sortedWith(compareBy({ it.startMs }, { -it.score }))
    }

    private fun overlaps(a: WeekendEntry, b: WeekendEntry) = a.startMs < b.endMs && b.startMs < a.endMs
}
