package app.kerkida.ui.fantasy

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.fantasy.FantasyLine
import app.kerkida.fantasy.FantasyLogic
import app.kerkida.fantasy.FantasySquad
import app.kerkida.fantasy.FplBootstrap
import app.kerkida.fantasy.FplClassicLeague
import app.kerkida.fantasy.FplEntry
import app.kerkida.fantasy.FplTeamMatcher
import app.kerkida.fantasy.LiveStanding
import app.kerkida.fantasy.Position
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.ErrorView
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DeadlineRow(val line: FantasyLine, val opponent: String?, val difficulty: Int?)

data class FantasyUi(
    val entryId: Int? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val entry: FplEntry? = null,
    val gameweek: Int? = null,
    val squad: FantasySquad? = null,
    val deadline: Instant? = null,
    val checks: List<DeadlineRow> = emptyList(),
    val table: List<LiveStanding>? = null,
    val tableName: String? = null,
    val tableLoading: Boolean = false,
)

class FantasyViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.appContext.getSharedPreferences("kerkida_fantasy", Context.MODE_PRIVATE)
    private val service = container.fantasyService
    private val _state = MutableStateFlow(FantasyUi(entryId = prefs.getInt(KEY_ENTRY, 0).takeIf { it > 0 }))
    val state: StateFlow<FantasyUi> = _state.asStateFlow()
    private var job: Job? = null

    init {
        _state.value.entryId?.let { load(it) }
    }

    fun connect(id: Int) {
        prefs.edit().putInt(KEY_ENTRY, id).apply()
        _state.value = FantasyUi(entryId = id)
        load(id)
    }

    fun disconnect() {
        job?.cancel()
        prefs.edit().remove(KEY_ENTRY).apply()
        container.alerts.saveFantasyPlayers(emptySet())
        _state.value = FantasyUi()
    }

    fun reload() = _state.value.entryId?.let { load(it) }

    /** FPL teams whose current match the person is keeping hidden. */
    private suspend fun hiddenTeams(bootstrap: FplBootstrap): Set<Int> {
        val provider = container.provider.value
        val matches: List<Match> = try {
            provider.fixtures(PREMIER_LEAGUE, null, null).data.matches
        } catch (_: DataException) {
            emptyList()
        }
        return matches.filter { container.shield.view(it).let { view -> view.isShielded && !view.showEvents } }
            .flatMap { listOfNotNull(FplTeamMatcher.find(it.home.name, bootstrap.teams), FplTeamMatcher.find(it.away.name, bootstrap.teams)) }
            .map { it.id }
            .toSet()
    }

    private fun load(id: Int) {
        job?.cancel()
        _state.update { it.copy(isLoading = true, error = null) }
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val bootstrap = service.bootstrap()
                val entry = service.entry(id)
                val gameweek = bootstrap.events.firstOrNull { it.isCurrent }?.id ?: entry.currentEvent
                    ?: throw DataException.NotFound()
                val fixtures = service.fixtures()
                val week = fixtures.filter { it.event == gameweek }
                val players = bootstrap.elements.associateBy { it.id }
                val live = service.live(gameweek, refresh = true).elements.associateBy { it.id }
                val hidden = hiddenTeams(bootstrap)
                val squad = FantasyLogic.build(service.picks(id, gameweek), players, live, week, hidden)
                val mine = (squad.starters + squad.bench)
                container.alerts.saveFantasyPlayers(mine.map { it.name }.toSet())
                prefs.edit().putInt("doubts", squad.starters.count { it.status != "a" }).apply()
                val next = bootstrap.events.firstOrNull { it.isNext }
                val teamNames = bootstrap.teams.associate { it.id to it.shortName }
                val nextWeek = fixtures.filter { it.event == next?.id }
                val checks = mine.map { line ->
                    val game = nextWeek.firstOrNull { it.teamHome == line.teamId || it.teamAway == line.teamId }
                    val isHome = game?.teamHome == line.teamId
                    DeadlineRow(
                        line = line,
                        opponent = game?.let { (teamNames[if (isHome) it.teamAway else it.teamHome] ?: "?") + if (isHome) " (Ε)" else " (Φ)" },
                        difficulty = game?.let { if (isHome) it.homeDifficulty else it.awayDifficulty },
                    )
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        entry = entry,
                        gameweek = gameweek,
                        squad = squad,
                        deadline = next?.deadline?.let { text -> runCatching { Instant.parse(text) }.getOrNull() },
                        checks = checks,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoading = false, error = error) }
            }
        }
    }

    fun openLeague(league: FplClassicLeague) {
        val gameweek = _state.value.gameweek ?: return
        _state.update { it.copy(table = null, tableName = league.name, tableLoading = true) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bootstrap = service.bootstrap()
                val rows = service.classicLeague(league.id).standings.results
                val week = service.fixtures().filter { it.event == gameweek }
                val players = bootstrap.elements.associateBy { it.id }
                val live = service.live(gameweek).elements.associateBy { it.id }
                val hidden = hiddenTeams(bootstrap)
                val points = HashMap<Int, Int>()
                // The free source is asked politely, so only the top of a league gets live scores.
                rows.take(MAX_LIVE_ROWS).forEach { row ->
                    try {
                        points[row.entry] = FantasyLogic.build(service.picks(row.entry, gameweek), players, live, week, hidden).total
                    } catch (_: DataException) {
                        // That manager keeps the published score.
                    }
                    _state.update { it.copy(table = FantasyLogic.liveTable(rows, points)) }
                }
                _state.update { it.copy(table = FantasyLogic.liveTable(rows, points), tableLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(tableLoading = false) }
            }
        }
    }

    fun closeLeague() = _state.update { it.copy(table = null, tableName = null, tableLoading = false) }

    private companion object {
        const val KEY_ENTRY = "entry"
        const val PREMIER_LEAGUE = "eng.1"
        const val MAX_LIVE_ROWS = 15
    }
}

@Composable
fun FantasyScreen(onBack: () -> Unit) {
    val viewModel = kerkidaViewModel(key = "fantasy") { FantasyViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.fantasy_title), onBack = onBack) {
            if (state.entryId != null) {
                TextButton(onClick = viewModel::disconnect) {
                    Text(stringResource(R.string.fantasy_change), color = KerkidaTheme.colors.onBrandBar)
                }
            }
        }
        when {
            state.entryId == null -> ConnectView(viewModel::connect)
            state.squad == null && state.error != null -> ErrorView(state.error!!, { viewModel.reload() })
            state.squad == null -> LoadingView()
            else -> FantasyContent(state, viewModel)
        }
    }
}

@Composable
private fun ConnectView(onConnect: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.fantasy_connect_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.fantasy_connect_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter(Char::isDigit).take(10) },
            label = { Text(stringResource(R.string.fantasy_id)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = text.isNotEmpty(), onClick = { text.toIntOrNull()?.let(onConnect) }) {
            Text(stringResource(R.string.fantasy_connect))
        }
    }
}

@Composable
private fun FantasyContent(state: FantasyUi, viewModel: FantasyViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val squad = state.squad ?: return
    Column(Modifier.fillMaxSize()) {
        Summary(state, squad)
        val labels = listOf(R.string.fantasy_tab_team, R.string.fantasy_tab_leagues, R.string.fantasy_tab_deadline)
        // A saved position from an older version can point past the last tab.
        val shown = tab.coerceIn(labels.indices)
        PrimaryScrollableTabRow(selectedTabIndex = shown, edgePadding = 8.dp) {
            labels.forEachIndexed { index, label ->
                Tab(selected = shown == index, onClick = { tab = index }, text = { Text(stringResource(label), maxLines = 1) })
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            when (shown) {
                0 -> item { TeamTab(squad) }
                1 -> item { LeaguesTab(state, viewModel) }
                else -> item { DeadlineTab(state) }
            }
        }
    }
}

@Composable
private fun Summary(state: FantasyUi, squad: FantasySquad) {
    val colors = KerkidaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.brandBar)
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.entry?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBrandBar,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.fantasy_gameweek, state.gameweek ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onBrandBar.copy(alpha = 0.8f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (squad.hiddenPlayers > 0) "${squad.total}+?" else squad.total.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBrandBar,
            )
            Text(stringResource(R.string.fantasy_points), style = MaterialTheme.typography.labelSmall, color = colors.onBrandBar.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun TeamTab(squad: FantasySquad) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (squad.hiddenPlayers > 0) {
            NoticeLine(stringResource(R.string.fantasy_hidden, squad.hiddenPlayers), KerkidaIcons.EyeOff)
        }
        if (squad.transferCost > 0) NoticeLine(stringResource(R.string.fantasy_hit, squad.transferCost))
        FantasyPitch(squad.starters)
        SectionTitle(stringResource(R.string.fantasy_bench))
        SectionCard {
            squad.bench.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BenchRow(line)
            }
        }
        Text(
            stringResource(R.string.fantasy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FantasyPitch(starters: List<FantasyLine>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E6B4C))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Position.entries.forEach { position ->
            val line = starters.filter { it.position == position }
            if (line.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    line.forEach { player -> PitchPlayerCard(player, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PitchPlayerCard(line: FantasyLine, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (line.status == "a") Color.White else Color(0xFFF2B134)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    line.scored?.toString() ?: "?",
                    color = Color(0xFF0B1F4B),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        val mark = when {
            line.isCaptain -> " (C)"
            line.isVice -> " (V)"
            line.subbedIn -> " ↑"
            else -> ""
        }
        Text(
            line.name + mark,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 1.dp, end = 1.dp),
        )
        if (line.provisionalBonus > 0) {
            Text("+${line.provisionalBonus} bonus", color = Color(0xFFB9F0DC), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BenchRow(line: FantasyLine) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.name + if (line.subbedOut) " ↓" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(line.points?.toString() ?: "?", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LeaguesTab(state: FantasyUi, viewModel: FantasyViewModel) {
    val table = state.table
    if (state.tableName != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.tableName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::closeLeague) { Text(stringResource(R.string.action_back)) }
            }
            if (state.tableLoading) NoticeLine(stringResource(R.string.fantasy_table_loading), KerkidaIcons.Clock)
            if (table != null) {
                SectionCard {
                    table.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StandingRowView(row, row.row.entry == state.entryId)
                    }
                }
            }
        }
        return
    }
    val leagues = state.entry?.leagues?.classic.orEmpty().filter { it.type == "x" }
    if (leagues.isEmpty()) {
        NoticeLine(stringResource(R.string.fantasy_no_leagues))
        return
    }
    SectionCard {
        leagues.forEachIndexed { index, league ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                league.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { viewModel.openLeague(league) }
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun StandingRowView(row: LiveStanding, isMine: Boolean) {
    val move = row.row.rank - row.liveRank
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isMine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.liveRank.toString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
        Text(
            when {
                move > 0 -> "▲$move"
                move < 0 -> "▼${-move}"
                else -> "–"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                move > 0 -> KerkidaTheme.colors.win
                move < 0 -> KerkidaTheme.colors.loss
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(row.row.entryName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.row.playerName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(row.gameweek.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 30.dp))
        Spacer(Modifier.width(8.dp))
        Text(row.liveTotal.toString(), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DeadlineTab(state: FantasyUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.deadline?.let {
            val text = it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d/M, HH:mm"))
            NoticeLine(stringResource(R.string.fantasy_deadline, text), KerkidaIcons.Clock)
            app.kerkida.assistant.AssistantLogic.countdown(it, Instant.now())?.let { (days, hours) ->
                NoticeLine(stringResource(R.string.fantasy_countdown, days, hours), KerkidaIcons.Clock)
            }
        }
        val doubts = state.checks.filter { it.line.status != "a" }
        SectionTitle(stringResource(R.string.fantasy_doubts))
        SectionCard {
            if (doubts.isEmpty()) {
                Text(stringResource(R.string.fantasy_no_doubts), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            doubts.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(12.dp)) {
                    Text(row.line.name, style = MaterialTheme.typography.bodyMedium)
                    if (row.line.news.isNotBlank()) {
                        Text(row.line.news, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.fantasy_fixtures))
        SectionCard {
            state.checks.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.line.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(row.opponent ?: stringResource(R.string.fantasy_blank), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    DifficultyDot(row.difficulty)
                }
            }
        }
    }
}

@Composable
private fun DifficultyDot(value: Int?) {
    val color = when (value) {
        1, 2 -> KerkidaTheme.colors.win
        3 -> KerkidaTheme.colors.draw
        4, 5 -> KerkidaTheme.colors.loss
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}
