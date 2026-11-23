package app.kerkida.ui.favourites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.DataException
import app.kerkida.data.Favourite
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.preview.FormResult
import app.kerkida.preview.PreviewBuilder
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.localTimeText
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.shield.rememberShieldedView
import app.kerkida.ui.theme.KerkidaTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class TeamDigest(val last: Match?, val next: Match?, val scorers: List<String>)

private val Goals = setOf(MatchEventType.GOAL, MatchEventType.PENALTY)

/** The team's own scorers, for a one-line summary after a match. */
internal fun scorersFor(events: List<MatchEvent>, side: Side): List<String> = events
    .filter { it.type in Goals && it.side == side }
    .mapNotNull { event -> event.player?.substringAfterLast(' ')?.let { name -> event.minute?.let { "$name $it′" } ?: name } }

/** One short card per followed team: the latest result and what comes next. */
@Composable
fun MyDashboard(teams: List<Favourite>, onMatch: (String) -> Unit) {
    if (teams.isEmpty()) return
    Column {
        SectionTitle(stringResource(R.string.dashboard_title))
        SectionCard {
            teams.forEachIndexed { index, team ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DigestRow(team, onMatch)
            }
        }
    }
}

@Composable
private fun DigestRow(team: Favourite, onMatch: (String) -> Unit) {
    val container = appContainer()
    val provider by container.provider.collectAsStateWithLifecycle()
    val digest by produceState<TeamDigest?>(null, team.id, provider.info.id) {
        value = try {
            val games = provider.teamMatches(team.id).data
            val last = games.recent.firstOrNull { it.status == MatchStatus.FINISHED }
            val events = last?.let {
                try {
                    provider.matchDetails(it.id).data.events
                } catch (_: DataException) {
                    emptyList()
                }
            }.orEmpty()
            val side = if (last?.home?.id == team.id) Side.HOME else Side.AWAY
            TeamDigest(last, games.upcoming.firstOrNull(), scorersFor(events, side))
        } catch (_: DataException) {
            null
        }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
        Crest(team.badgeUrl, team.title, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(team.title, style = MaterialTheme.typography.titleSmall)
            digest?.last?.let { LastLine(it, team.id, digest?.scorers.orEmpty(), onMatch) }
            digest?.next?.let { NextLine(it, team.id, onMatch) }
        }
    }
}

@Composable
private fun LastLine(match: Match, teamId: String, scorers: List<String>, onMatch: (String) -> Unit) {
    val view = rememberShieldedView(match)
    val colors = KerkidaTheme.colors
    val opponent = if (match.home.id == teamId) match.away.name else match.home.name
    val shown = view.match
    val result = if (view.showScore) PreviewBuilder.resultFor(shown, teamId) else null
    val text = when {
        result == null -> stringResource(R.string.dashboard_hidden, opponent)
        else -> {
            val own = if (match.home.id == teamId) shown.homeScore else shown.awayScore
            val other = if (match.home.id == teamId) shown.awayScore else shown.homeScore
            val res = when (result) {
                FormResult.WIN -> R.string.dashboard_win
                FormResult.DRAW -> R.string.dashboard_draw
                FormResult.LOSS -> R.string.dashboard_loss
            }
            stringResource(res, own ?: 0, other ?: 0, opponent)
        }
    }
    val color = when (result) {
        FormResult.WIN -> colors.win
        FormResult.LOSS -> colors.loss
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(Modifier.clickable(role = Role.Button) { onMatch(match.id) }.padding(top = 3.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (view.showEvents && scorers.isNotEmpty()) {
            Text(
                stringResource(R.string.dashboard_scorers, scorers.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NextLine(match: Match, teamId: String, onMatch: (String) -> Unit) {
    val opponent = if (match.home.id == teamId) match.away.name else match.home.name
    val date = match.kickoff?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("d/M"))
    Text(
        stringResource(R.string.dashboard_next, opponent, listOfNotNull(date, match.kickoff?.localTimeText()).joinToString(" ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(role = Role.Button) { onMatch(match.id) }.padding(top = 3.dp),
    )
}
