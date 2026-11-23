package app.kerkida.broadcast

import android.content.Context
import app.kerkida.data.model.Match
import app.kerkida.tv.TvScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class AccessType { FREE, SUBSCRIPTION, PAY_PER_VIEW }

/** The channels offered when adding a broadcast. Rights are never assumed from this list. */
data class Channel(val id: String, val name: String, val usualAccess: AccessType)

val GreekChannels = listOf(
    Channel("nova", "Nova", AccessType.SUBSCRIPTION),
    Channel("magenta", "Magenta", AccessType.SUBSCRIPTION),
    Channel("eurosport", "Eurosport", AccessType.SUBSCRIPTION),
    Channel("dazn", "DAZN", AccessType.SUBSCRIPTION),
    Channel("ant1plus", "ΑΝΤ1+", AccessType.SUBSCRIPTION),
    Channel("sportfm", "ΣΠΟΡ FM TV", AccessType.FREE),
    Channel("ert", "ΕΡΤ", AccessType.FREE),
    Channel("mega", "Mega", AccessType.FREE),
    Channel("alpha", "Alpha", AccessType.FREE),
    Channel("ant1", "ΑΝΤ1", AccessType.FREE),
    Channel("skai", "ΣΚΑΪ", AccessType.FREE),
    Channel("star", "Star", AccessType.FREE),
    Channel("open", "Open", AccessType.FREE),
    Channel("streaming", "Streaming", AccessType.SUBSCRIPTION),
)

/** Channels that can be ticked in Settings as "I have this". */
val SubscribableChannels: List<Channel> = GreekChannels.filter { it.usualAccess == AccessType.SUBSCRIPTION }

@Serializable
enum class BroadcastScope { MATCH, LEAGUE }

@Serializable
data class Broadcast(
    val id: String = UUID.randomUUID().toString(),
    val scope: BroadcastScope,
    /** The match id or league id this entry applies to. */
    val targetId: String,
    /** League entries apply to one season, because rights change between seasons. */
    val season: String? = null,
    val channelId: String,
    val channelName: String,
    val access: AccessType,
    val updatedAtMs: Long,
    val sourceId: String = ManualBroadcastProvider.ID,
)

/** A source of Greek broadcast information. Add a new one and register it in [BroadcastRepository]. */
interface BroadcastProvider {
    val id: String
    suspend fun forMatch(match: Match): List<Broadcast>
}

interface BroadcastStore {
    fun load(): List<Broadcast>
    fun save(items: List<Broadcast>)
    fun loadSubscriptions(): Set<String>
    fun saveSubscriptions(ids: Set<String>)
}

class PrefsBroadcastStore(context: Context) : BroadcastStore {
    private val prefs = context.getSharedPreferences("kerkida_broadcasts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<Broadcast> = prefs.getString("items", null)
        ?.let { runCatching { json.decodeFromString<List<Broadcast>>(it) }.getOrNull() }
        .orEmpty()

    override fun save(items: List<Broadcast>) {
        prefs.edit().putString("items", json.encodeToString(items)).apply()
    }

    override fun loadSubscriptions(): Set<String> = prefs.getStringSet("subscriptions", emptySet()).orEmpty().toSet()

    override fun saveSubscriptions(ids: Set<String>) {
        prefs.edit().putStringSet("subscriptions", ids).apply()
    }
}

/** Entries typed in by the person. A match entry replaces whatever its league entry says. */
class ManualBroadcastProvider(
    private val store: BroadcastStore,
    private val now: () -> Long = System::currentTimeMillis,
) : BroadcastProvider {
    override val id: String = ID

    private val _items = MutableStateFlow(store.load())
    val items: StateFlow<List<Broadcast>> = _items.asStateFlow()

    override suspend fun forMatch(match: Match): List<Broadcast> = matching(match)

    fun matching(match: Match): List<Broadcast> {
        val own = _items.value.filter { it.scope == BroadcastScope.MATCH && it.targetId == match.id }
        if (own.isNotEmpty()) return own
        return _items.value.filter {
            it.scope == BroadcastScope.LEAGUE && it.targetId == match.league.id &&
                (it.season == null || it.season == match.season)
        }
    }

    fun add(match: Match, scope: BroadcastScope, channelId: String, channelName: String, access: AccessType) {
        val item = Broadcast(
            scope = scope,
            targetId = if (scope == BroadcastScope.MATCH) match.id else match.league.id,
            season = if (scope == BroadcastScope.LEAGUE) match.season else null,
            channelId = channelId,
            channelName = channelName.trim(),
            access = access,
            updatedAtMs = now(),
        )
        update(_items.value.filterNot { it.sameSlot(item) } + item)
    }

    fun remove(id: String) = update(_items.value.filterNot { it.id == id })

    private fun Broadcast.sameSlot(other: Broadcast) =
        scope == other.scope && targetId == other.targetId && season == other.season &&
            channelId == other.channelId && channelName.equals(other.channelName, ignoreCase = true)

    private fun update(items: List<Broadcast>) {
        _items.value = items
        store.save(items)
    }

    companion object {
        const val ID = "manual"
    }
}

class BroadcastRepository(
    val manual: ManualBroadcastProvider,
    private val store: BroadcastStore,
    private val tv: TvScheduleRepository? = null,
) {
    private val _subscriptions = MutableStateFlow(store.loadSubscriptions())
    val subscriptions: StateFlow<Set<String>> = _subscriptions.asStateFlow()

    fun setSubscription(channelId: String, owned: Boolean) {
        val updated = if (owned) _subscriptions.value + channelId else _subscriptions.value - channelId
        _subscriptions.value = updated
        store.saveSubscriptions(updated)
    }

    /** Manual entries win over the automatic listings for the same network. */
    fun known(match: Match): List<Broadcast> {
        val mine = manual.matching(match)
        val mineIds = mine.map { it.channelId }.toSet()
        return mine + tv?.broadcastsNow(match).orEmpty().filterNot { it.channelId in mineIds }
    }
}

/** How easily the person can watch a match, best first. */
enum class WatchRank { OWNED, FREE, PAY_PER_VIEW, NOT_OWNED, UNKNOWN }

object WhatIHave {
    fun rankOf(broadcast: Broadcast, owned: Set<String>): WatchRank = when {
        broadcast.access == AccessType.FREE -> WatchRank.FREE
        broadcast.access == AccessType.PAY_PER_VIEW -> WatchRank.PAY_PER_VIEW
        broadcast.channelId in owned -> WatchRank.OWNED
        else -> WatchRank.NOT_OWNED
    }

    fun rankOf(broadcasts: List<Broadcast>, owned: Set<String>): WatchRank =
        broadcasts.minOfOrNull { rankOf(it, owned) } ?: WatchRank.UNKNOWN

    /** The best option first, so a row shows the channel the person can actually use. */
    fun bestFirst(broadcasts: List<Broadcast>, owned: Set<String>): List<Broadcast> =
        broadcasts.sortedWith(compareBy({ rankOf(it, owned) }, { it.channelName }))

    /** Sorts by what the person can watch, then kick-off. Ties keep their original order. */
    fun <T> sort(
        items: List<T>,
        owned: Set<String>,
        broadcastsOf: (T) -> List<Broadcast>,
        kickoffOf: (T) -> Long?,
    ): List<T> = items.sortedWith(
        compareBy<T> { rankOf(broadcastsOf(it), owned) }.thenBy { kickoffOf(it) ?: Long.MAX_VALUE },
    )
}
