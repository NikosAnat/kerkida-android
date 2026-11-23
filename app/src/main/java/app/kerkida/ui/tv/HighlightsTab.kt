package app.kerkida.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.DataException
import app.kerkida.data.FavouriteKind
import app.kerkida.data.model.Match
import app.kerkida.features.Feature
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.highlights.HighlightLogic
import app.kerkida.highlights.HighlightVideo
import app.kerkida.highlights.LinkedHighlight
import app.kerkida.highlights.OfficialChannels
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.shield.rememberShieldedView
import app.kerkida.ui.theme.KerkidaIcons
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAYS_BACK = 4L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsTab(onMatch: (String) -> Unit) {
    val container = appContainer()
    val repository = container.highlights
    val videos by repository.videos.collectAsStateWithLifecycle()
    val refreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val provider by container.provider.collectAsStateWithLifecycle()
    val favourites by remember(provider.info.id) { container.favourites.favourites(provider.info.id) }
        .collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var matches by remember(provider.info.id) { mutableStateOf<List<Match>>(emptyList()) }
    var matchesReady by remember(provider.info.id) { mutableStateOf(false) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    // Until we know which match each video is, none may show a title or thumbnail that could spoil it.
    val shieldOn = isFeatureOn(Feature.SPOILER_SHIELD)
    val staySafe = shieldOn && !matchesReady

    LaunchedEffect(Unit) { repository.refresh() }
    LaunchedEffect(provider.info.id) {
        val today = LocalDate.now()
        for (back in 0..DAYS_BACK) {
            try {
                val day = provider.matchDay(today.minusDays(back)).lastOrNull()
                if (day != null) matches = (matches + day.matches).distinctBy { it.id }
            } catch (error: CancellationException) {
                throw error
            } catch (_: DataException) {
                // Videos still show, just without a link to their match.
            }
        }
        matchesReady = true
    }

    val myTeams = remember(favourites) { favourites.filter { it.favouriteKind == FavouriteKind.TEAM }.map { it.id }.toSet() }
    val linked by produceState(emptyList<LinkedHighlight>(), videos, matches) {
        value = withContext(Dispatchers.Default) { HighlightLogic.link(videos, matches) }
    }
    val visible = linked.filter { item ->
        (source == null || item.video.channelId == source) &&
            (!mineOnly || item.match?.let { it.home.id in myTeams || it.away.id in myTeams } == true)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(mineOnly, { mineOnly = !mineOnly }, { Text(stringResource(R.string.highlights_my_teams)) })
            OfficialChannels.forEach { channel ->
                FilterChip(
                    source == channel.id,
                    { source = if (source == channel.id) null else channel.id },
                    { Text(channel.name) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { repository.refresh(force = true) } },
            modifier = Modifier.weight(1f),
        ) {
            if (visible.isEmpty()) {
                MessageView(
                    KerkidaIcons.Tv,
                    stringResource(if (refreshing) R.string.loading else R.string.highlights_empty_title),
                    stringResource(R.string.highlights_empty_body),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.video.videoId }) { item -> HighlightCard(item, staySafe, onMatch) }
                }
            }
        }
    }
}

/** The official videos of one finished match, for its own page. */
@Composable
fun MatchHighlights(videos: List<HighlightVideo>, match: Match) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(12.dp),
    ) {
        videos.forEach { video ->
            HighlightCard(LinkedHighlight(video, match), staySafe = false, onMatch = {}, showMatchLink = false)
        }
    }
}

@Composable
private fun HighlightCard(
    item: LinkedHighlight,
    staySafe: Boolean,
    onMatch: (String) -> Unit,
    showMatchLink: Boolean = true,
) {
    val match = item.match
    // A video of a hidden match gets a neutral title and no thumbnail, which often shows the score.
    val hidden = staySafe || match?.let { rememberShieldedView(it) }?.let { it.isShielded && !it.showScore } == true
    val uri = LocalUriHandler.current
    val title = when {
        hidden && match != null -> stringResource(R.string.highlights_neutral, match.home.name, match.away.name)
        hidden -> stringResource(R.string.tv_tab_highlights)
        else -> item.video.title
    }
    val date = remember(item.video.publishedMs) {
        Instant.ofEpochMilli(item.video.publishedMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d/M, HH:mm"))
    }
    SectionCard {
        Column(Modifier.clickable(role = Role.Button) { uri.openUri(item.video.url) }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF0B1F4B)),
                contentAlignment = Alignment.Center,
            ) {
                if (!hidden && item.video.thumbnail != null) {
                    AsyncImage(
                        model = item.video.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(KerkidaIcons.EyeOff, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.highlights_source, item.video.channelName, date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (match != null && showMatchLink) {
                    TextButton(onClick = { onMatch(match.id) }) { Text(stringResource(R.string.highlights_open_match)) }
                }
            }
        }
    }
}
