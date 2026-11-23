package app.kerkida.ui.planner

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.broadcast.AccessType
import app.kerkida.data.AppContainer
import app.kerkida.data.DataException
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.Match
import app.kerkida.data.model.Sport
import app.kerkida.nearby.GreekPlaces
import app.kerkida.nearby.Place
import app.kerkida.planner.WeekendEntry
import app.kerkida.planner.WeekendListing
import app.kerkida.planner.WeekendLogic
import app.kerkida.tv.TvLogic
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class WeekendUi(
    val place: Place? = null,
    val radiusKm: Int = 50,
    val entries: List<WeekendEntry> = emptyList(),
    val isLoading: Boolean = true,
)

class WeekendViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(WeekendUi())
    val state: StateFlow<WeekendUi> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() = viewModelScope.launch(Dispatchers.Default) {
        val prefs = container.appContext.getSharedPreferences("kerkida_nearby", Context.MODE_PRIVATE)
        val place = prefs.getString("name", null)
            ?.let { Place(it, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lng", 0f).toDouble()) }
            ?: GreekPlaces.first { it.name == "Πάτρα" }
        val radius = prefs.getInt("radius", 50)
        _state.update { it.copy(place = place, radiusKm = radius) }
        val provider = container.provider.value
        runCatching { container.tv.refresh() }
        val days = WeekendLogic.days(LocalDate.now())
        val games = LinkedHashMap<String, Match>()
        val owned = container.broadcasts.subscriptions.value
        fun canWatch(channel: String) = TvLogic.channelIdOf(channel).let { it in owned || TvLogic.accessOf(it) == AccessType.FREE }
        val myTeams = container.favourites.favourites(provider.info.id).value
            .filter { it.favouriteKind == FavouriteKind.TEAM }.map { it.id }.toSet()
        fun publish() {
            val dayNames = days.map { it.toString() }.toSet()
            val tvEvents = TvLogic.groupEvents(container.tv.items.value.filter { it.date in dayNames })
            val linked = games.values.toList()
            val listings = tvEvents.filter { event -> container.tv.matchFor(event.item, linked) == null }
                .mapNotNull { event ->
                    val usable = event.channels.filter(::canWatch)
                    if (usable.isEmpty() || TvLogic.splitTeams(event.item.title) == null) return@mapNotNull null
                    WeekendListing(event.item.key, event.item.title, event.item.startMs, event.item.endMs, usable)
                }
            val entries = WeekendLogic.plan(
                linked, listings, place, radius, myTeams, Instant.now(), container.stadiums::find,
            ) { game -> container.tv.itemsFor(game).map { it.channel }.filter(::canWatch).distinct() }
            _state.update { it.copy(entries = entries) }
        }
        publish()
        for (date in days) {
            try {
                provider.matchDay(date).lastOrNull()?.matches?.forEach { games[it.id] = it }
                if (Sport.BASKETBALL in provider.sports) {
                    provider.sportDay(Sport.BASKETBALL, date).lastOrNull()?.matches?.forEach { games[it.id] = it }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: DataException) {
                // A day that fails just shows less.
            }
            publish()
        }
        _state.update { it.copy(isLoading = false) }
    }
}

@Composable
fun WeekendScreen(onBack: () -> Unit, onMatch: (String) -> Unit) {
    val viewModel = kerkidaViewModel(key = "weekend") { WeekendViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.weekend_title), onBack = onBack)
        if (state.isLoading) app.kerkida.ui.common.ScarfProgress()
        state.place?.let {
            Text(
                stringResource(R.string.weekend_place, it.name, state.radiusKm),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (state.entries.isEmpty() && !state.isLoading) {
            MessageView(KerkidaIcons.Calendar, stringResource(R.string.weekend_empty_title), stringResource(R.string.weekend_empty_body))
            return
        }
        val byDay = state.entries.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            byDay.forEach { (day, entries) ->
                item(key = "day-$day") { SectionTitle(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))) }
                items(entries, key = { it.key }) { entry -> EntryCard(entry, zone, onMatch) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: WeekendEntry, zone: ZoneId, onMatch: (String) -> Unit) {
    val colors = KerkidaTheme.colors
    val time = Instant.ofEpochMilli(entry.startMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    SectionCard {
        Column(
            Modifier
                .fillMaxWidth()
                .then(entry.match?.let { game -> Modifier.clickable { onMatch(game.id) } } ?: Modifier)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.titleSmall, color = colors.win)
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isPick) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.isMine) Icon(KerkidaIcons.StarFilled, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
            entry.match?.league?.name?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.km != null) {
                Line(KerkidaIcons.Pin, stringResource(R.string.weekend_ground, entry.ground.orEmpty(), entry.km.toInt().coerceAtLeast(1)))
            }
            if (entry.channels.isNotEmpty()) Line(KerkidaIcons.Tv, entry.channels.joinToString(", "))
            when {
                entry.isPick -> Text(stringResource(R.string.weekend_pick), style = MaterialTheme.typography.labelMedium, color = colors.win)
                entry.clashesWith != null && (entry.isMine || entry.km != null) -> Text(
                    stringResource(R.string.weekend_clash, entry.clashesWith),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Line(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
