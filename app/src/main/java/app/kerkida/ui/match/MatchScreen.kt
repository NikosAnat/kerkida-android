package app.kerkida.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.DataException
import app.kerkida.data.espn.HeadToHead
import app.kerkida.data.espn.HeadToHeadRecord
import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.TeamLineup
import app.kerkida.fantasy.LineupRatings
import app.kerkida.fantasy.MatchRatings
import app.kerkida.fantasy.SquadPlayer
import app.kerkida.features.Feature
import app.kerkida.preview.ElevenSource
import app.kerkida.preview.KnownEleven
import app.kerkida.preview.MatchPreview
import app.kerkida.preview.PreviewBuilder
import app.kerkida.shield.DelayBuffer
import app.kerkida.shield.ShieldedView
import app.kerkida.ui.broadcast.BroadcastCard
import app.kerkida.ui.season.AudioButton
import app.kerkida.ui.season.WatchedButton
import app.kerkida.ui.shield.ShieldEye
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.ui.shield.isHiddenAfterKickoff
import app.kerkida.ui.shield.rememberShieldedView
import app.kerkida.ui.shield.shieldedStatus
import app.kerkida.data.Favourite
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.TeamRef
import app.kerkida.data.model.isInPlay
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.FavouriteButton
import app.kerkida.ui.common.FreshnessFooter
import app.kerkida.ui.common.Loadable
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.Resource
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.StandingsTable
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.common.localTimeText
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MatchViewModel(private val container: AppContainer, matchId: String) : ViewModel() {
    private val provider = container.provider.value
    private val providerId = provider.info.id
    val favourites: StateFlow<List<Favourite>> = container.favourites.favourites(providerId)
    val details = Resource(viewModelScope) { refresh -> provider.matchDetails(matchId, refresh) }
    private var pollJob: Job? = null
    private var ratingsJob: Job? = null
    private var previewJob: Job? = null

    /** Ratings keep their arrival time, so a delayed viewer only sees what is on their screen. */
    private val ratingsBuffer = DelayBuffer<MatchRatings>()
    private val _ratings = MutableStateFlow<MatchRatings?>(null)
    val ratingsVersion: StateFlow<MatchRatings?> = _ratings.asStateFlow()
    private val _ratingsLoading = MutableStateFlow(true)
    val ratingsLoading: StateFlow<Boolean> = _ratingsLoading.asStateFlow()
    private val _preview = MutableStateFlow<MatchPreview?>(null)
    val preview: StateFlow<MatchPreview?> = _preview.asStateFlow()
    private val _previewLoading = MutableStateFlow(false)
    val previewLoading: StateFlow<Boolean> = _previewLoading.asStateFlow()

    init {
        details.load()
        viewModelScope.launch {
            var lastFetched: java.time.Instant? = null
            details.state.collect { state ->
                val ready = state as? Loadable.Ready<MatchDetails> ?: return@collect
                if (ready.fetchedAt != lastFetched) {
                    lastFetched = ready.fetchedAt
                    loadRatings(ready.value)
                    loadPhotos(ready.value)
                }
            }
        }
    }

    private val _photos = MutableStateFlow<Map<String, String>>(emptyMap())
    val photos: StateFlow<Map<String, String>> = _photos.asStateFlow()
    private var photosFor: Set<String> = emptySet()

    /** Starts as soon as the match opens, so the faces are ready when the players tab is. */
    private fun loadPhotos(data: MatchDetails) {
        val extras = data.extras ?: return
        if (!container.flags.isOn(Feature.PLAYER_PHOTOS) || data.match.league.sport != app.kerkida.data.model.Sport.FOOTBALL) return
        val players = listOfNotNull(extras.home, extras.away).flatMap { it.players }.map { it.id }.toSet()
        if (players.isEmpty() || players == photosFor) return
        photosFor = players
        viewModelScope.launch(Dispatchers.Default) {
            val found = container.playerPhotos.forMatch(data.match, extras)
            _photos.value = found
            container.playerPhotos.preload(extras, found)
        }
    }

    fun ratingsFor(view: ShieldedView): MatchRatings? {
        if (!view.isFullyOpen) return null
        val cutoff = view.statsCutoffMs ?: return _ratings.value
        return ratingsBuffer.atOrBefore(cutoff)
    }

    private fun loadRatings(data: MatchDetails) {
        if (!container.flags.isOn(Feature.RATINGS) || data.match.status == MatchStatus.SCHEDULED) {
            _ratingsLoading.value = false
            return
        }
        ratingsJob?.cancel()
        ratingsJob = viewModelScope.launch(Dispatchers.Default) {
            val fantasy = try {
                container.fantasy.ratings(data.match)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // FPL confirms a fixture hours after full time, but the match itself is already over.
            val ratings = (
                fantasy?.takeIf { it.home.isNotEmpty() || it.away.isNotEmpty() }
                    ?: LineupRatings.build(data.match, data.extras)
                )?.let { it.copy(isFinal = it.isFinal || data.match.status == MatchStatus.FINISHED) }
            if (ratings != null) {
                ratingsBuffer.add(System.currentTimeMillis(), ratings)
                _ratings.value = ratings
            }
            _ratingsLoading.value = false
        }
    }

    private val _headToHead = MutableStateFlow<HeadToHeadRecord?>(null)
    val headToHead: StateFlow<HeadToHeadRecord?> = _headToHead.asStateFlow()

    /** A meeting the person is keeping hidden is left out, so history cannot spoil it. */
    fun loadHeadToHead(match: Match) {
        if (_headToHead.value != null || match.league.sport != app.kerkida.data.model.Sport.FOOTBALL) return
        viewModelScope.launch(Dispatchers.Default) {
            val games = try {
                provider.teamHistory(match.home.id, H2H_SEASONS).filter { container.shield.view(it).showScore }
            } catch (error: CancellationException) {
                throw error
            } catch (_: DataException) {
                emptyList()
            }
            _headToHead.value = HeadToHead.of(games, match.home.id, match.away.id, match.id)
        }
    }

    fun loadPreview(data: MatchDetails) {
        if (_preview.value != null || previewJob?.isActive == true) return
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            _previewLoading.value = true
            try {
                val match = data.match
                val homeGames = recentGames(match.home.id)
                val awayGames = recentGames(match.away.id)
                val squads = try {
                    container.fantasy.squads(match)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                _preview.value = PreviewBuilder.build(
                    match = match,
                    homeRecent = homeGames,
                    awayRecent = awayGames,
                    standings = data.standings,
                    squads = squads,
                    homeEleven = knownEleven(data.extras?.home, homeGames, match.home.id),
                    awayEleven = knownEleven(data.extras?.away, awayGames, match.away.id),
                )
            } finally {
                _previewLoading.value = false
            }
        }
    }

    /** A game the person is keeping hidden must not leak through another match's form guide. */
    private suspend fun recentGames(teamId: String): List<Match> = try {
        provider.teamMatches(teamId).data.recent.filter { container.shield.view(it).showScore }
    } catch (_: DataException) {
        emptyList()
    }

    private suspend fun knownEleven(official: TeamLineup?, recent: List<Match>, teamId: String): KnownEleven? {
        val starters = official?.players?.filter { it.isStarter }.orEmpty()
        if (starters.isNotEmpty()) return KnownEleven(starters.map(::toSquad), ElevenSource.OFFICIAL, official?.formation)
        val last = recent.firstOrNull { it.status == MatchStatus.FINISHED } ?: return null
        return try {
            val extras = provider.matchDetails(last.id).data.extras ?: return null
            val lineup = if (last.home.id == teamId) extras.home else extras.away
            lineup?.players?.filter { it.isStarter }?.takeIf { it.isNotEmpty() }
                ?.let { players -> KnownEleven(players.map(::toSquad), ElevenSource.LAST_MATCH, lineup.formation) }
        } catch (_: DataException) {
            null
        }
    }

    private fun toSquad(player: LineupPlayer) = SquadPlayer(
        id = player.id.toIntOrNull() ?: player.id.hashCode(),
        name = player.name,
        position = LineupRatings.positionOf(player.positionCode),
        starts = 0,
        minutes = player.minutes,
        isAvailable = true,
        chance = null,
        news = "",
        positionCode = player.positionCode,
        jersey = player.jersey,
    )

    fun refresh() = details.load(refresh = true)

    fun retry() = details.load()

    fun toggleFavourite(match: Match) {
        container.favourites.toggle(
            providerId,
            Favourite(
                kind = FavouriteKind.MATCH.name,
                id = match.id,
                title = "${match.home.name} – ${match.away.name}",
                subtitle = match.league.name,
                badgeUrl = match.home.badgeUrl,
                awayBadgeUrl = match.away.badgeUrl,
                kickoffEpochSeconds = match.kickoff?.epochSecond,
            ),
        )
    }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                val ready = details.state.value as? Loadable.Ready<MatchDetails> ?: continue
                if (ready.value.match.status.isInPlay) details.load(silent = true)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private companion object {
        const val POLL_MS = 75_000L
        const val H2H_SEASONS = 4
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(
    matchId: String,
    onBack: () -> Unit,
    onTeam: (String) -> Unit,
    onLeague: (String) -> Unit,
) {
    val viewModel = kerkidaViewModel(key = "match-$matchId") { MatchViewModel(it, matchId) }
    val state by viewModel.details.state.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val ready = state as? Loadable.Ready<MatchDetails>
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = ready?.value?.match?.league?.name ?: stringResource(R.string.match_title),
            onBack = onBack,
        ) {
            ready?.value?.match?.let { match ->
                FavouriteButton(
                    isFavourite = favourites.any { it.favouriteKind == FavouriteKind.MATCH && it.id == match.id },
                    onToggle = { viewModel.toggleFavourite(match) },
                    tint = KerkidaTheme.colors.onBrandBar,
                )
                val audioView = rememberShieldedView(match, ready?.value?.events.orEmpty())
                AudioButton(audioView, audioView.events)
                ShieldEye(match, tint = KerkidaTheme.colors.onBrandBar)
            }
        }
        when (val current = state) {
            Loadable.Loading -> LoadingView(rows = 4)
            is Loadable.Failed -> ErrorView(current.error, viewModel::retry)
            is Loadable.Ready -> PullToRefreshBox(
                isRefreshing = current.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                MatchContent(current, viewModel, onTeam, onLeague)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchContent(
    ready: Loadable.Ready<MatchDetails>,
    viewModel: MatchViewModel,
    onTeam: (String) -> Unit,
    onLeague: (String) -> Unit,
) {
    val source = ready.value
    val view = rememberShieldedView(source.match, source.events)
    val details = source.copy(match = view.match, events = view.events)
    val notStarted = source.match.status == MatchStatus.SCHEDULED
    val ratingsOn = isFeatureOn(Feature.RATINGS)
    val previewOn = isFeatureOn(Feature.PREVIEW)
    var tabName by rememberSaveable { mutableStateOf(MatchTab.EVENTS.name) }
    val highlights = app.kerkida.ui.shield.appContainer().highlights
    val wantsHighlights = isFeatureOn(Feature.HIGHLIGHTS) && source.match.status == MatchStatus.FINISHED
    val allVideos by highlights.videos.collectAsStateWithLifecycle()
    val matchVideos = androidx.compose.runtime.remember(allVideos, source.match, wantsHighlights) {
        if (wantsHighlights) app.kerkida.highlights.HighlightLogic.forMatch(allVideos, source.match) else emptyList()
    }
    androidx.compose.runtime.LaunchedEffect(source.match.id, wantsHighlights) {
        if (wantsHighlights) highlights.refresh()
    }
    val tabs = buildList {
        add(MatchTab.EVENTS)
        if (matchVideos.isNotEmpty()) add(MatchTab.HIGHLIGHTS)
        val isFootball = source.match.league.sport == app.kerkida.data.model.Sport.FOOTBALL
        if (notStarted && previewOn && isFootball) add(MatchTab.PREVIEW)
        if (!notStarted && ratingsOn && isFootball) add(MatchTab.PLAYERS)
        if (source.match.home.id.isNotEmpty()) add(MatchTab.TABLE)
        add(MatchTab.INFO)
    }
    val tab = tabs.firstOrNull { it.name == tabName } ?: MatchTab.EVENTS
    val ratingsVersion by viewModel.ratingsVersion.collectAsStateWithLifecycle()
    val ratingsLoading by viewModel.ratingsLoading.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val previewLoading by viewModel.previewLoading.collectAsStateWithLifecycle()
    val shownRatings = androidx.compose.runtime.remember(view, ratingsVersion) { viewModel.ratingsFor(view) }

    androidx.compose.runtime.LaunchedEffect(tab, source.match.id) {
        if (tab == MatchTab.PREVIEW) viewModel.loadPreview(source)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "hero") { ScoreHero(view, source.match, onTeam) }
        stickyHeader(key = "tabs") {
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 8.dp) {
                tabs.forEach { option ->
                    Tab(
                        selected = tab == option,
                        onClick = { tabName = option.name },
                        text = { Text(stringResource(option.label), maxLines = 1) },
                    )
                }
            }
        }
        when (tab) {
            MatchTab.EVENTS -> eventsSection(details, view, source.match)
            MatchTab.HIGHLIGHTS -> item(key = "highlights") { app.kerkida.ui.tv.MatchHighlights(matchVideos, source.match) }
            MatchTab.PREVIEW -> item(key = "preview") { PreviewTab(source.match, preview, previewLoading) }
            MatchTab.PLAYERS -> item(key = "players") {
                val photos by viewModel.photos.collectAsStateWithLifecycle()
                PlayersTab(
                    match = source.match,
                    extras = androidx.compose.runtime.remember(source.extras, photos) {
                        app.kerkida.data.photos.PlayerPhotoLogic.withPhotos(source.extras, photos)
                    },
                    ratings = shownRatings,
                    isLoading = ratingsLoading || (view.isFullyOpen && view.statsCutoffMs != null && ratingsVersion != null),
                    isHidden = view.isShielded && !view.showEvents,
                )
            }
            MatchTab.TABLE -> item(key = "table") {
                Box(Modifier.padding(12.dp)) {
                    val standings = details.standings
                    if (standings == null || standings.rows.isEmpty()) {
                        MessageView(
                            KerkidaIcons.Leagues,
                            stringResource(R.string.table_empty_title),
                            stringResource(R.string.table_empty_body),
                            scrollable = false,
                        )
                    } else {
                        StandingsTable(
                            standings = standings,
                            onTeamClick = onTeam,
                            highlightTeamIds = setOf(details.match.home.id, details.match.away.id),
                        )
                    }
                }
            }
            MatchTab.INFO -> item(key = "info") {
                val record by viewModel.headToHead.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(source.match.id) { viewModel.loadHeadToHead(source.match) }
                Column {
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) { WatchedButton(details.match) }
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) { app.kerkida.ui.season.AttendedButton(details.match) }
                    record?.takeIf { it.games.isNotEmpty() }?.let { history ->
                        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) { HeadToHeadCard(source.match, history, onTeam) }
                    }
                    InfoSection(details, onLeague)
                }
            }
        }
        item(key = "fresh") {
            FreshnessFooter(ready.fetchedAt, ready.isStale, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

private enum class MatchTab(val label: Int) {
    EVENTS(R.string.match_tab_events),
    HIGHLIGHTS(R.string.tv_tab_highlights),
    PREVIEW(R.string.match_tab_preview),
    PLAYERS(R.string.match_tab_players),
    TABLE(R.string.match_tab_table),
    INFO(R.string.match_tab_info),
}

private fun androidx.compose.foundation.lazy.LazyListScope.eventsSection(
    details: MatchDetails,
    view: ShieldedView,
    original: Match,
) {
    if (view.isShielded && !view.showEvents && original.status != MatchStatus.SCHEDULED) {
        item(key = "events-hidden") {
            MessageView(
                KerkidaIcons.EyeOff,
                stringResource(if (view.waitingForSync) R.string.shield_syncing else R.string.shield_events_title),
                stringResource(R.string.shield_events_body),
                scrollable = false,
            )
        }
        return
    }
    if (details.events.isEmpty()) {
        item(key = "no-events") {
            val notStarted = original.status == MatchStatus.SCHEDULED
            MessageView(
                KerkidaIcons.Whistle,
                stringResource(if (notStarted) R.string.events_not_started_title else R.string.events_empty_title),
                stringResource(if (notStarted) R.string.events_not_started_body else R.string.events_empty_body),
                scrollable = false,
            )
        }
        return
    }
    if (details.eventsMayBeIncomplete) {
        item(key = "events-partial") {
            NoticeLine(stringResource(R.string.events_partial), modifier = Modifier.padding(12.dp))
        }
    }
    item(key = "events-top") { Spacer(Modifier.height(8.dp)) }
    items(details.events) { event -> EventLine(event) }
}

@Composable
private fun ScoreHero(view: ShieldedView, original: Match, onTeam: (String) -> Unit) {
    val match = view.match
    val hiddenScore = isHiddenAfterKickoff(view, original)
    val colors = KerkidaTheme.colors
    val locale = currentLocale()
    val inPlay = match.status.isInPlay
    val kickoffText = match.kickoff?.let {
        it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.brandBar)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        match.round?.let {
            Text(
                stringResource(R.string.round_label, it),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onBrandBar.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            HeroTeam(match.home, onTeam, Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                val hasScore = match.homeScore != null && match.awayScore != null
                Text(
                    text = when {
                        hasScore -> "${match.homeScore} – ${match.awayScore}"
                        hiddenScore -> "? – ?"
                        else -> match.kickoff?.localTimeText() ?: "–"
                    },
                    color = colors.onBrandBar,
                    fontSize = if (hasScore || hiddenScore) 36.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(if (inPlay) colors.live else colors.onBrandBar.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inPlay) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.onLive),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        shieldedStatus(view, original),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inPlay) colors.onLive else colors.onBrandBar,
                    )
                }
            }
            HeroTeam(match.away, onTeam, Modifier.weight(1f))
        }
        match.resultText?.takeIf { match.league.sport != app.kerkida.data.model.Sport.FOOTBALL }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onBrandBar.copy(alpha = 0.9f), textAlign = TextAlign.Center)
        }
        kickoffText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onBrandBar.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun HeroTeam(team: TeamRef, onTeam: (String) -> Unit, modifier: Modifier) {
    val colors = KerkidaTheme.colors
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = team.id.isNotEmpty(), role = Role.Button) { onTeam(team.id) }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crest(team.badgeUrl, team.name, 54.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            app.kerkida.ui.common.clubName(team.name),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onBrandBar,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class EventLook(val icon: ImageVector, val labelRes: Int)

private fun lookOf(type: MatchEventType) = when (type) {
    MatchEventType.GOAL -> EventLook(KerkidaIcons.Matches, R.string.event_goal)
    MatchEventType.OWN_GOAL -> EventLook(KerkidaIcons.Matches, R.string.event_own_goal)
    MatchEventType.PENALTY -> EventLook(KerkidaIcons.Matches, R.string.event_penalty)
    MatchEventType.MISSED_PENALTY -> EventLook(KerkidaIcons.Close, R.string.event_missed_penalty)
    MatchEventType.YELLOW_CARD -> EventLook(KerkidaIcons.Card, R.string.event_yellow)
    MatchEventType.RED_CARD -> EventLook(KerkidaIcons.Card, R.string.event_red)
    MatchEventType.SUBSTITUTION -> EventLook(KerkidaIcons.Swap, R.string.event_sub)
    MatchEventType.VAR -> EventLook(KerkidaIcons.Info, R.string.event_var)
    MatchEventType.OTHER -> EventLook(KerkidaIcons.Whistle, R.string.event_other)
}

/** A central time spine with home events on the left and away events on the right. */
@Composable
private fun EventLine(event: MatchEvent) {
    val look = lookOf(event.type)
    val colors = KerkidaTheme.colors
    val tint: Color = when (event.type) {
        MatchEventType.YELLOW_CARD -> colors.marigold
        MatchEventType.RED_CARD -> colors.loss
        MatchEventType.GOAL, MatchEventType.PENALTY -> colors.win
        MatchEventType.OWN_GOAL, MatchEventType.MISSED_PENALTY -> colors.loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (event.side != Side.AWAY) EventText(event, look, tint, alignEnd = true)
        }
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = event.minute?.let { "$it′" } ?: "–",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (event.side == Side.AWAY) EventText(event, look, tint, alignEnd = false)
        }
    }
}

@Composable
private fun EventText(event: MatchEvent, look: EventLook, tint: Color, alignEnd: Boolean) {
    val icon = @Composable {
        Icon(look.icon, contentDescription = stringResource(look.labelRes), tint = tint, modifier = Modifier.size(18.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!alignEnd) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                event.player ?: stringResource(look.labelRes),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(stringResource(look.labelRes), event.detail?.takeIf { it != event.player }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (alignEnd) {
            Spacer(Modifier.width(6.dp))
            icon()
        }
    }
}

@Composable
private fun InfoSection(details: MatchDetails, onLeague: (String) -> Unit) {
    val match = details.match
    val locale = currentLocale()
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BroadcastCard(match)
        StadiumCard(match)
        SectionCard {
            InfoRow(
                KerkidaIcons.Leagues,
                stringResource(R.string.info_league),
                match.league.name,
                onClick = if (match.league.id.isNotEmpty()) ({ onLeague(match.league.id) }) else null,
            )
            match.kickoff?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    KerkidaIcons.Clock,
                    stringResource(R.string.info_kickoff),
                    it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", locale)),
                )
            }
            match.season?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(KerkidaIcons.Calendar, stringResource(R.string.info_season), it)
            }
        }
        details.description?.let {
            SectionCard {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
fun InfoRow(icon: ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (onClick != null) {
            Icon(
                KerkidaIcons.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
