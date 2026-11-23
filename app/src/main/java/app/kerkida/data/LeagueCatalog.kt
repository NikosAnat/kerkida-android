package app.kerkida.data

import android.content.Context
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Sport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogLeague(
    @SerialName("i") val id: String,
    @SerialName("n") val name: String,
    @SerialName("s") val sportName: String,
    /** Two-letter country code. Null for international competitions. */
    @SerialName("c") val country: String? = null,
    @SerialName("l") val logo: String? = null,
) {
    val sport: Sport get() = Sport.entries.firstOrNull { it.name == sportName } ?: Sport.FOOTBALL

    fun toRef() = LeagueRef(id = id, name = name, badgeUrl = logo, sport = sport)
}

interface LeagueChoiceStore {
    fun load(): Set<String>?
    fun save(ids: Set<String>)
}

class PrefsLeagueChoiceStore(context: Context) : LeagueChoiceStore {
    private val prefs = context.getSharedPreferences("kerkida_leagues", Context.MODE_PRIVATE)
    override fun load(): Set<String>? = prefs.getStringSet(KEY, null)?.toSet()
    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "selected"
    }
}

/** Every competition the app can show, and the ones the person chose to follow. */
class LeagueCatalog(
    private val store: LeagueChoiceStore,
    private val load: () -> List<CatalogLeague>,
) {
    constructor(context: Context) : this(PrefsLeagueChoiceStore(context), { readBundled(context) })

    val all: List<CatalogLeague> by lazy { load() }
    private val byId by lazy { all.associateBy { it.id } }

    private val _selected = MutableStateFlow(store.load() ?: Defaults)
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun find(id: String): CatalogLeague? = byId[id]

    fun isSelected(id: String) = id in _selected.value

    fun toggle(id: String) = update(if (id in _selected.value) _selected.value - id else _selected.value + id)

    fun setAll(ids: Collection<String>, on: Boolean) =
        update(if (on) _selected.value + ids else _selected.value - ids.toSet())

    fun reset() = update(Defaults)

    private fun update(ids: Set<String>) {
        _selected.value = ids
        store.save(ids)
    }

    companion object {
        val Defaults: Set<String> = setOf(
            "gre.1", "grl.4640", "grl.5847", "grl.gbl", "grl.ts.elite", "grl.ts.vlm", "grl.ts.vlw", "grl.5830", "grl.ach.a1", "grl.ach.a2", "grl.las.a", "eng.1", "uefa.champions", "uefa.europa", "uefa.europa.conf", "esp.1", "ita.1", "ger.1", "fra.1",
            "ned.1", "por.1", "tur.1", "eng.2", "basketball/euroleague", "basketball/eurocup", "basketball/nba", "basketball/wnba",
            "tennis/atp", "tennis/wta", "racing/f1", "football/nfl", "hockey/nhl", "baseball/mlb", "mma/ufc",
        )
        private val json = Json { ignoreUnknownKeys = true }

        private fun readBundled(context: Context): List<CatalogLeague> = runCatching {
            context.assets.open("catalog/leagues.json").bufferedReader().use {
                json.decodeFromString<List<CatalogLeague>>(it.readText())
            }
        }.getOrDefault(emptyList())
    }
}
