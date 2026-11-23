package app.kerkida.ui.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.data.model.Sport
import app.kerkida.nearby.GreekPlaces
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.NearbyMatch
import app.kerkida.nearby.Place
import app.kerkida.nearby.currentPlace
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.MatchRow
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.common.sportIcon
import app.kerkida.ui.theme.KerkidaIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

data class NearbyUi(
    val place: Place? = null,
    val radiusKm: Int = 50,
    val results: List<NearbyMatch> = emptyList(),
    val isLoading: Boolean = false,
    val locationFailed: Boolean = false,
)

class NearbyViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.appContext.getSharedPreferences("kerkida_nearby", Context.MODE_PRIVATE)
    private val provider = container.provider.value
    private val _state = MutableStateFlow(
        NearbyUi(
            place = prefs.getString(KEY_NAME, null)?.let { Place(it, prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LNG, 0f).toDouble()) },
            radiusKm = prefs.getInt(KEY_RADIUS, 50),
        ),
    )
    val state: StateFlow<NearbyUi> = _state.asStateFlow()
    private val games = LinkedHashMap<String, Match>()
    private var job: Job? = null

    init {
        if (_state.value.place != null) load()
    }

    fun choose(place: Place) {
        prefs.edit().putString(KEY_NAME, place.name).putFloat(KEY_LAT, place.lat.toFloat()).putFloat(KEY_LNG, place.lng.toFloat()).apply()
        _state.update { it.copy(place = place, locationFailed = false) }
        publish()
        if (games.isEmpty()) load()
    }

    fun useLocation(context: Context, label: String) {
        viewModelScope.launch {
            val place = currentPlace(context, label)
            if (place != null) choose(place) else _state.update { it.copy(locationFailed = true) }
        }
    }

    fun setRadius(km: Int) {
        prefs.edit().putInt(KEY_RADIUS, km).apply()
        _state.update { it.copy(radiusKm = km) }
        publish()
    }

    private fun publish() {
        val current = _state.value
        val place = current.place ?: return
        val stadiums = container.stadiums
        val snapshot = games.values.toList()
        viewModelScope.launch(Dispatchers.Default) {
            val found = NearbyLogic.filter(snapshot, place, current.radiusKm, Instant.now(), stadiums::find)
            _state.update { if (it.place == place && it.radiusKm == current.radiusKm) it.copy(results = found) else it }
        }
    }

    private fun load() {
        job?.cancel()
        _state.update { it.copy(isLoading = true) }
        job = viewModelScope.launch(Dispatchers.Default) {
            val today = LocalDate.now()
            // Sports with fixed grounds. Tennis, racing and fights have no local fixtures here.
            val others = provider.sports.filter { it in listOf(Sport.BASKETBALL, Sport.HOCKEY, Sport.AMERICAN_FOOTBALL, Sport.BASEBALL, Sport.VOLLEYBALL, Sport.RUGBY) }
            for (offset in 0 until DAYS) {
                val date = today.plusDays(offset.toLong())
                try {
                    provider.matchDay(date).lastOrNull()?.matches?.forEach { games[it.id] = it }
                    others.forEach { sport ->
                        provider.sportDay(sport, date).lastOrNull()?.matches?.forEach { games[it.id] = it }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: DataException) {
                    // A day that fails just shows fewer matches.
                }
                publish()
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private companion object {
        const val DAYS = 8
        const val KEY_NAME = "name"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val KEY_RADIUS = "radius"
    }
}

private val Radii = listOf(10, 25, 50, 100, 200, 500)

@Composable
fun NearbyScreen(onBack: () -> Unit, onMatch: (String) -> Unit) {
    val viewModel = kerkidaViewModel(key = "nearby") { NearbyViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMap by rememberSaveable { mutableStateOf(false) }
    val myArea = stringResource(R.string.nearby_my_area)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.useLocation(context, myArea)
    }
    val locate = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.useLocation(context, myArea)
        } else {
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.nearby_title), onBack = onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = state.place?.name == myArea,
                onClick = locate,
                leadingIcon = { Icon(KerkidaIcons.Pin, null, modifier = Modifier.size(16.dp)) },
                label = { Text(myArea) },
            )
            GreekPlaces.forEach { place ->
                FilterChip(state.place?.name == place.name, { viewModel.choose(place) }, { Text(place.name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Radii.forEach { km ->
                FilterChip(state.radiusKm == km, { viewModel.setRadius(km) }, { Text("$km km") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (state.isLoading) app.kerkida.ui.common.ScarfProgress(Modifier.padding(top = 4.dp))
        if (state.locationFailed) {
            NoticeLine(stringResource(R.string.nearby_location_failed), KerkidaIcons.Offline, Modifier.padding(12.dp))
        }
        when {
            state.place == null -> MessageView(
                KerkidaIcons.Pin,
                stringResource(R.string.nearby_start_title),
                stringResource(R.string.nearby_start_body),
                primaryLabel = stringResource(R.string.nearby_use_location),
                onPrimary = locate,
            )
            else -> {
                ViewSwitch(showMap) { showMap = it }
                when {
                    showMap -> NearbyMap(state.place!!, state.radiusKm, state.results, onMatch)
                    state.results.isEmpty() && !state.isLoading -> MessageView(
                        KerkidaIcons.Pin,
                        stringResource(R.string.nearby_empty_title),
                        stringResource(R.string.nearby_empty_body),
                    )
                    else -> NearbyList(state.results, onMatch)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(showMap: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        listOf(false to R.string.nearby_view_list, true to R.string.nearby_view_map).forEachIndexed { index, (isMap, label) ->
            SegmentedButton(
                selected = showMap == isMap,
                onClick = { onChange(isMap) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun NearbyList(results: List<NearbyMatch>, onMatch: (String) -> Unit) {
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    val days = results.groupBy { it.match.kickoff?.atZone(zone)?.toLocalDate() }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        days.forEach { (day, games) ->
            item(key = "day-$day") {
                SectionTitle(day?.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)).orEmpty())
            }
            items(games, key = { it.match.id }) { game ->
                SectionCard {
                    MatchRow(game.match, onClick = { onMatch(game.match.id) })
                    Row(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(sportIcon(game.match.league.sport), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${game.stadium.name} · ${game.match.league.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.nearby_km, game.km.toInt().coerceAtLeast(1)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
