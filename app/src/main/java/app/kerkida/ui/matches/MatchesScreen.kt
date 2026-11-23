package app.kerkida.ui.matches

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.broadcast.WhatIHave
import app.kerkida.features.Feature
import app.kerkida.ui.broadcast.RankHeader
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.data.AppContainer
import app.kerkida.data.Favourite
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.Sport
import app.kerkida.data.model.isInPlay
import app.kerkida.ui.common.BrandHeader
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.FavouriteButton
import app.kerkida.ui.common.FreshnessFooter
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MatchRow
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SportBadge
import app.kerkida.ui.common.SportStrip
import app.kerkida.ui.common.sportIcon
import app.kerkida.ui.common.sportLabel
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

enum class MatchFilter { ALL, LIVE, FAVOURITES }

data class MatchesUiState(
    val date: LocalDate = LocalDate.now(),
    val filter: MatchFilter = MatchFilter.ALL,
    val sport: Sport = Sport.FOOTBALL,
    val day: MatchDay? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: Throwable? = null,
)

class MatchesViewModel(private val container: AppContainer) : ViewModel() {
    private val provider = container.provider.value
    val providerId: String = provider.info.id
    fun currentSports(): List<Sport> = provider.sports
    val favourites: StateFlow<List<Favourite>> = container.favourites.favourites(providerId)

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        load(refresh = false)
    }

    private fun priorityLeagues() = favourites.value
        .filter { it.favouriteKind == FavouriteKind.LEAGUE }
        .map { it.id }

    private fun dayFlow(date: LocalDate, sport: Sport, refresh: Boolean) =
        if (sport == Sport.FOOTBALL) provider.matchDay(date, priorityLeagues(), refresh) else provider.sportDay(sport, date, refresh)

    fun selectSport(sport: Sport) {
        if (sport == _state.value.sport) return
        _state.update { it.copy(sport = sport, day = null, isLoading = true, error = null) }
        load(refresh = false)
    }

    private fun load(refresh: Boolean) {
        loadJob?.cancel()
        val date = _state.value.date
        val sport = _state.value.sport
        _state.update {
            it.copy(isLoading = it.day == null, isRefreshing = refresh, error = null)
        }
        loadJob = viewModelScope.launch {
            try {
                dayFlow(date, sport, refresh).flowOn(Dispatchers.Default).collect { day ->
                    _state.update {
                        it.copy(day = day, isLoading = false, isRefreshing = refresh && !day.isComplete)
                    }
                }
                _state.update { it.copy(isRefreshing = false, isLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error, isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun selectDate(date: LocalDate) {
        if (date == _state.value.date) return
        _state.update { it.copy(date = date, day = null, isLoading = true, error = null) }
        load(refresh = false)
    }

    fun selectFilter(filter: MatchFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() = load(refresh = true)

    fun retry() {
        _state.update { it.copy(day = null) }
        load(refresh = false)
    }

    fun toggleLeague(league: LeagueRef) {
        container.favourites.toggle(
            providerId,
            Favourite(FavouriteKind.LEAGUE.name, league.id, league.name, league.country, league.badgeUrl),
        )
    }

    /** Quietly updates today's list while matches are running and the screen is visible. */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                val current = _state.value
                if (current.date != LocalDate.now() || !needsPolling(current.day)) continue
                if (loadJob?.isActive == true) continue
                try {
                    val latest = dayFlow(current.date, current.sport, false)
                        .flowOn(Dispatchers.Default)
                        .lastOrNull()
                    if (latest != null && _state.value.date == current.date && _state.value.sport == current.sport) {
                        _state.update { it.copy(day = latest) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The next round of polling tries again. The current list stays on screen.
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun needsPolling(day: MatchDay?): Boolean {
        val now = Instant.now()
        return day?.matches?.any { match ->
            match.status.isInPlay || match.kickoff?.let {
                Duration.between(now, it).abs() < Duration.ofMinutes(20)
            } == true
        } == true
    }

    private companion object {
        const val POLL_MS = 90_000L
    }
}

private data class LeagueGroup(val league: LeagueRef, val matches: List<Match>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(
    onMatch: (String) -> Unit,
    onLeague: (String) -> Unit,
    onSettings: () -> Unit,
    onFantasy: () -> Unit,
    onNearby: () -> Unit,
    onWeekend: () -> Unit,
) {
    val viewModel = kerkidaViewModel { MatchesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var sortByMine by rememberSaveable { mutableStateOf(false) }
    val canSortByMine = isFeatureOn(Feature.BROADCASTS)
    val tvGuide = appContainer().tv
    LaunchedEffect(canSortByMine) { if (canSortByMine) tvGuide.refresh() }

    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val isShort = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
    var showFilters by rememberSaveable { mutableStateOf(true) }
    val hideOnScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) showFilters = false else if (available.y > 6f) showFilters = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(if (isShort) Modifier.nestedScroll(hideOnScroll) else Modifier),
    ) {
        BrandHeader(title = stringResource(R.string.app_name), showMark = true) {
            IconButton(onClick = onWeekend) {
                Icon(KerkidaIcons.Calendar, contentDescription = stringResource(R.string.weekend_title))
            }
            IconButton(onClick = onNearby) {
                Icon(KerkidaIcons.Pin, contentDescription = stringResource(R.string.nearby_title))
            }
            if (isFeatureOn(Feature.FANTASY)) {
                IconButton(onClick = onFantasy) {
                    Icon(KerkidaIcons.Trophy, contentDescription = stringResource(R.string.fantasy_title))
                }
            }
            IconButton(onClick = { showPicker = true }) {
                Icon(KerkidaIcons.Calendar, contentDescription = stringResource(R.string.action_pick_date))
            }
            IconButton(onClick = onSettings) {
                Icon(KerkidaIcons.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
        val chosenLeagues by appContainer().catalog.selected.collectAsStateWithLifecycle()
        val sports = remember(chosenLeagues) { viewModel.currentSports() }
        var lastLeagues by remember { mutableStateOf(chosenLeagues) }
        LaunchedEffect(chosenLeagues) {
            if (chosenLeagues != lastLeagues) {
                lastLeagues = chosenLeagues
                viewModel.retry()
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = !isShort || showFilters) {
            Column {
                DateStrip(selected = state.date, onSelect = viewModel::selectDate)
                SportStrip(sports, state.sport, viewModel::selectSport)
                FilterRow(
                    selected = state.filter,
                    onSelect = viewModel::selectFilter,
                    showMine = canSortByMine,
                    mine = sortByMine,
                    onMine = { sortByMine = !sortByMine },
                )
            }
        }

        val day = state.day
        if (day != null && !day.isComplete) {
            Column(Modifier.fillMaxWidth()) {
                app.kerkida.ui.common.ScarfProgress(
                    progress = { (day.loadedSources + day.failedSources).toFloat() / day.totalSources },
                )
                Text(
                    text = stringResource(R.string.loading_progress, day.loadedSources + day.failedSources, day.totalSources),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.isLoading && day == null -> LoadingView()
                state.error != null && day == null -> ErrorView(state.error!!, viewModel::retry)
                day == null -> LoadingView()
                else -> MatchList(
                    date = state.date,
                    sport = state.sport,
                    day = day,
                    filter = state.filter,
                    sortByMine = sortByMine && canSortByMine,
                    favourites = favourites,
                    onMatch = onMatch,
                    onLeague = onLeague,
                    onToggleLeague = viewModel::toggleLeague,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.selectDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private const val SHORT_SCREEN_DP = 480

@Composable
private fun DateStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    val days = remember(today, selected) {
        val base = (-7L..7L).map(today::plusDays)
        if (selected in base) base else (base + selected).sorted()
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selected) - 2).coerceAtLeast(0))
    LaunchedEffect(selected, days) {
        listState.animateScrollToItem((days.indexOf(selected) - 2).coerceAtLeast(0))
    }
    val locale = currentLocale()
    val colors = KerkidaTheme.colors
    Surface(color = colors.brandBar, contentColor = colors.onBrandBar) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(days, key = { it.toEpochDay() }) { date ->
                val isSelected = date == selected
                val label = when (date) {
                    today -> stringResource(R.string.date_today)
                    today.minusDays(1) -> stringResource(R.string.date_yesterday)
                    today.plusDays(1) -> stringResource(R.string.date_tomorrow)
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            if (isSelected) colors.accent else colors.onBrandBar.copy(alpha = 0.1f),
                            MaterialTheme.shapes.small,
                        )
                        .clickable(role = Role.Tab) { onSelect(date) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    val textColor = if (isSelected) MaterialTheme.colorScheme.scrim else colors.onBrandBar
                    Text(label, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
                    Text(
                        text = date.format(DateTimeFormatter.ofPattern("dd/MM", locale)),
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: MatchFilter,
    onSelect: (MatchFilter) -> Unit,
    showMine: Boolean,
    mine: Boolean,
    onMine: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MatchFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                MatchFilter.ALL -> R.string.filter_all
                                MatchFilter.LIVE -> R.string.filter_live
                                MatchFilter.FAVOURITES -> R.string.filter_favourites
                            },
                        ),
                    )
                },
            )
        }
        if (showMine) {
            FilterChip(
                selected = mine,
                onClick = onMine,
                leadingIcon = { Icon(KerkidaIcons.Tv, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(R.string.filter_mine)) },
            )
        }
    }
}

@Composable
private fun MatchList(
    date: LocalDate,
    sport: Sport,
    day: MatchDay,
    filter: MatchFilter,
    sortByMine: Boolean,
    favourites: List<Favourite>,
    onMatch: (String) -> Unit,
    onLeague: (String) -> Unit,
    onToggleLeague: (LeagueRef) -> Unit,
    onRetry: () -> Unit,
) {
    val favouriteLeagues = favourites.filter { it.favouriteKind == FavouriteKind.LEAGUE }.map { it.id }.toSet()
    val favouriteTeams = favourites.filter { it.favouriteKind == FavouriteKind.TEAM }.map { it.id }.toSet()
    val favouriteMatches = favourites.filter { it.favouriteKind == FavouriteKind.MATCH }.map { it.id }.toSet()
    val container = appContainer()
    val shield = container.shield
    val shieldEntries by shield.entries.collectAsStateWithLifecycle()
    val inPlayIds = remember(day.matches, shieldEntries, favourites) {
        day.matches.filter(shield::looksInPlay).map { it.id }.toSet()
    }

    val visible = day.matches.filter { match ->
        when (filter) {
            MatchFilter.ALL -> true
            MatchFilter.LIVE -> match.id in inPlayIds
            MatchFilter.FAVOURITES -> match.league.id in favouriteLeagues ||
                match.home.id in favouriteTeams || match.away.id in favouriteTeams || match.id in favouriteMatches
        }
    }
    val groups = visible
        .groupBy { it.league.id.ifEmpty { it.league.name } }
        .values
        .map { LeagueGroup(it.first().league, it) }
        .sortedWith(
            compareByDescending<LeagueGroup> { it.league.id in favouriteLeagues }
                .thenByDescending { group -> group.matches.any { it.id in inPlayIds } }
                .thenBy { it.league.name },
        )

    if (sortByMine) {
        app.kerkida.ui.tv.TvDayList(date, day.matches, onMatch, sport)
        return
    }

    if (groups.isEmpty()) {
        if (!day.isComplete) {
            LoadingView()
            return
        }
        val error = day.firstError
        if (error != null && day.matches.isEmpty()) {
            ErrorView(error, onRetry)
            return
        }
        val (titleRes, bodyRes) = when (filter) {
            MatchFilter.ALL -> R.string.empty_day_title to R.string.empty_day_body
            MatchFilter.LIVE -> R.string.empty_live_title to R.string.empty_live_body
            MatchFilter.FAVOURITES -> R.string.empty_fav_filter_title to R.string.empty_fav_filter_body
        }
        MessageView(KerkidaIcons.Whistle, stringResource(titleRes), stringResource(bodyRes))
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(sortByMine) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (day.isStale) {
            item(key = "stale") { NoticeLine(stringResource(R.string.stale_live_notice), KerkidaIcons.Offline) }
        }
        if (day.failedSources > 0 && day.isComplete) {
            item(key = "partial") { NoticeLine(stringResource(R.string.partial_sources), KerkidaIcons.Offline) }
        }
        items(groups, key = { it.league.id.ifEmpty { it.league.name } }) { group ->
            LeagueCard(
                group = group,
                isFavourite = group.league.id in favouriteLeagues,
                onLeague = onLeague,
                onToggle = { onToggleLeague(group.league) },
                onMatch = onMatch,
            )
        }
        if (day.isLimitedCoverage) {
            item(key = "coverage") { NoticeLine(stringResource(R.string.coverage_note)) }
        }
        item(key = "fresh") { FreshnessFooter(day.fetchedAt, day.isStale) }
    }
}

@Composable
private fun LeagueCard(
    group: LeagueGroup,
    isFavourite: Boolean,
    onLeague: (String) -> Unit,
    onToggle: () -> Unit,
    onMatch: (String) -> Unit,
) {
    // Tennis, racing and fights have no league page.
    val canOpen = group.league.id.isNotEmpty() && group.league.sport !in setOf(Sport.TENNIS, Sport.MOTOR, Sport.MMA, Sport.GOLF)
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = canOpen, role = Role.Button) { onLeague(group.league.id) }
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SportBadge(sportIcon(group.league.sport), sportLabel(group.league.sport))
            Spacer(Modifier.width(8.dp))
            val leagueLogo = group.league.badgeUrl ?: app.kerkida.ui.shield.appContainer().catalog.find(group.league.id)?.logo
            if (leagueLogo != null) {
                Crest(leagueLogo, group.league.name, 22.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    group.league.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                group.league.country?.let { app.kerkida.ui.common.placeName(it) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canOpen) {
                FavouriteButton(isFavourite, onToggle)
                Icon(
                    KerkidaIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                )
            }
        }
        group.matches.forEachIndexed { index, match ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MatchRow(match, onClick = { onMatch(match.id) })
        }
    }
}
