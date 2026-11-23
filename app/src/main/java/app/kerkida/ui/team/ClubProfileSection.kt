package app.kerkida.ui.team

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kerkida.R
import app.kerkida.data.DataException
import app.kerkida.data.club.ClubMoment
import app.kerkida.data.club.ClubPlace
import app.kerkida.data.club.ClubProfile
import app.kerkida.data.club.ClubProfiles
import app.kerkida.data.club.MomentKind
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.features.Feature
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.NoticeLine
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.SectionTitle
import app.kerkida.ui.common.StandingsTable
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaTheme

/** The parts of a club page, each one a tab at the top. */
enum class ClubTab(val label: Int) {
    MATCHES(R.string.nav_matches),
    TABLE(R.string.league_tab_table),
    ABOUT(R.string.club_tab_about),
    HISTORY(R.string.club_tab_history),
    HONOURS(R.string.club_tab_honours),
    GROUND(R.string.club_tab_ground),
    CITY(R.string.club_tab_city),
}

/** Only tabs with something to show are offered. */
fun clubTabs(profile: ClubProfile?, hasTable: Boolean): List<ClubTab> = buildList {
    add(ClubTab.MATCHES)
    if (hasTable) add(ClubTab.TABLE)
    add(ClubTab.ABOUT)
    if (profile == null) return@buildList
    if (profile.moments.size >= 2) add(ClubTab.HISTORY)
    if (profile.honours.isNotEmpty()) add(ClubTab.HONOURS)
    if (profile.ground != null) add(ClubTab.GROUND)
    if (profile.city != null) add(ClubTab.CITY)
}

/** The club's profile, and whether it is still on its way. */
data class ClubLoad(val profile: ClubProfile? = null, val isLoading: Boolean = false)

@Composable
fun rememberClubProfile(team: Team?): State<ClubLoad> {
    val container = appContainer()
    return produceState(ClubLoad(isLoading = team != null), team?.id) {
        if (team == null || !container.flags.isOn(Feature.CLUB_PROFILE)) {
            value = ClubLoad()
            return@produceState
        }
        value = ClubLoad(isLoading = true)
        value = ClubLoad(profile = container.clubProfiles.load(team))
    }
}

/** A moving bar and a short line under the header while the team's data arrives. */
@Composable
fun TeamLoadingBar(isLoading: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        // The bar's space is always kept, so the page does not jump when it ends.
        Box(Modifier.fillMaxWidth().height(12.dp)) {
            if (isLoading) app.kerkida.ui.common.ScarfProgress()
        }
        AnimatedVisibility(visible = isLoading) {
            Text(
                stringResource(R.string.team_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubTabRow(tabs: List<ClubTab>, selected: ClubTab, onSelect: (ClubTab) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.label), maxLines = 1) },
            )
        }
    }
}

private sealed interface TableState {
    data object Loading : TableState
    data object Missing : TableState
    data class Ready(val standings: Standings) : TableState
}

/** The table of each competition the club plays in, with the club marked. */
@Composable
fun ClubStandings(teamId: String, competitions: List<LeagueRef>, onTeam: (String) -> Unit) {
    val provider by appContainer().provider.collectAsState()
    var chosenId by rememberSaveable(teamId) { mutableStateOf(competitions.first().id) }
    val chosen = competitions.firstOrNull { it.id == chosenId } ?: competitions.first()
    val state by produceState<TableState>(TableState.Loading, chosen.id, provider) {
        value = TableState.Loading
        value = try {
            provider.standings(chosen.id, null).data.takeIf { it.rows.isNotEmpty() }?.let { TableState.Ready(it) } ?: TableState.Missing
        } catch (_: DataException) {
            TableState.Missing
        }
    }
    if (competitions.size > 1) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            competitions.forEach { league ->
                FilterChip(
                    selected = league.id == chosen.id,
                    onClick = { chosenId = league.id },
                    label = { Text(league.name, maxLines = 1) },
                )
            }
        }
    }
    when (val current = state) {
        TableState.Loading -> LoadingView(rows = 6)
        TableState.Missing -> NoticeLine(stringResource(R.string.club_table_missing))
        is TableState.Ready -> StandingsTable(current.standings, onTeam, highlightTeamIds = setOf(teamId))
    }
}

@Composable
fun ClubAbout(profile: ClubProfile?) {
    profile?.about?.let {
        TextBlock(stringResource(R.string.club_about), it)
        ClubSource(profile.articleUrl)
    }
}

@Composable
fun ClubHistory(profile: ClubProfile) {
    Timeline(profile.moments)
    ClubSource(profile.articleUrl)
}

@Composable
fun ClubHonours(profile: ClubProfile) {
    Honours(profile.honours)
    ClubSource(profile.articleUrl)
}

@Composable
fun ClubPlaceTab(place: ClubPlace, articleUrl: String?) {
    PlaceCard(place, appContainer().clubProfiles)
    ClubSource(articleUrl)
}

@Composable
private fun ClubSource(url: String?) {
    url ?: return
    val context = LocalContext.current
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
        Text(stringResource(R.string.club_source), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TextBlock(title: String, text: String) {
    Column {
        SectionTitle(title)
        SectionCard {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun momentText(moment: ClubMoment): String = when (moment.kind) {
    MomentKind.FOUNDED -> stringResource(R.string.club_moment_founded)
    MomentKind.GROUND_OPENED -> stringResource(R.string.club_moment_ground, moment.text)
    MomentKind.FIRST_TITLE -> stringResource(R.string.club_moment_first, moment.text)
    MomentKind.LATEST_TITLE -> stringResource(R.string.club_moment_latest, moment.text)
    MomentKind.STORY -> moment.text
}

/** A vertical line of nodes, from the founding at the top to the latest moment at the bottom. */
@Composable
private fun Timeline(moments: List<ClubMoment>) {
    SectionCard {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            moments.forEachIndexed { index, moment ->
                TimelineNode(moment, isFirst = index == 0, isLast = index == moments.lastIndex)
            }
        }
    }
}

@Composable
private fun TimelineNode(moment: ClubMoment, isFirst: Boolean, isLast: Boolean) {
    val accent = KerkidaTheme.colors.accent
    val yearColor = KerkidaTheme.colors.accentText
    val line = MaterialTheme.colorScheme.outlineVariant
    val big = moment.kind == MomentKind.FOUNDED || moment.kind == MomentKind.FIRST_TITLE
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.width(2.dp).fillMaxHeight()) {
                val top = if (isFirst) 12.dp.toPx() else 0f
                val bottom = if (isLast) 12.dp.toPx() else size.height
                drawLine(line, Offset(size.width / 2, top), Offset(size.width / 2, bottom), 2.dp.toPx())
            }
            Box(
                Modifier
                    .padding(top = if (big) 4.dp else 6.dp)
                    .size(if (big) 16.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (big) accent else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (!big) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            Text(
                moment.year.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = yearColor,
            )
            Text(momentText(moment), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Honours(items: List<String>) {
    SectionCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row {
                    Text("•", color = KerkidaTheme.colors.accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlaceCard(place: ClubPlace, profiles: ClubProfiles) {
    val context = LocalContext.current
    SectionCard {
        Column(
            Modifier.then(
                if (place.lat != null && place.lng != null) {
                    Modifier.clickable(role = Role.Button) {
                        val label = Uri.encode(place.name)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}($label)"))) }
                    }
                } else {
                    Modifier
                },
            ),
        ) {
            if (place.photos.isNotEmpty()) PhotoCarousel(place.photos, place.name, profiles)
            Column(Modifier.padding(12.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium)
                place.about?.let { about ->
                    Spacer(Modifier.height(4.dp))
                    Text(about, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Swipe between photos. The dots appear only when there is more than one. */
@Composable
private fun PhotoCarousel(photos: List<String>, name: String, profiles: ClubProfiles) {
    val pager = rememberPagerState { photos.size }
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(220.dp)) { page ->
            val url = photos[page]
            val photo by produceState<Bitmap?>(null, url) { value = profiles.photo(url) }
            Box(Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                photo?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
            }
        }
        if (photos.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                photos.indices.forEach { index ->
                    Box(
                        Modifier
                            .size(if (index == pager.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (index == pager.currentPage) Color.White else Color(0x99FFFFFF)),
                    )
                }
            }
        }
    }
}
