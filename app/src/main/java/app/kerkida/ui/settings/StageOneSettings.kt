package app.kerkida.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.alerts.AlertNotifier
import app.kerkida.alerts.AlertScheduler
import app.kerkida.assistant.AssistantScheduler
import app.kerkida.broadcast.SubscribableChannels
import app.kerkida.data.FavouriteKind
import app.kerkida.features.Feature
import app.kerkida.shield.ShieldSettings
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.shield.appContainer

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun StageOneSettings() {
    val context = LocalContext.current
    val container = appContainer()
    val flags by container.flags.enabled.collectAsStateWithLifecycle()
    val shield by container.shield.settings.collectAsStateWithLifecycle()
    val owned by container.broadcasts.subscriptions.collectAsStateWithLifecycle()
    val rules by container.alerts.rules.collectAsStateWithLifecycle()
    val provider by container.provider.collectAsStateWithLifecycle()
    val favourites by remember(provider.info.id) { container.favourites.favourites(provider.info.id) }
        .collectAsStateWithLifecycle()
    val teams = favourites.filter { it.favouriteKind == FavouriteKind.TEAM }

    val enableAlerts = {
        container.flags.set(Feature.SMART_ALERTS, true)
        AlertScheduler.start(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableAlerts()
    }

    var pendingAssistant by remember { mutableStateOf<Feature?>(null) }
    val assistantPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingAssistant?.takeIf { granted }?.let {
            container.flags.set(it, true)
            AssistantScheduler.sync(context)
        }
        pendingAssistant = null
    }
    val setAssistant: (Feature, Boolean) -> Unit = { feature, on ->
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AlertNotifier.canNotify(context)) {
            pendingAssistant = feature
            assistantPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            container.flags.set(feature, on)
            AssistantScheduler.sync(context)
        }
    }

    SectionTitle(stringResource(R.string.settings_features))
    SectionCard {
        SwitchRow(
            stringResource(R.string.feature_shield),
            stringResource(R.string.feature_shield_body),
            Feature.SPOILER_SHIELD in flags,
        ) { container.flags.set(Feature.SPOILER_SHIELD, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_broadcasts),
            stringResource(R.string.feature_broadcasts_body),
            Feature.BROADCASTS in flags,
        ) { container.flags.set(Feature.BROADCASTS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_tv),
            stringResource(R.string.feature_tv_body),
            Feature.TV_GUIDE in flags,
        ) { container.flags.set(Feature.TV_GUIDE, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_ratings),
            stringResource(R.string.feature_ratings_body),
            Feature.RATINGS in flags,
        ) { container.flags.set(Feature.RATINGS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_preview),
            stringResource(R.string.feature_preview_body),
            Feature.PREVIEW in flags,
        ) { container.flags.set(Feature.PREVIEW, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.fantasy_title),
            stringResource(R.string.feature_fantasy_body),
            Feature.FANTASY in flags,
        ) { container.flags.set(Feature.FANTASY, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.tv_tab_highlights),
            stringResource(R.string.feature_highlights_body),
            Feature.HIGHLIGHTS in flags,
        ) { container.flags.set(Feature.HIGHLIGHTS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_greek_lower),
            stringResource(R.string.feature_greek_lower_body),
            Feature.GREEK_LOWER in flags,
        ) { container.flags.set(Feature.GREEK_LOWER, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_greek_open),
            stringResource(R.string.feature_greek_open_body),
            Feature.GREEK_OPEN_RESULTS in flags,
        ) { container.flags.set(Feature.GREEK_OPEN_RESULTS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_nova_button),
            stringResource(R.string.feature_nova_button_body),
            Feature.NOVA_BUTTON in flags,
        ) { container.flags.set(Feature.NOVA_BUTTON, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_club_profile),
            stringResource(R.string.feature_club_profile_body),
            Feature.CLUB_PROFILE in flags,
        ) { container.flags.set(Feature.CLUB_PROFILE, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_player_photos),
            stringResource(R.string.feature_player_photos_body),
            Feature.PLAYER_PHOTOS in flags,
        ) { container.flags.set(Feature.PLAYER_PHOTOS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_alerts),
            stringResource(R.string.feature_alerts_body),
            Feature.SMART_ALERTS in flags,
        ) { on ->
            when {
                !on -> {
                    container.flags.set(Feature.SMART_ALERTS, false)
                    AlertScheduler.stop(context)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AlertNotifier.canNotify(context) ->
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> enableAlerts()
            }
        }
    }

    SectionTitle(stringResource(R.string.settings_assistant))
    SectionCard {
        SwitchRow(
            stringResource(R.string.feature_nearby_alerts),
            stringResource(R.string.feature_nearby_alerts_body),
            Feature.NEARBY_ALERTS in flags,
        ) { setAssistant(Feature.NEARBY_ALERTS, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_digest),
            stringResource(R.string.feature_digest_body),
            Feature.WEEKLY_DIGEST in flags,
        ) { setAssistant(Feature.WEEKLY_DIGEST, it) }
        Divider()
        SwitchRow(
            stringResource(R.string.feature_fpl_deadline),
            stringResource(R.string.feature_fpl_deadline_body),
            Feature.FPL_DEADLINE in flags,
        ) { setAssistant(Feature.FPL_DEADLINE, it) }
    }

    if (Feature.SPOILER_SHIELD in flags) {
        SectionTitle(stringResource(R.string.settings_shield))
        SectionCard {
            var delay by remember(shield.delaySeconds) { mutableFloatStateOf(shield.delaySeconds.toFloat()) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.shield_delay, delay.toInt()), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.shield_delay_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = delay,
                    onValueChange = { delay = it },
                    onValueChangeFinished = { container.shield.setDelay(delay.toInt()) },
                    valueRange = ShieldSettings.MIN_DELAY.toFloat()..ShieldSettings.MAX_DELAY.toFloat(),
                    steps = (ShieldSettings.MAX_DELAY - ShieldSettings.MIN_DELAY) / 5 - 1,
                )
            }
            Divider()
            SwitchRow(
                stringResource(R.string.shield_auto),
                stringResource(R.string.shield_auto_body),
                shield.autoForFavourites,
                container.shield::setAutoForFavourites,
            )
        }
    }

    if (Feature.BROADCASTS in flags) {
        SectionTitle(stringResource(R.string.settings_subscriptions))
        SectionCard {
            SubscribableChannels.forEachIndexed { index, channel ->
                if (index > 0) Divider()
                CheckRow(channel.name, channel.id in owned) { container.broadcasts.setSubscription(channel.id, it) }
            }
        }
    }

    if (Feature.SMART_ALERTS in flags) {
        SectionTitle(stringResource(R.string.settings_alerts))
        NoticeLine(stringResource(R.string.alerts_timing))
        if (teams.isEmpty()) {
            NoticeLine(stringResource(R.string.alerts_no_teams))
        }
        teams.forEach { team ->
            val rule = rules[team.id] ?: container.alerts.rulesFor(team.id)
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        team.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                    )
                    CheckRow(stringResource(R.string.alert_rule_red), rule.redCards) {
                        container.alerts.setRules(team.id, rule.copy(redCards = it))
                    }
                    CheckRow(stringResource(R.string.alert_rule_late), rule.lateGoals) {
                        container.alerts.setRules(team.id, rule.copy(lateGoals = it))
                    }
                    CheckRow(stringResource(R.string.alert_rule_comeback), rule.comebacks) {
                        container.alerts.setRules(team.id, rule.copy(comebacks = it))
                    }
                }
            }
        }
        val highlightAlerts by container.highlights.alertsOn.collectAsStateWithLifecycle()
        SectionCard {
            SwitchRow(
                stringResource(R.string.highlights_alert),
                stringResource(R.string.highlights_alert_body),
                highlightAlerts,
                container.highlights::setAlerts,
            )
        }
    }
}
