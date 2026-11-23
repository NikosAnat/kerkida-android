package app.kerkida.ui.search

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.Sourced
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Team
import app.kerkida.ui.common.BrandHeader
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.league.LeagueListRow
import app.kerkida.ui.theme.KerkidaIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SearchState {
    data object Idle : SearchState
    data object TooShort : SearchState
    data object Loading : SearchState
    data class Failed(val error: Throwable) : SearchState
    data class Done(val query: String, val results: SearchResults) : SearchState
}

class SearchViewModel(container: AppContainer) : ViewModel() {
    private val provider = container.provider.value
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(text: String) {
        _query.value = text
        schedule(DEBOUNCE_MS)
    }

    fun retry() = schedule(0)

    private fun schedule(wait: Long) {
        job?.cancel()
        val text = _query.value.trim()
        when {
            text.isEmpty() -> _state.value = SearchState.Idle
            text.length < MIN_LENGTH -> _state.value = SearchState.TooShort
            else -> {
                _state.value = SearchState.Loading
                job = viewModelScope.launch {
                    delay(wait)
                    try {
                        val result: Sourced<SearchResults> = withContext(Dispatchers.Default) { provider.search(text) }
                        _state.value = SearchState.Done(text, result.data)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _state.value = SearchState.Failed(error)
                    }
                }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 650L
        const val MIN_LENGTH = 3
    }
}

@Composable
fun SearchScreen(onTeam: (String) -> Unit, onLeague: (String) -> Unit) {
    val viewModel = kerkidaViewModel { SearchViewModel(it) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        BrandHeader(title = stringResource(R.string.nav_search))
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(KerkidaIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQuery("") }) {
                        Icon(KerkidaIcons.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
        when (val current = state) {
            SearchState.Idle -> MessageView(
                KerkidaIcons.Search,
                stringResource(R.string.search_idle_title),
                stringResource(R.string.search_idle_body),
            )
            SearchState.TooShort -> MessageView(
                KerkidaIcons.Search,
                stringResource(R.string.search_short_title),
                stringResource(R.string.search_short_body),
            )
            SearchState.Loading -> LoadingView(rows = 3)
            is SearchState.Failed -> ErrorView(current.error, viewModel::retry)
            is SearchState.Done -> if (current.results.isEmpty) {
                MessageView(
                    KerkidaIcons.Search,
                    stringResource(R.string.search_empty_title),
                    stringResource(R.string.search_empty_body, current.query),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (current.results.teams.isNotEmpty()) {
                        item(key = "teams-title") { SectionTitle(stringResource(R.string.search_teams)) }
                        items(current.results.teams, key = { "team-${it.id}" }) { team ->
                            TeamResult(team) { onTeam(team.id) }
                        }
                    }
                    if (current.results.leagues.isNotEmpty()) {
                        item(key = "leagues-title") { SectionTitle(stringResource(R.string.search_leagues)) }
                        items(current.results.leagues, key = { "league-${it.id}" }) { league ->
                            LeagueListRow(league) { onLeague(league.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamResult(team: Team, onClick: () -> Unit) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crest(team.badgeUrl, team.name, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.kerkida.ui.common.clubName(team.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(team.league?.name, team.country?.let { app.kerkida.ui.common.placeName(it) }).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(KerkidaIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
