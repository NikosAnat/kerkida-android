package app.kerkida.tv

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class DirectoryTeam(
    @SerialName("s") val sport: String,
    @SerialName("n") val name: String,
    @SerialName("b") val badge: String,
    @SerialName("a") val alternates: List<String> = emptyList(),
)

/** Finds a club's crest from a Greek or Latin name. The list ships with the app, so lookups are free. */
class TeamDirectory(private val load: () -> List<DirectoryTeam>) {
    constructor(context: Context) : this({ readBundled(context) })

    private val teams by lazy { load() }
    private val memo = ConcurrentHashMap<String, String>()

    private fun sportName(sport: TvSport) = when (sport) {
        TvSport.FOOTBALL -> "FOOTBALL"
        TvSport.BASKETBALL -> "BASKETBALL"
        TvSport.AMERICAN_FOOTBALL -> "AMERICAN_FOOTBALL"
        TvSport.HOCKEY -> "HOCKEY"
        TvSport.BASEBALL -> "BASEBALL"
        else -> null
    }

    fun crest(name: String, sport: TvSport): String? {
        val wanted = sportName(sport) ?: return null
        val key = "$wanted|$name"
        memo[key]?.let { return it.ifEmpty { null } }
        var best: DirectoryTeam? = null
        var bestScore = 0.0
        teams.asSequence().filter { it.sport == wanted }.forEach { team ->
            val score = (listOf(team.name) + team.alternates).maxOf { TvLogic.nameSimilarity(name, it) }
            if (score > bestScore) {
                bestScore = score
                best = team
            }
        }
        val found = best?.badge?.takeIf { bestScore >= MIN_SCORE }
        memo[key] = found.orEmpty()
        return found
    }

    /** Latin names that sound like a Greek search, for the search service. */
    fun closestNames(text: String, limit: Int = 3): List<String> = teams.asSequence()
        .map { team -> team to (listOf(team.name) + team.alternates).maxOf { TvLogic.nameSimilarity(text, it) } }
        .filter { it.second >= MIN_SCORE }
        .sortedByDescending { it.second }
        .map { it.first.name }
        .distinct()
        .take(limit)
        .toList()

    private companion object {
        const val MIN_SCORE = 0.82
        val json = Json { ignoreUnknownKeys = true }

        fun readBundled(context: Context): List<DirectoryTeam> = runCatching {
            context.assets.open("teams/teams.json").bufferedReader().use {
                json.decodeFromString<List<DirectoryTeam>>(it.readText())
            }
        }.getOrDefault(emptyList())
    }
}
