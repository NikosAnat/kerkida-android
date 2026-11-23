package app.kerkida.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.LeagueGrouping
import app.kerkida.data.espn.EspnProvider
import app.kerkida.data.model.Sport
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.DetailHeader
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SportStrip
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.sportIcon
import app.kerkida.ui.common.sportLabel
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaIcons
import java.util.Locale

private const val MANY_LEAGUES = 60

/** Choose what the app follows, sport by sport and then country by country. */
@Composable
fun LeaguePickerScreen(onBack: () -> Unit) {
    val catalog = appContainer().catalog
    val selected by catalog.selected.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sportName by rememberSaveable { mutableStateOf(Sport.FOOTBALL.name) }
    val allSports = remember { catalog.all.map { it.sport }.distinct().sortedBy { it.ordinal } }
    var openCountries by rememberSaveable {
        mutableStateOf(listOf("FOOTBALL/GR", "FOOTBALL/${LeagueGrouping.INTERNATIONAL}", "BASKETBALL/GR", "BASKETBALL/${LeagueGrouping.INTERNATIONAL}"))
    }
    val locale = currentLocale()
    val international = stringResource(R.string.leagues_international)
    val needle = remember(query) { EspnProvider.searchKey(query) }
    val groups = remember(needle, locale, international) {
        val shown = catalog.all.filter { league ->
            needle.isEmpty() || EspnProvider.searchKey(league.name).contains(needle) ||
                LeagueGrouping.countryOf(league)?.let { EspnProvider.searchKey(countryName(it, locale)).contains(needle) } == true
        }
        LeagueGrouping.group(shown, locale, international) { countryName(it, locale) }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.leagues_title), onBack = onBack) {
            TextButton(onClick = catalog::reset) {
                Text(stringResource(R.string.leagues_reset), color = app.kerkida.ui.theme.KerkidaTheme.colors.onBrandBar)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            placeholder = { Text(stringResource(R.string.leagues_search)) },
            leadingIcon = { Icon(KerkidaIcons.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(KerkidaIcons.Close, stringResource(R.string.action_clear)) }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (needle.isEmpty()) SportStrip(allSports, Sport.valueOf(sportName)) { sportName = it.name }
        Text(
            stringResource(R.string.leagues_count, selected.size, catalog.all.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (selected.size > MANY_LEAGUES) {
            NoticeLine(stringResource(R.string.leagues_many), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            groups.filter { needle.isNotEmpty() || it.sport.name == sportName }.forEach { sportGroup ->
                if (needle.isNotEmpty()) {
                    item(key = "sport-${sportGroup.sport.name}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(sportIcon(sportGroup.sport), null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(sportLabel(sportGroup.sport), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                sportGroup.countries.forEach { group ->
                    val open = needle.isNotEmpty() || group.key in openCountries || sportGroup.countries.size == 1
                    val chosen = group.leagues.count { it.id in selected }
                    item(key = "head-${group.key}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 4.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                .clickable(role = Role.Button) {
                                    openCountries = if (group.key in openCountries) openCountries - group.key else openCountries + group.key
                                }
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (open) KerkidaIcons.ChevronUp else KerkidaIcons.ChevronDown, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(group.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("$chosen/${group.leagues.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Checkbox(
                                checked = chosen == group.leagues.size,
                                onCheckedChange = { on -> catalog.setAll(group.leagues.map { it.id }, on) },
                            )
                        }
                    }
                    if (open) {
                        items(group.leagues, key = { "${group.key}-${it.id}" }) { league ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = league.id in selected, role = Role.Checkbox) { catalog.toggle(league.id) }
                                    .padding(start = 22.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Crest(league.logo, league.name, 24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    league.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Checkbox(checked = league.id in selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

internal fun countryName(code: String, locale: Locale): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
