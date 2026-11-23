package app.kerkida.data.photos

import android.content.Context
import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchExtras
import app.kerkida.data.model.TeamLineup
import app.kerkida.fantasy.FantasyInsights
import app.kerkida.fantasy.FplPlayer
import app.kerkida.tv.TvLogic
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.serialization.json.Json
import java.net.URLEncoder

object PlayerPhotoLogic {
    private const val PIXELS = 160
    private val EspnHeadshot = Regex("^https://a\\.espncdn\\.com(/i/headshots/.+\\.png)$")

    /** ESPN's full headshots weigh about 200 KB. Its image service returns the same photo at about 20 KB. */
    fun smallEspn(url: String?): String? = url?.let {
        EspnHeadshot.matchEntire(it)?.let { found ->
            "https://a.espncdn.com/combiner/i?img=${found.groupValues[1]}&w=$PIXELS&h=$PIXELS&scale=crop"
        } ?: it
    }

    fun commonsUrl(file: String): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(file.replace(' ', '_'), "UTF-8").replace("+", "%20") + "?width=$PIXELS"

    fun fplUrl(code: Int) = "https://resources.premierleague.com/premierleague25/photos/players/110x140/$code.png"

    private fun key(name: String) = TvLogic.plain(name).replace(Regex("[^\\p{L}0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

    private fun <T> uniqueBy(items: List<T>, by: (T) -> String): Map<String, T> =
        items.groupBy(by).filterValues { it.size == 1 }.mapValues { it.value.single() }

    /** Pairs a line-up with its FPL squad. An unclear name gets no photo rather than someone else's. */
    fun matchSquad(players: List<LineupPlayer>, squad: List<FplPlayer>): Map<String, FplPlayer> {
        val byFull = uniqueBy(squad) { key("${it.firstName} ${it.secondName}") }
        val byShort = uniqueBy(squad) { key(it.name) }
        val bySurname = uniqueBy(squad) { key(it.secondName).substringAfterLast(' ') }
        return players.mapNotNull { player ->
            val name = key(player.name)
            val surnameMatch = bySurname[name.substringAfterLast(' ')]?.takeIf { found ->
                ' ' in name && key(found.firstName).firstOrNull() == name.first()
            }
            (byFull[name] ?: byShort[name] ?: surnameMatch)?.let { player.id to it }
        }.toMap()
    }

    /** The official FPL photo first, then ESPN's, then the open one. */
    fun choose(player: LineupPlayer, fpl: FplPlayer?, commonsFile: String?): String? =
        fpl?.code?.takeIf { it > 0 }?.let(::fplUrl) ?: player.photoUrl ?: commonsFile?.let(::commonsUrl)

    fun withPhotos(extras: MatchExtras?, photos: Map<String, String>): MatchExtras? {
        if (extras == null || photos.isEmpty()) return extras
        fun side(lineup: TeamLineup?) = lineup?.copy(
            players = lineup.players.map { it.copy(photoUrl = photos[it.id] ?: it.photoUrl) },
        )
        return MatchExtras(side(extras.home), side(extras.away))
    }
}

/** Finds a face for each player of a match and fetches the starters' photos ahead of time. */
class PlayerPhotos(private val context: Context, private val fantasy: FantasyInsights) {
    private val index: Map<String, String> by lazy {
        runCatching {
            context.assets.open("catalog/player_photos.json").bufferedReader().use {
                Json.decodeFromString<Map<String, String>>(it.readText())
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun forMatch(match: Match, extras: MatchExtras): Map<String, String> {
        val squads = runCatching { fantasy.teamPlayers(match) }.getOrNull()
        fun side(lineup: TeamLineup?, squad: List<FplPlayer>?): List<Pair<String, String>> {
            val players = lineup?.players.orEmpty()
            val official = squad?.let { PlayerPhotoLogic.matchSquad(players, it) }.orEmpty()
            return players.mapNotNull { player ->
                PlayerPhotoLogic.choose(player, official[player.id], index[player.id])?.let { player.id to it }
            }
        }
        return (side(extras.home, squads?.first) + side(extras.away, squads?.second)).toMap()
    }

    /** Only those who played, so a whole bench is not downloaded on mobile data. */
    fun preload(extras: MatchExtras, photos: Map<String, String>) {
        val loader = SingletonImageLoader.get(context)
        listOfNotNull(extras.home, extras.away).flatMap { it.players }
            .filter { it.isStarter || it.minutes > 0 }
            .mapNotNull { photos[it.id] }
            .distinct()
            .forEach { url -> loader.enqueue(ImageRequest.Builder(context).data(url).build()) }
    }
}
