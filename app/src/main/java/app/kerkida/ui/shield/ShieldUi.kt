package app.kerkida.ui.shield

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.KerkidaApp
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchStatus
import app.kerkida.features.Feature
import app.kerkida.shield.RevealLevel
import app.kerkida.shield.ShieldMode
import app.kerkida.shield.ShieldedView
import app.kerkida.ui.common.statusLabel
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as KerkidaApp).container

@Composable
fun isFeatureOn(feature: Feature): Boolean {
    val enabled by appContainer().flags.enabled.collectAsStateWithLifecycle()
    return feature in enabled
}

/** What may be drawn for this match right now. It updates as the sync delay runs out. */
@Composable
fun rememberShieldedView(match: Match, events: List<MatchEvent> = emptyList()): ShieldedView {
    val container = appContainer()
    val shield = container.shield
    val entries by shield.entries.collectAsStateWithLifecycle()
    val settings by shield.settings.collectAsStateWithLifecycle()
    val clock by shield.clock.collectAsStateWithLifecycle()
    val flags by container.flags.enabled.collectAsStateWithLifecycle()
    val provider by container.provider.collectAsStateWithLifecycle()
    val favourites by remember(provider.info.id) { container.favourites.favourites(provider.info.id) }
        .collectAsStateWithLifecycle()
    return remember(match, events, entries, settings, clock, flags, favourites) { shield.view(match, events) }
}

/** The status line, without revealing whether a hidden match is running or over. */
@Composable
fun shieldedStatus(view: ShieldedView, original: Match): String = when {
    view.waitingForSync -> stringResource(R.string.shield_syncing)
    view.showClock -> statusLabel(view.match)
    original.status == MatchStatus.SCHEDULED -> statusLabel(original)
    else -> stringResource(R.string.shield_hidden)
}

fun isHiddenAfterKickoff(view: ShieldedView, original: Match): Boolean =
    view.isShielded && !view.showScore && original.status != MatchStatus.SCHEDULED

/** Tap to hide or reveal. Long press for partial reveal and recorded matches. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShieldEye(match: Match, modifier: Modifier = Modifier, tint: Color? = null) {
    if (!isFeatureOn(Feature.SPOILER_SHIELD)) return
    val shield = appContainer().shield
    val view = rememberShieldedView(match)
    var menu by remember { mutableStateOf(false) }
    val hidden = view.isShielded && view.entry?.level != RevealLevel.FULL
    val description = stringResource(if (hidden) R.string.shield_reveal else R.string.shield_hide)
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = description,
                onLongClickLabel = stringResource(R.string.shield_options),
                onLongClick = { menu = true },
                onClick = { shield.toggle(match) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (hidden) KerkidaIcons.EyeOff else KerkidaIcons.Eye,
            contentDescription = description,
            tint = when {
                hidden -> KerkidaTheme.colors.accent
                tint != null -> tint
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun close(action: () -> Unit): () -> Unit = {
                action()
                menu = false
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shield_hide_all)) },
                onClick = close { shield.setLevel(match, RevealLevel.HIDDEN) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shield_only_time)) },
                onClick = close { shield.setLevel(match, RevealLevel.TIME_ONLY) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shield_only_score)) },
                onClick = close { shield.setLevel(match, RevealLevel.SCORE_ONLY) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shield_reveal_all)) },
                onClick = close { shield.setLevel(match, RevealLevel.FULL) },
            )
            HorizontalDivider()
            if (view.entry?.mode == ShieldMode.RECORDED && view.entry.level != RevealLevel.FULL) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shield_watched)) },
                    onClick = close { shield.markWatched(match) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shield_recorded)) },
                    onClick = close { shield.markRecorded(match) },
                )
            }
            if (view.isShielded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shield_remove)) },
                    onClick = close { shield.clear(match) },
                )
            }
        }
    }
}
