package app.kerkida.ui.league

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.CatalogLeague
import app.kerkida.data.Favourite
import app.kerkida.data.FavouriteKind
import app.kerkida.data.LeagueGrouping
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Sport
import app.kerkida.data.model.Standings
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.FavouriteButton
import app.kerkida.ui.common.FreshnessFooter
import app.kerkida.ui.common.Loadable
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MatchRow
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.Resource
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.StandingsTable
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LeagueViewModel(private val container: AppContainer, private val leagueId: String) : ViewModel() {
    private val provider = container.provider.value
    private val providerId = provider.info.id
    val favourites: StateFlow<List<Favourite>> = container.favourites.favourites(providerId)

    private val round = MutableStateFlow<Int?>(null)

    val details = Resource(viewModelScope) { refresh -> provider.league(leagueId, refresh) }
    val standings = Resource(viewModelScope) { refresh -> provider.standings(leagueId, season(), refresh) }
    val fixtures = Resource(viewModelScope) { refresh -> provider.fixtures(leagueId, season(), round.value, refresh) }

    private fun season(): String? = (details.state.value as? Loadable.Ready<LeagueDetails>)?.value?.currentSeason

    init {
        details.load()
        standings.load()
        fixtures.load()
    }

    fun refresh() {
        details.load(refresh = true)
        standings.load(refresh = true)
        fixtures.load(refresh = true)
    }

    fun retryStandings() = standings.load()

    fun retryFixtures() = fixtures.load()

    fun changeRound(target: Int) {
        round.value = target
        fixtures.load()
    }

    fun toggleFavourite(league: LeagueRef) {
        container.favourites.toggle(
            providerId,
            Favourite(FavouriteKind.LEAGUE.name, league.id, league.name, league.country, league.badgeUrl),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeagueScreen(
    leagueId: String,
    onBack: () -> Unit,
    onMatch: (String) -> Unit,
    onTeam: (String) -> Unit,
) {
    val viewModel = kerkidaViewModel(key = "league-$leagueId") { LeagueViewModel(it, leagueId) }
    val details by viewModel.details.state.collectAsStateWithLifecycle()
    val standings by viewModel.standings.state.collectAsStateWithLifecycle()
    val fixtures by viewModel.fixtures.state.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val league = (details as? Loadable.Ready<LeagueDetails>)?.value
    val refreshing = (details as? Loadable.Ready<*>)?.isRefreshing == true ||
        (standings as? Loadable.Ready<*>)?.isRefreshing == true ||
        (fixtures as? Loadable.Ready<*>)?.isRefreshing == true

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = league?.league?.name ?: stringResource(R.string.league_title),
            onBack = onBack,
        ) {
            league?.league?.let { ref ->
                FavouriteButton(
                    isFavourite = favourites.any { it.favouriteKind == FavouriteKind.LEAGUE && it.id == ref.id },
                    onToggle = { viewModel.toggleFavourite(ref) },
                    tint = KerkidaTheme.colors.onBrandBar,
                )
            }
        }
        league?.let { LeagueHero(it) }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.league_tab_table, R.string.league_tab_fixtures).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (tab == 0) {
                TableTab(standings, onTeam, viewModel::retryStandings)
            } else {
                FixturesTab(fixtures, onMatch, viewModel::changeRound, viewModel::retryFixtures)
            }
        }
    }
}

@Composable
private fun LeagueHero(details: LeagueDetails) {
    val colors = KerkidaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.brandBar)
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(details.league.badgeUrl ?: app.kerkida.ui.shield.appContainer().catalog.find(details.league.id)?.logo, details.league.name, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                details.league.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBrandBar,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(details.league.country?.let { app.kerkida.ui.common.placeName(it) }, details.currentSeason).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onBrandBar.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun TableTab(state: Loadable<Standings>, onTeam: (String) -> Unit, onRetry: () -> Unit) {
    when (state) {
        Loadable.Loading -> LoadingView()
        is Loadable.Failed -> ErrorView(state.error, onRetry)
        is Loadable.Ready -> {
            if (state.value.rows.isEmpty()) {
                MessageView(
                    KerkidaIcons.Leagues,
                    stringResource(R.string.table_empty_title),
                    stringResource(R.string.table_empty_body),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { StandingsTable(state.value, onTeam) }
                    item { FreshnessFooter(state.fetchedAt, state.isStale) }
                }
            }
        }
    }
}

@Composable
private fun FixturesTab(
    state: Loadable<LeagueFixtures>,
    onMatch: (String) -> Unit,
    onRound: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        Loadable.Loading -> LoadingView()
        is Loadable.Failed -> ErrorView(state.error, onRetry)
        is Loadable.Ready -> {
            val data = state.value
            Column(Modifier.fillMaxSize()) {
                data.round?.let { round ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(enabled = data.hasPreviousRound, onClick = { onRound(round - 1) }) {
                            Icon(KerkidaIcons.ChevronLeft, stringResource(R.string.action_previous_round))
                        }
                        Text(
                            stringResource(R.string.round_label, round),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(enabled = data.hasNextRound, onClick = { onRound(round + 1) }) {
                            Icon(KerkidaIcons.ChevronRight, stringResource(R.string.action_next_round))
                        }
                    }
                }
                if (data.matches.isEmpty()) {
                    MessageView(
                        KerkidaIcons.Calendar,
                        stringResource(R.string.fixtures_empty_title),
                        stringResource(R.string.fixtures_empty_body),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        item {
                            SectionCard {
                                data.matches.forEachIndexed { index, match ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    MatchRow(match, onClick = { onMatch(match.id) }, showDate = true)
                                }
                            }
                        }
                        item { FreshnessFooter(state.fetchedAt, state.isStale) }
                    }
                }
            }
        }
    }
}

/** The list of main leagues, shown on the Leagues tab. */
class LeaguesViewModel(container: AppContainer) : ViewModel() {
    private val provider = container.provider.value
    val leagues = Resource(viewModelScope) { _ -> provider.featuredLeagues() }

    init {
        leagues.load()
    }
}

@Composable
fun LeaguesScreen(onLeague: (String) -> Unit) {
    val viewModel = kerkidaViewModel { LeaguesViewModel(it) }
    val state by viewModel.leagues.state.collectAsStateWithLifecycle()
    val catalog = app.kerkida.ui.shield.appContainer().catalog
    val locale = app.kerkida.ui.common.currentLocale()
    val international = stringResource(R.string.leagues_international)
    var sportName by rememberSaveable { mutableStateOf(Sport.FOOTBALL.name) }
    Column(Modifier.fillMaxSize()) {
        app.kerkida.ui.common.BrandHeader(title = stringResource(R.string.nav_leagues))
        when (val current = state) {
            Loadable.Loading -> LoadingView()
            is Loadable.Failed -> ErrorView(current.error, { viewModel.leagues.load() })
            is Loadable.Ready -> {
                val refs = current.value
                val groups = remember(refs, locale, international) {
                    val entries = refs.distinctBy { it.id }.map { ref ->
                        val known = catalog.find(ref.id)
                        CatalogLeague(ref.id, ref.name, known?.sportName ?: ref.sport.name, known?.country, ref.badgeUrl)
                    }
                    LeagueGrouping.group(entries, locale, international) { app.kerkida.ui.settings.countryName(it, locale) }
                }
                val byId = remember(refs) { refs.associateBy { it.id } }
                val shown = groups.firstOrNull { it.sport.name == sportName } ?: groups.firstOrNull()
                app.kerkida.ui.common.SportStrip(groups.map { it.sport }, shown?.sport ?: Sport.FOOTBALL) { sportName = it.name }
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            stringResource(R.string.leagues_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    shown?.countries?.forEach { country ->
                        item(key = "country-${country.key}") {
                            Text(
                                country.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = KerkidaTheme.colors.accentText,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                            )
                        }
                        items(country.leagues, key = { it.id }) { entry ->
                            val league = byId.getValue(entry.id)
                            LeagueListRow(league, showCountry = false) { onLeague(league.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LeagueListRow(league: LeagueRef, showCountry: Boolean = true, onClick: () -> Unit) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crest(league.badgeUrl ?: app.kerkida.ui.shield.appContainer().catalog.find(league.id)?.logo, league.name, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                league.country?.takeIf { showCountry }?.let { app.kerkida.ui.common.placeName(it) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(KerkidaIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
