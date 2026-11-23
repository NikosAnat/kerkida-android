package app.kerkida.ui.broadcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.broadcast.AccessType
import app.kerkida.broadcast.Broadcast
import app.kerkida.broadcast.BroadcastScope
import app.kerkida.broadcast.GreekChannels
import app.kerkida.broadcast.ManualBroadcastProvider
import app.kerkida.broadcast.WatchRank
import app.kerkida.broadcast.WhatIHave
import app.kerkida.data.model.Match
import app.kerkida.features.Feature
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun accessLabel(access: AccessType): String = stringResource(
    when (access) {
        AccessType.FREE -> R.string.access_free
        AccessType.SUBSCRIPTION -> R.string.access_subscription
        AccessType.PAY_PER_VIEW -> R.string.access_ppv
    },
)

@Composable
fun rankTitle(rank: WatchRank): String = stringResource(
    when (rank) {
        WatchRank.OWNED -> R.string.rank_owned
        WatchRank.FREE -> R.string.rank_free
        WatchRank.PAY_PER_VIEW -> R.string.rank_ppv
        WatchRank.NOT_OWNED -> R.string.rank_not_owned
        WatchRank.UNKNOWN -> R.string.rank_unknown
    },
)

@Composable
private fun rememberBroadcasts(match: Match): List<Broadcast> {
    val container = appContainer()
    val repository = container.broadcasts
    val items by repository.manual.items.collectAsStateWithLifecycle()
    val listings by container.tv.items.collectAsStateWithLifecycle()
    // Matching Greek listings to teams is real work, so it never runs on the screen's thread.
    val list by produceState(emptyList<Broadcast>(), match.id, match.season, match.kickoff, items, listings) {
        value = withContext(Dispatchers.Default) { repository.known(match) }
    }
    return list
}

/** One small line under a match row. Nothing is drawn when no broadcast is known. */
@Composable
fun BroadcastLine(match: Match, modifier: Modifier = Modifier) {
    if (!isFeatureOn(Feature.BROADCASTS)) return
    val list = rememberBroadcasts(match)
    if (list.isEmpty()) return
    val owned by appContainer().broadcasts.subscriptions.collectAsStateWithLifecycle()
    val best = WhatIHave.bestFirst(list, owned).first()
    val canWatch = WhatIHave.rankOf(best, owned) <= WatchRank.FREE
    val color = if (canWatch) KerkidaTheme.colors.win else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(KerkidaIcons.Tv, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        val extra = if (list.size > 1) " +${list.size - 1}" else ""
        Text(
            "${best.channelName} · ${accessLabel(best.access)}$extra",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun updatedDate(ms: Long): String {
    val locale = currentLocale()
    return remember(ms, locale) {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
    }
}

/**
 * A small pill in the brand colours that opens the channel in the Nova TV app.
 * It stays compact on screen, and Compose still gives it a full-size touch area.
 */
@Composable
fun NovaWatchButton(channels: List<String>, modifier: Modifier = Modifier) {
    if (!isFeatureOn(Feature.NOVA_BUTTON)) return
    val channel = remember(channels) { app.kerkida.broadcast.NovaTv.firstCarried(channels) } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val description = stringResource(R.string.nova_watch_channel, channel)
    // A soft blue-grey that stands out from the card without the glare of pure white.
    val ink = androidx.compose.ui.graphics.Color(0xFF0B1F4B)
    val isDarkCard = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fill = androidx.compose.ui.graphics.Color(if (isDarkCard) 0xFFBCCBE3 else 0xFFD6E0F0)
    Row(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { app.kerkida.broadcast.NovaTv.open(context, channel) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶", style = MaterialTheme.typography.labelSmall, color = ink)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.nova_watch), style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/** The editable "where to watch" card on the match page. */
@Composable
fun BroadcastCard(match: Match) {
    if (!isFeatureOn(Feature.BROADCASTS)) return
    val repository = appContainer().broadcasts
    val list = rememberBroadcasts(match)
    val owned by repository.subscriptions.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(KerkidaIcons.Tv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.broadcast_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.broadcast_add)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.broadcast_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        WhatIHave.bestFirst(list, owned).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${item.channelName} · ${accessLabel(item.access)}", style = MaterialTheme.typography.bodyMedium)
                    val isManual = item.sourceId == ManualBroadcastProvider.ID
                    val scope = stringResource(
                        when {
                            !isManual -> R.string.broadcast_auto
                            item.scope == BroadcastScope.MATCH -> R.string.broadcast_scope_match
                            else -> R.string.broadcast_scope_league
                        },
                    )
                    Text(
                        "$scope · ${stringResource(R.string.broadcast_updated, updatedDate(item.updatedAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.sourceId == ManualBroadcastProvider.ID) {
                    IconButton(onClick = { repository.manual.remove(item.id) }) {
                        Icon(KerkidaIcons.Close, stringResource(R.string.broadcast_remove), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                NovaWatchButton(listOf(item.channelName), Modifier.padding(bottom = 6.dp))
            }
        }
    }
    if (adding) AddBroadcastDialog(match) { adding = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBroadcastDialog(match: Match, onClose: () -> Unit) {
    val repository = appContainer().broadcasts
    var channelId by rememberSaveable { mutableStateOf(GreekChannels.first().id) }
    var otherName by rememberSaveable { mutableStateOf("") }
    var access by rememberSaveable { mutableStateOf(GreekChannels.first().usualAccess) }
    var scope by rememberSaveable { mutableStateOf(BroadcastScope.MATCH) }
    val isOther = channelId == OTHER
    val name = if (isOther) otherName.trim() else GreekChannels.first { it.id == channelId }.name

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.broadcast_add_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.broadcast_channel), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GreekChannels.forEach { channel ->
                        FilterChip(
                            selected = channelId == channel.id,
                            onClick = {
                                channelId = channel.id
                                access = channel.usualAccess
                            },
                            label = { Text(channel.name) },
                        )
                    }
                    FilterChip(
                        selected = isOther,
                        onClick = { channelId = OTHER },
                        label = { Text(stringResource(R.string.broadcast_other)) },
                    )
                }
                if (isOther) {
                    OutlinedTextField(
                        value = otherName,
                        onValueChange = { otherName = it.take(40) },
                        label = { Text(stringResource(R.string.broadcast_channel_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.broadcast_access), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccessType.entries.forEach { type ->
                        FilterChip(selected = access == type, onClick = { access = type }, label = { Text(accessLabel(type)) })
                    }
                }
                Text(stringResource(R.string.broadcast_applies), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = scope == BroadcastScope.MATCH,
                        onClick = { scope = BroadcastScope.MATCH },
                        label = { Text(stringResource(R.string.broadcast_scope_match)) },
                    )
                    FilterChip(
                        selected = scope == BroadcastScope.LEAGUE,
                        onClick = { scope = BroadcastScope.LEAGUE },
                        label = { Text(stringResource(R.string.broadcast_scope_league)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotEmpty(),
                onClick = {
                    val id = if (isOther) "other:${name.lowercase()}" else channelId
                    repository.manual.add(match, scope, id, name, access)
                    onClose()
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val OTHER = "__other"

@Composable
fun RankHeader(rank: WatchRank) {
    Text(
        rankTitle(rank),
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false, role = Role.Button) {}
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
