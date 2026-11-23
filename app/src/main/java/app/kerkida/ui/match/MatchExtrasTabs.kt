package app.kerkida.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kerkida.R
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchExtras
import app.kerkida.data.model.TeamLineup
import app.kerkida.data.model.TeamRef
import app.kerkida.fantasy.MatchRatings
import app.kerkida.fantasy.PlayerRating
import app.kerkida.fantasy.Position
import app.kerkida.fantasy.RatingAction
import app.kerkida.fantasy.RatingSource
import app.kerkida.fantasy.SquadPlayer
import app.kerkida.preview.ElevenSource
import app.kerkida.preview.FormResult
import app.kerkida.preview.InsightKind
import app.kerkida.preview.MatchPreview
import app.kerkida.preview.PreviewInsight
import app.kerkida.preview.TeamPreview
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import java.util.Locale

private fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)

@Composable
private fun ratingColor(value: Double): Color {
    val colors = KerkidaTheme.colors
    return when {
        value >= 7.0 -> Color(0xFF00B37A)
        value >= 6.0 -> Color(0xFF7FE0BF)
        value >= 5.0 -> colors.marigold
        else -> Color(0xFFFF8A73)
    }
}

@Composable
private fun RatingBadge(value: Double) {
    val background = ratingColor(value)
    Box(
        Modifier
            .widthIn(min = 42.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            oneDecimal(value),
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF0B1F4B),
        )
    }
}

private fun pitchTeam(team: TeamRef, lineup: TeamLineup?, ratings: List<PlayerRating>, showPhotos: Boolean): PitchTeam? {
    lineup ?: return null
    val starters = FormationLayout.fromLineup(lineup.players, ratings, showPhotos)
    if (starters.isEmpty()) return null
    return PitchTeam(team, lineup.formation, FormationLayout.lines(starters, lineup.formation))
}

@Composable
fun PlayersTab(match: Match, extras: MatchExtras?, ratings: MatchRatings?, isLoading: Boolean, isHidden: Boolean) {
    var selected by remember { mutableStateOf<PlayerRating?>(null) }
    val showPhotos = app.kerkida.ui.shield.appContainer().flags.isOn(app.kerkida.features.Feature.PLAYER_PHOTOS)
    val home = remember(match.home, extras, ratings, showPhotos) { pitchTeam(match.home, extras?.home, ratings?.home.orEmpty(), showPhotos) }
    val away = remember(match.away, extras, ratings, showPhotos) { pitchTeam(match.away, extras?.away, ratings?.away.orEmpty(), showPhotos) }
    val photos = remember(extras, showPhotos) {
        if (!showPhotos) {
            emptyMap()
        } else {
            listOfNotNull(extras?.home, extras?.away).flatMap { it.players }
                .groupBy { FormationLayout.surnameKey(it.name) }
                // Two players with one surname could swap faces, so neither is matched by it.
                .filterValues { it.size == 1 }
                .mapNotNull { (surname, players) -> players.single().photoUrl?.let { surname to it } }
                .toMap()
        }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MatchPitch(home, away, onPlayer = { selected = it })
        when {
            isHidden -> MessageView(
                KerkidaIcons.EyeOff,
                stringResource(R.string.players_hidden_title),
                stringResource(R.string.players_hidden_body),
                scrollable = false,
            )
            ratings == null && isLoading -> MessageView(
                KerkidaIcons.Clock,
                stringResource(R.string.loading),
                stringResource(R.string.players_loading_body),
                scrollable = false,
            )
            ratings == null -> MessageView(
                KerkidaIcons.Info,
                stringResource(R.string.players_empty_title),
                stringResource(R.string.players_empty_body),
                scrollable = false,
            )
            else -> {
                NoticeLine(
                    stringResource(
                        if (ratings.source == RatingSource.FANTASY) R.string.ratings_note_fantasy else R.string.ratings_note_stats,
                    ),
                )
                if (!ratings.isFinal) NoticeLine(stringResource(R.string.ratings_live), KerkidaIcons.Clock)
                TeamRatings(match.home, ratings.home, photos, showPhotos) { selected = it }
                TeamRatings(match.away, ratings.away, photos, showPhotos) { selected = it }
            }
        }
    }
    selected?.let { player ->
        val team = if (ratings?.home?.any { it.id == player.id } == true) match.home else match.away
        RatingDialog(player, team) { selected = null }
    }
}

@Composable
private fun TeamRatings(team: TeamRef, players: List<PlayerRating>, photos: Map<String, String>, showPhotos: Boolean, onPlayer: (PlayerRating) -> Unit) {
    if (players.isEmpty()) return
    Row(
        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(team.badgeUrl, team.name, 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(app.kerkida.ui.common.clubName(team.name), style = MaterialTheme.typography.titleMedium)
    }
    SectionCard {
        players.forEachIndexed { index, player ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPlayer(player) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.kerkida.ui.common.PlayerPhoto((player.photoUrl ?: photos[FormationLayout.surnameKey(player.name)]).takeIf { showPhotos }, player.name, 34.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    positionLabel(player.position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(player.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.player_team_minutes, team.name, player.minutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RatingBadge(player.breakdown.rating)
            }
        }
    }
}

@Composable
private fun RatingDialog(player: PlayerRating, team: TeamRef, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(team.badgeUrl, team.name, 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(player.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${team.name} · ${positionLabel(player.position)} · ${stringResource(R.string.player_minutes, player.minutes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RatingBadge(player.breakdown.rating)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BreakdownLine(stringResource(R.string.rating_base), null, oneDecimal(player.breakdown.base), MaterialTheme.colorScheme.onSurface)
                player.breakdown.contributions.forEach { line ->
                    val sign = if (line.delta > 0) "+" else ""
                    BreakdownLine(
                        actionLabel(line.action),
                        if (line.value % 1.0 == 0.0) line.value.toInt().toString() else String.format(Locale.US, "%.2f", line.value),
                        sign + String.format(Locale.US, "%.2f", line.delta),
                        if (line.delta >= 0) KerkidaTheme.colors.win else KerkidaTheme.colors.loss,
                    )
                }
                if (player.breakdown.contributions.isEmpty()) {
                    Text(stringResource(R.string.rating_no_actions), style = MaterialTheme.typography.bodySmall)
                }
                if (player.breakdown.isSmallSample) {
                    NoticeLine(stringResource(R.string.rating_small_sample))
                }
                Text(
                    stringResource(R.string.rating_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
private fun BreakdownLine(label: String, count: String?, delta: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        count?.let {
            Text(
                "×$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
        Text(
            delta,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

@Composable
private fun positionLabel(position: Position) = stringResource(
    when (position) {
        Position.GKP -> R.string.position_gk
        Position.DEF -> R.string.position_def
        Position.MID -> R.string.position_mid
        Position.FWD -> R.string.position_fwd
    },
)

@Composable
private fun actionLabel(action: RatingAction) = stringResource(
    when (action) {
        RatingAction.GOALS -> R.string.action_goals
        RatingAction.ASSISTS -> R.string.action_assists
        RatingAction.EXPECTED_GOALS -> R.string.action_xg
        RatingAction.EXPECTED_ASSISTS -> R.string.action_xa
        RatingAction.SHOTS_ON_TARGET -> R.string.action_shots_on
        RatingAction.SHOTS_OFF_TARGET -> R.string.action_shots_off
        RatingAction.FOULS_WON -> R.string.action_fouls_won
        RatingAction.FOULS_COMMITTED -> R.string.action_fouls
        RatingAction.TACKLES -> R.string.action_tackles
        RatingAction.DEFENSIVE_ACTIONS -> R.string.action_defensive
        RatingAction.RECOVERIES -> R.string.action_recoveries
        RatingAction.SAVES -> R.string.action_saves
        RatingAction.PENALTIES_SAVED -> R.string.action_pen_saved
        RatingAction.CLEAN_SHEET -> R.string.action_clean_sheet
        RatingAction.GOALS_CONCEDED -> R.string.action_conceded
        RatingAction.YELLOW_CARD -> R.string.action_yellow
        RatingAction.RED_CARD -> R.string.action_red
        RatingAction.OWN_GOALS -> R.string.action_own_goal
        RatingAction.PENALTIES_MISSED -> R.string.action_pen_missed
    },
)

@Composable
fun PreviewTab(match: Match, preview: MatchPreview?, isLoading: Boolean) {
    if (preview == null) {
        MessageView(
            if (isLoading) KerkidaIcons.Clock else KerkidaIcons.Info,
            stringResource(if (isLoading) R.string.loading else R.string.preview_empty_title),
            stringResource(if (isLoading) R.string.preview_loading_body else R.string.preview_empty_body),
            scrollable = false,
        )
        return
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preview.insights.isNotEmpty()) {
            SectionTitle(stringResource(R.string.preview_points))
            SectionCard {
                preview.insights.forEachIndexed { index, insight ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        insightText(insight),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        SectionTitle(stringResource(R.string.preview_form))
        SectionCard {
            FormRow(match.home.name, preview.home.form)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FormRow(match.away.name, preview.away.form)
        }
        SectionTitle(stringResource(R.string.preview_absences))
        SectionCard {
            if (!preview.hasSquadData) {
                Text(
                    stringResource(R.string.preview_absences_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                AbsenceList(match.home.name, preview.home.absences)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AbsenceList(match.away.name, preview.away.absences)
            }
        }
        PreviewPitch(match, preview)
    }
}

@Composable
private fun FormRow(team: String, form: List<FormResult>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(team, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (form.isEmpty()) {
            Text("–", style = MaterialTheme.typography.bodySmall)
        }
        // Oldest on the left, so the latest result is always next to the edge.
        form.reversed().forEach { result ->
            val colors = KerkidaTheme.colors
            val (label, color) = when (result) {
                FormResult.WIN -> stringResource(R.string.form_win) to colors.win
                FormResult.DRAW -> stringResource(R.string.form_draw) to colors.draw
                FormResult.LOSS -> stringResource(R.string.form_loss) to colors.loss
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AbsenceList(team: String, players: List<SquadPlayer>) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(team, style = MaterialTheme.typography.titleSmall)
        if (players.isEmpty()) {
            Text(
                stringResource(R.string.preview_no_absences),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        players.take(MAX_ABSENCES).forEach { player ->
            Text(
                listOf(player.name, player.news).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun previewTeam(team: TeamRef, preview: TeamPreview): PitchTeam? {
    if (preview.probableEleven.isEmpty()) return null
    val players = FormationLayout.fromSquad(preview.probableEleven)
    return PitchTeam(team, preview.formation, FormationLayout.lines(players, preview.formation))
}

@Composable
private fun elevenCaption(team: String, source: ElevenSource): String? {
    val res = when (source) {
        ElevenSource.OFFICIAL -> R.string.eleven_official
        ElevenSource.LAST_MATCH -> R.string.eleven_last_match
        ElevenSource.SEASON_STARTS -> R.string.eleven_season
        ElevenSource.NONE -> return null
    }
    return "$team: ${stringResource(res)}"
}

@Composable
private fun PreviewPitch(match: Match, preview: MatchPreview) {
    val home = remember(preview) { previewTeam(match.home, preview.home) }
    val away = remember(preview) { previewTeam(match.away, preview.away) }
    if (home == null && away == null) return
    SectionTitle(stringResource(R.string.preview_eleven))
    if (home != null) elevenCaption(match.home.name, preview.home.elevenSource)?.let { NoticeLine(it) }
    if (away != null) elevenCaption(match.away.name, preview.away.elevenSource)?.let { NoticeLine(it) }
    MatchPitch(home, away)
}

@Composable
private fun insightText(insight: PreviewInsight): String {
    val team = insight.team.orEmpty()
    val number = insight.number ?: 0
    val decimal = oneDecimal(insight.decimal ?: 0.0)
    return when (insight.kind) {
        InsightKind.WIN_STREAK -> stringResource(R.string.insight_win_streak, team, number)
        InsightKind.WINLESS_RUN -> stringResource(R.string.insight_winless, team, number)
        InsightKind.CLOSE_IN_TABLE -> stringResource(R.string.insight_close, number)
        InsightKind.TABLE_GAP -> stringResource(R.string.insight_gap, team, number)
        InsightKind.HIGH_SCORING -> stringResource(R.string.insight_scoring, team, decimal, number)
        InsightKind.LEAKY_DEFENCE -> stringResource(R.string.insight_leaky, team, decimal, number)
        InsightKind.KEY_ABSENCE -> stringResource(R.string.insight_absence, team, insight.player.orEmpty(), number)
    }
}

private const val MAX_ABSENCES = 6

@Composable
fun HeadToHeadCard(match: Match, record: app.kerkida.data.espn.HeadToHeadRecord, onTeam: (String) -> Unit) {
    val colors = KerkidaTheme.colors
    SectionCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.h2h_title), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button) { onTeam(match.home.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(record.wins.toString(), style = MaterialTheme.typography.headlineSmall, color = colors.win)
                    Text(match.home.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(record.draws.toString(), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.h2h_draws), style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f).clickable(role = Role.Button) { onTeam(match.away.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(record.losses.toString(), style = MaterialTheme.typography.headlineSmall, color = colors.loss)
                    Text(match.away.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            record.games.take(5).forEach { game ->
                val date = game.kickoff?.atZone(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofPattern("d/M/yy")).orEmpty()
                Row {
                    Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(62.dp))
                    Text(
                        "${game.home.name} ${game.homeScore}–${game.awayScore} ${game.away.name}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
