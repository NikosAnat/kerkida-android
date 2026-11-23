package app.kerkida.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import kotlinx.coroutines.launch
import app.kerkida.data.AppContainer
import app.kerkida.data.Favourite
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.Match
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.clubName
import app.kerkida.ui.common.placeName
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.FavouriteButton
import app.kerkida.ui.common.FreshnessFooter
import app.kerkida.ui.common.Loadable
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MatchRow
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.Resource
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.match.InfoRow
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.flow.StateFlow

class TeamViewModel(private val container: AppContainer, teamId: String) : ViewModel() {
    private val provider = container.provider.value
    private val providerId = provider.info.id
    val isDemo = provider.info.isDemo
    val limitedHistory = provider.info.id == app.kerkida.data.thesportsdb.TheSportsDbProvider.ID
    val favourites: StateFlow<List<Favourite>> = container.favourites.favourites(providerId)
    val team = Resource(viewModelScope) { refresh -> provider.team(teamId, refresh) }
    val matches = Resource(viewModelScope) { refresh -> provider.teamMatches(teamId, refresh) }

    init {
        team.load()
        matches.load()
    }

    fun refresh() {
        team.load(refresh = true)
        matches.load(refresh = true)
    }

    fun toggleFavourite(team: Team) {
        container.favourites.toggle(
            providerId,
            Favourite(FavouriteKind.TEAM.name, team.id, team.name, team.league?.name ?: team.country, team.badgeUrl),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TeamScreen(
    teamId: String,
    onBack: () -> Unit,
    onMatch: (String) -> Unit,
    onLeague: (String) -> Unit,
    onTeam: (String) -> Unit = {},
) {
    val viewModel = kerkidaViewModel(key = "team-$teamId") { TeamViewModel(it, teamId) }
    val teamState by viewModel.team.state.collectAsStateWithLifecycle()
    val matchesState by viewModel.matches.state.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val team = (teamState as? Loadable.Ready<Team>)?.value
    val clubLoad by rememberClubProfile(team)
    val profile = clubLoad.profile
    val isLoadingTeam = teamState is Loadable.Loading || (team != null && (matchesState is Loadable.Loading || clubLoad.isLoading))
    val catalog = app.kerkida.ui.shield.appContainer().catalog
    val played = (matchesState as? Loadable.Ready<TeamMatches>)?.value
    val competitions = remember(team, played) {
        app.kerkida.data.club.TeamCompetitions.of(team?.league, played?.let { it.recent + it.upcoming }.orEmpty(), catalog::find)
    }
    val tabs = clubTabs(profile, competitions.isNotEmpty())
    var tabName by rememberSaveable(teamId) { mutableStateOf(ClubTab.MATCHES.name) }
    val tab = tabs.firstOrNull { it.name == tabName } ?: ClubTab.MATCHES

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = team?.name?.let { clubName(it) } ?: stringResource(R.string.team_title), onBack = onBack) {
            team?.let { current ->
                FavouriteButton(
                    isFavourite = favourites.any { it.favouriteKind == FavouriteKind.TEAM && it.id == current.id },
                    onToggle = { viewModel.toggleFavourite(current) },
                    tint = KerkidaTheme.colors.onBrandBar,
                )
                TeamCalendarButton(current.id)
            }
        }
        TeamLoadingBar(isLoadingTeam)
        when (val current = teamState) {
            Loadable.Loading -> LoadingView(rows = 4)
            is Loadable.Failed -> ErrorView(current.error, { viewModel.team.load(); viewModel.matches.load() })
            is Loadable.Ready -> PullToRefreshBox(
                isRefreshing = current.isRefreshing || (matchesState as? Loadable.Ready<*>)?.isRefreshing == true,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { TeamHero(current.value) }
                    stickyHeader { ClubTabRow(tabs, tab) { tabName = it.name } }
                    item {
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val details = profile
                            when (tab) {
                                ClubTab.MATCHES -> TeamMatchesSection(matchesState, onMatch, viewModel.isDemo, viewModel.limitedHistory) { viewModel.matches.load() }
                                ClubTab.TABLE -> ClubStandings(current.value.id, competitions, onTeam)
                                ClubTab.ABOUT -> {
                                    TeamInfo(current.value, onLeague)
                                    ClubAbout(details)
                                }
                                ClubTab.HISTORY -> details?.let { ClubHistory(it) }
                                ClubTab.HONOURS -> details?.let { ClubHonours(it) }
                                ClubTab.GROUND -> details?.ground?.let { ClubPlaceTab(it, details.articleUrl) }
                                ClubTab.CITY -> details?.city?.let { ClubPlaceTab(it, details.articleUrl) }
                            }
                            FreshnessFooter(current.fetchedAt, current.isStale)
                        }
                    }
                }
            }
        }
    }
}

/** Puts the team's season in the phone's calendar and keeps it updated when kick-offs move. */
@Composable
private fun TeamCalendarButton(teamId: String) {
    val calendar = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.kerkida.KerkidaApp).container.teamCalendar
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var isOn by androidx.compose.runtime.remember(teamId) {
        androidx.compose.runtime.mutableStateOf(teamId in calendar.teams() && calendar.hasPermission())
    }
    val enable = {
        calendar.setTeam(teamId, true)
        isOn = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { calendar.sync() }
        Unit
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.all { it }) enable() }
    androidx.compose.material3.IconButton(onClick = {
        when {
            isOn -> {
                calendar.setTeam(teamId, false)
                isOn = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { calendar.sync() }
            }
            calendar.hasPermission() -> enable()
            else -> permission.launch(
                arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
            )
        }
    }) {
        androidx.compose.material3.Icon(
            KerkidaIcons.Calendar,
            contentDescription = stringResource(if (isOn) R.string.calendar_off else R.string.calendar_on),
            tint = if (isOn) KerkidaTheme.colors.accent else KerkidaTheme.colors.onBrandBar,
        )
    }
}

@Composable
private fun TeamHero(team: Team) {
    val colors = KerkidaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.brandBar)
            .padding(start = 16.dp, end = 16.dp, bottom = 18.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crest(team.badgeUrl, team.name, 72.dp)
        Spacer(Modifier.height(8.dp))
        Text(clubName(team.name), style = MaterialTheme.typography.titleLarge, color = colors.onBrandBar, textAlign = TextAlign.Center)
        val subtitle = listOfNotNull(team.league?.name, team.country?.let { placeName(it) }).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onBrandBar.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TeamMatchesSection(
    state: Loadable<TeamMatches>,
    onMatch: (String) -> Unit,
    isDemo: Boolean,
    limitedHistory: Boolean,
    onRetry: () -> Unit,
) {
    when (state) {
        Loadable.Loading -> LoadingView(rows = 2)
        is Loadable.Failed -> ErrorView(state.error, onRetry, scrollable = false)
        is Loadable.Ready -> {
            val data = state.value
            if (data.upcoming.isEmpty() && data.recent.isEmpty()) {
                MessageView(
                    KerkidaIcons.Calendar,
                    stringResource(R.string.team_matches_empty_title),
                    stringResource(R.string.team_matches_empty_body),
                    scrollable = false,
                )
            } else {
                if (data.upcoming.isNotEmpty()) MatchGroup(stringResource(R.string.team_next), data.upcoming, onMatch)
                if (data.recent.isNotEmpty()) MatchGroup(stringResource(R.string.team_recent), data.recent, onMatch)
                if (!isDemo && limitedHistory) NoticeLine(stringResource(R.string.team_matches_limited))
            }
        }
    }
}

@Composable
private fun MatchGroup(title: String, matches: List<Match>, onMatch: (String) -> Unit) {
    Column {
        SectionTitle(title)
        SectionCard {
            matches.forEachIndexed { index, match ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MatchRow(match, onClick = { onMatch(match.id) }, showDate = true)
            }
        }
    }
}

@Composable
private fun TeamInfo(team: Team, onLeague: (String) -> Unit) {
    var expanded by remember(team.id) { mutableStateOf(false) }
    Column {
        SectionTitle(stringResource(R.string.team_info))
        SectionCard {
            team.league?.let { league ->
                InfoRow(KerkidaIcons.Leagues, stringResource(R.string.team_league), league.name, onClick = { onLeague(league.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            team.stadium?.let {
                InfoRow(KerkidaIcons.Shield, stringResource(R.string.team_stadium), it)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            team.location?.let {
                InfoRow(KerkidaIcons.Pin, stringResource(R.string.team_location), it)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            team.foundedYear?.let {
                InfoRow(KerkidaIcons.Clock, stringResource(R.string.team_founded), it.toString())
            }
        }
        team.description?.let { description ->
            Spacer(Modifier.height(10.dp))
            SectionCard {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.action_show_less else R.string.action_show_more))
                    }
                }
            }
        }
    }
}
