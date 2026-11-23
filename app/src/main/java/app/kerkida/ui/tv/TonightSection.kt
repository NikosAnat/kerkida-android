package app.kerkida.ui.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.broadcast.WhatIHave
import app.kerkida.data.DataException
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Standings
import app.kerkida.tv.Tonight
import app.kerkida.tv.TonightEntry
import app.kerkida.tv.WorthLevel
import app.kerkida.tv.WorthReason
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.localTimeText
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_ROWS = 6

/** Opens the phone's calendar with the match and channel filled in. No permission is needed. */
fun addToCalendar(context: Context, match: Match, channel: String?) {
    val start = match.kickoff?.toEpochMilli() ?: return
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, "${match.home.name} – ${match.away.name}")
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 115 * 60_000L)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, channel ?: match.venue.orEmpty())
        .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(match.league.name, channel).joinToString(" · "))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without a calendar app simply does nothing.
    }
}

@Composable
fun TonightSection(matches: List<Match>, onMatch: (String) -> Unit) {
    val container = appContainer()
    val owned by container.broadcasts.subscriptions.collectAsStateWithLifecycle()
    val listings by container.tv.items.collectAsStateWithLifecycle()
    val provider by container.provider.collectAsStateWithLifecycle()
    val favourites by remember(provider.info.id) { container.favourites.favourites(provider.info.id) }
        .collectAsStateWithLifecycle()
    val myTeams = remember(favourites) { favourites.filter { it.favouriteKind == FavouriteKind.TEAM }.map { it.id }.toSet() }
    val upcoming = remember(matches) {
        matches.filter { it.status == MatchStatus.SCHEDULED || it.status == MatchStatus.LIVE || it.status == MatchStatus.HALF_TIME }
    }
    val leagues = remember(upcoming) { upcoming.map { it.league.id }.distinct() }
    val tables by produceState<Map<String, Standings>>(emptyMap(), leagues, provider.info.id) {
        leagues.forEach { id ->
            try {
                value = value + (id to provider.standings(id, null).data)
            } catch (_: DataException) {
                // Without a table the match just gets no "worth it" mark.
            }
        }
    }
    val entries by produceState(emptyList<TonightEntry>(), upcoming, tables, listings, owned, myTeams) {
        value = withContext(Dispatchers.Default) {
            val all = upcoming.map { match ->
                TonightEntry(match, container.broadcasts.known(match), Tonight.worth(match, tables[match.league.id]))
            }.filter { it.broadcasts.isNotEmpty() || Tonight.isMine(it.match, myTeams) }
            Tonight.rank(all, myTeams, owned)
        }
    }
    if (entries.isEmpty()) return
    val clashes = remember(entries, myTeams) { Tonight.clashes(entries, myTeams) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.tonight_title))
        SectionCard {
            entries.take(MAX_ROWS).forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TonightRow(entry, Tonight.isMine(entry.match, myTeams), entry.match.id in clashes, owned, onMatch)
            }
        }
    }
}

@Composable
private fun TonightRow(entry: TonightEntry, isMine: Boolean, clashes: Boolean, owned: Set<String>, onMatch: (String) -> Unit) {
    val context = LocalContext.current
    val colors = KerkidaTheme.colors
    val match = entry.match
    val channel = WhatIHave.bestFirst(entry.broadcasts, owned).firstOrNull()?.channelName
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onMatch(match.id) }
            .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(match.kickoff?.localTimeText() ?: "–", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
        Crest(match.home.badgeUrl, match.home.name, 20.dp)
        Spacer(Modifier.width(3.dp))
        Crest(match.away.badgeUrl, match.away.name, 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${match.home.name} – ${match.away.name}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                if (isMine) Tag(stringResource(R.string.tonight_mine), colors.accent)
                entry.worth?.let { worth ->
                    val label = stringResource(
                        when (worth.reason) {
                            WorthReason.TOP_CLASH -> R.string.tonight_top
                            WorthReason.CLOSE_RACE -> R.string.tonight_close
                            WorthReason.RELEGATION -> R.string.tonight_relegation
                        },
                    )
                    Tag(label, if (worth.level == WorthLevel.TOP) Color(0xFFF2B134) else Color(0xFFB9F0DC))
                }
                if (clashes) Tag(stringResource(R.string.tonight_clash), Color(0xFFFF8A73))
            }
        }
        IconButton(onClick = { addToCalendar(context, match, channel) }) {
            Icon(KerkidaIcons.Calendar, stringResource(R.string.tonight_add), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF0B1F4B),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
