package app.kerkida.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.BuildConfig
import app.kerkida.KerkidaApp
import app.kerkida.R
import app.kerkida.data.AppLanguage
import app.kerkida.data.ThemeMode
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle

@Composable
fun SettingsScreen(onBack: () -> Unit, onLeagues: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as KerkidaApp).container
    val activeProvider by container.provider.collectAsState()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.settings_title), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val chosenLeagues by container.catalog.selected.collectAsStateWithLifecycle()
            SectionTitle(stringResource(R.string.settings_leagues))
            SectionCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onLeagues)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.leagues_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.leagues_count, chosenLeagues.size, container.catalog.all.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Icon(app.kerkida.ui.theme.KerkidaIcons.ChevronRight, null)
                }
            }

            SectionTitle(stringResource(R.string.settings_appearance))
            SectionCard {
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(stringResource(R.string.theme_system), null, settings.theme == ThemeMode.SYSTEM) {
                        container.settings.setTheme(ThemeMode.SYSTEM)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChoiceRow(stringResource(R.string.theme_light), null, settings.theme == ThemeMode.LIGHT) {
                        container.settings.setTheme(ThemeMode.LIGHT)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChoiceRow(stringResource(R.string.theme_dark), null, settings.theme == ThemeMode.DARK) {
                        container.settings.setTheme(ThemeMode.DARK)
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_language))
            SectionCard {
                Column(Modifier.selectableGroup()) {
                    val choose = { language: AppLanguage ->
                        if (language != settings.language) {
                            container.settings.setLanguage(language)
                            (context as? Activity)?.recreate()
                        }
                    }
                    ChoiceRow("Ελληνικά", null, settings.language == AppLanguage.GREEK) { choose(AppLanguage.GREEK) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChoiceRow("English", null, settings.language == AppLanguage.ENGLISH) { choose(AppLanguage.ENGLISH) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChoiceRow(stringResource(R.string.language_system), null, settings.language == AppLanguage.SYSTEM) {
                        choose(AppLanguage.SYSTEM)
                    }
                }
            }

            StageOneSettings()

            SectionTitle(stringResource(R.string.settings_source))
            SectionCard {
                Column(Modifier.selectableGroup()) {
                    container.registry.providers.forEachIndexed { index, provider ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ChoiceRow(
                            title = if (provider.info.isDemo) {
                                stringResource(R.string.source_demo_title)
                            } else {
                                provider.info.displayName
                            },
                            subtitle = stringResource(
                                if (provider.info.isDemo) R.string.source_demo_body else R.string.source_live_body,
                            ),
                            selected = settings.providerId == provider.info.id,
                        ) {
                            container.settings.setProvider(provider.info.id)
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_about))
            SectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.about_attribution, activeProvider.info.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) {
                            uriHandler.openUri(activeProvider.info.attributionUrl ?: "https://www.espn.com/soccer/")
                        },
                    )
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
