package app.kerkida.ui.season

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.season.Announcer
import app.kerkida.season.SeasonLogic
import app.kerkida.season.VisitedGround
import app.kerkida.season.WatchedMatch
import app.kerkida.shield.ShieldedView
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MySeasonScreen(onBack: () -> Unit, onMatch: (String) -> Unit) {
    val store = appContainer().season
    val watched by store.watched.collectAsStateWithLifecycle()
    val season = remember { SeasonLogic.seasonOf(System.currentTimeMillis()) }
    val summary = remember(watched, season) { SeasonLogic.summary(watched, season) }
    val visited = remember(watched) { SeasonLogic.grounds(watched) }
    val games = remember(watched, season) {
        watched.filter { SeasonLogic.seasonOf(it.kickoffMs) == season }.sortedByDescending { it.kickoffMs }
    }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.season_title), onBack = onBack)
        if (games.isEmpty() && visited.isEmpty()) {
            MessageView(KerkidaIcons.Trophy, stringResource(R.string.season_empty_title), stringResource(R.string.season_empty_body))
            return
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "numbers") {
                SectionCard {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        Number(summary.matches.toString(), stringResource(R.string.season_matches), Modifier.weight(1f))
                        Number(summary.goals.toString(), stringResource(R.string.season_goals), Modifier.weight(1f))
                        Number(String.format(Locale.US, "%.1f", summary.perMatch), stringResource(R.string.season_per_match), Modifier.weight(1f))
                        Number(summary.grounds.toString(), stringResource(R.string.season_grounds), Modifier.weight(1f))
                    }
                }
            }
            summary.biggest?.let { game ->
                item(key = "biggest") {
                    SectionTitle(stringResource(R.string.season_biggest))
                    SectionCard { GameLine(game, onMatch) }
                }
            }
            if (summary.topTeams.isNotEmpty()) {
                item(key = "teams") {
                    SectionTitle(stringResource(R.string.season_top_teams))
                    SectionCard {
                        summary.topTeams.forEachIndexed { index, (name, count) ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(count.toString(), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            if (visited.isNotEmpty()) {
                item(key = "grounds") {
                    SectionTitle(stringResource(R.string.grounds_title, visited.size))
                    SectionCard { GroundsCard(visited) }
                }
            }
            item(key = "title") { SectionTitle(stringResource(R.string.season_log)) }
            items(games, key = { it.matchId }) { game -> SectionCard { GameLine(game, onMatch) } }
        }
    }
}

@Composable
private fun Number(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = KerkidaTheme.colors.win)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun GameLine(game: WatchedMatch, onMatch: (String) -> Unit) {
    val locale = currentLocale()
    val date = remember(game.kickoffMs, locale) {
        Instant.ofEpochMilli(game.kickoffMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
    val score = if (game.homeScore != null && game.awayScore != null) "${game.homeScore} – ${game.awayScore}" else "–"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onMatch(game.matchId) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(game.home, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Crest(game.homeBadge, game.home, 22.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(score, style = MaterialTheme.typography.titleSmall)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Crest(game.awayBadge, game.away, 22.dp)
        Spacer(Modifier.width(6.dp))
        Text(game.away, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** "I watched this" on the match page. */
@Composable
fun WatchedButton(match: Match) {
    val store = appContainer().season
    val watched by store.watched.collectAsStateWithLifecycle()
    val isWatched = watched.any { it.matchId == match.id }
    LaunchedEffect(match.status, match.homeScore, match.awayScore, isWatched) { if (isWatched) store.refresh(match) }
    OutlinedButton(onClick = { store.toggle(match) }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (isWatched) KerkidaIcons.Check else KerkidaIcons.Trophy, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (isWatched) R.string.season_watched else R.string.season_mark))
    }
}

/** Marks that the person was at the ground. */
@Composable
fun AttendedButton(match: Match) {
    val container = appContainer()
    val watched by container.season.watched.collectAsStateWithLifecycle()
    val wasThere = watched.any { it.matchId == match.id && it.attended }
    OutlinedButton(
        onClick = { container.season.toggleAttended(match, container.stadiums.find(match)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(if (wasThere) KerkidaIcons.Check else KerkidaIcons.Pin, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (wasThere) R.string.grounds_was_there else R.string.grounds_mark))
    }
}

/** A small map of Greece's bounds with a dot per ground, then the list. Needs no map service. */
@Composable
private fun GroundsCard(grounds: List<VisitedGround>) {
    val dot = KerkidaTheme.colors.win
    val edge = MaterialTheme.colorScheme.outlineVariant
    val placed = grounds.filter { it.lat != null && it.lng != null }
    Column {
        if (placed.isNotEmpty()) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp).padding(12.dp)) {
                drawRoundRect(edge, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                placed.forEach { ground ->
                    val x = ((ground.lng!! - 19.3) / (28.4 - 19.3)).coerceIn(0.0, 1.0).toFloat() * size.width
                    val y = (1 - (ground.lat!! - 34.7) / (41.8 - 34.7)).coerceIn(0.0, 1.0).toFloat() * size.height
                    drawCircle(dot, radius = (3 + ground.visits.coerceAtMost(4)).dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
        }
        grounds.forEachIndexed { index, ground ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ground.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ground.city?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(ground.visits.toString(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** A speaker button: while on, key events of this match are read out. */
@Composable
fun AudioButton(view: ShieldedView, events: List<MatchEvent>) {
    val context = LocalContext.current
    var isOn by rememberSaveable { mutableStateOf(false) }
    val speaker = remember { arrayOfNulls<TextToSpeech>(1) }
    val spoken = remember { arrayOfNulls<Set<String>>(1) }
    val locale = currentLocale()
    val words = Announcer.Words(
        stringResource(R.string.audio_goal), stringResource(R.string.audio_penalty), stringResource(R.string.audio_own_goal),
        stringResource(R.string.audio_red), stringResource(R.string.audio_missed), stringResource(R.string.audio_minute),
    )
    DisposableEffect(isOn) {
        if (isOn) {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) engine?.language = locale
            }
            speaker[0] = engine
            spoken[0] = events.map(Announcer::key).toSet()
        }
        onDispose {
            speaker[0]?.stop()
            speaker[0]?.shutdown()
            speaker[0] = null
        }
    }
    // Reads the shielded events, so a hidden match stays silent and a delayed one speaks on time.
    LaunchedEffect(events, isOn) {
        if (!isOn) return@LaunchedEffect
        Announcer.fresh(spoken[0], events).forEach { event ->
            speaker[0]?.speak(Announcer.line(event, view.match, words), TextToSpeech.QUEUE_ADD, null, Announcer.key(event))
        }
        spoken[0] = spoken[0].orEmpty() + events.map(Announcer::key)
    }
    IconButton(onClick = { isOn = !isOn }) {
        Icon(
            if (isOn) KerkidaIcons.SoundOn else KerkidaIcons.SoundOff,
            stringResource(if (isOn) R.string.audio_off else R.string.audio_on),
            tint = if (isOn) KerkidaTheme.colors.accent else KerkidaTheme.colors.onBrandBar,
        )
    }
}
