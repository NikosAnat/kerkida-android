package app.kerkida.ui.favourites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.AppContainer
import app.kerkida.data.Favourite
import app.kerkida.data.FavouriteKind
import app.kerkida.ui.common.BrandHeader
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.FavouriteButton
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.kerkidaViewModel
import app.kerkida.ui.theme.KerkidaIcons
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FavouritesViewModel(private val container: AppContainer) : ViewModel() {
    private val providerId = container.provider.value.info.id
    val favourites: StateFlow<List<Favourite>> = container.favourites.favourites(providerId)

    fun remove(item: Favourite) = container.favourites.toggle(providerId, item)
}

@Composable
fun FavouritesScreen(
    onTeam: (String) -> Unit,
    onLeague: (String) -> Unit,
    onMatch: (String) -> Unit,
    onSearch: () -> Unit,
    onSeason: () -> Unit = {},
) {
    val viewModel = kerkidaViewModel { FavouritesViewModel(it) }
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        BrandHeader(title = stringResource(R.string.nav_favourites)) {
            androidx.compose.material3.IconButton(onClick = onSeason) {
                androidx.compose.material3.Icon(KerkidaIcons.Trophy, contentDescription = stringResource(R.string.season_title))
            }
        }
        if (favourites.isEmpty()) {
            MessageView(
                icon = KerkidaIcons.Star,
                title = stringResource(R.string.favourites_empty_title),
                body = stringResource(R.string.favourites_empty_body),
                primaryLabel = stringResource(R.string.action_go_search),
                onPrimary = onSearch,
            )
        } else {
            val teams = favourites.filter { it.favouriteKind == FavouriteKind.TEAM }
            val leagues = favourites.filter { it.favouriteKind == FavouriteKind.LEAGUE }
            val matches = favourites
                .filter { it.favouriteKind == FavouriteKind.MATCH }
                .sortedByDescending { it.kickoffEpochSeconds ?: 0 }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (teams.isNotEmpty()) item(key = "dashboard") { MyDashboard(teams, onMatch) }
                favouriteSection(R.string.favourites_teams, teams, onTeam, viewModel::remove)
                favouriteSection(R.string.favourites_leagues, leagues, onLeague, viewModel::remove)
                favouriteSection(R.string.favourites_matches, matches, onMatch, viewModel::remove)
                item(key = "note") {
                    Text(
                        stringResource(R.string.favourites_device_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.favouriteSection(
    titleRes: Int,
    items: List<Favourite>,
    onOpen: (String) -> Unit,
    onRemove: (Favourite) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "title-$titleRes") { SectionTitle(stringResource(titleRes)) }
    item(key = "card-$titleRes") {
        SectionCard {
            items.forEachIndexed { index, favourite ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FavouriteRow(favourite, onOpen, onRemove)
            }
        }
    }
}

@Composable
private fun FavouriteRow(item: Favourite, onOpen: (String) -> Unit, onRemove: (Favourite) -> Unit) {
    val locale = currentLocale()
    val whenText = item.kickoffEpochSeconds?.let {
        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(item.id) }
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(item.badgeUrl, item.title, 32.dp)
        if (item.favouriteKind == FavouriteKind.MATCH) {
            Spacer(Modifier.width(4.dp))
            Crest(item.awayBadgeUrl, item.title.substringAfter("– "), 32.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(item.subtitle, whenText).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FavouriteButton(isFavourite = true, onToggle = { onRemove(item) })
    }
}
