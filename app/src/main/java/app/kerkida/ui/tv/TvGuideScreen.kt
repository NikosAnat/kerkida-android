package app.kerkida.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.broadcast.AccessType
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.features.Feature
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.tv.TvEvent
import app.kerkida.tv.TvItem
import app.kerkida.tv.TvLogic
import app.kerkida.tv.TvSport
import app.kerkida.tv.TvStatus
import app.kerkida.ui.broadcast.accessLabel
import app.kerkida.ui.common.BrandHeader
import app.kerkida.ui.common.Crest
import app.kerkida.ui.common.SportBadge
import app.kerkida.ui.common.tvSportIcon
import app.kerkida.ui.common.tvSportLabel
import app.kerkida.ui.common.LoadingView
import app.kerkida.ui.common.MessageView
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.common.updatedText
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private data class GuideRow(val event: TvEvent, val linked: Match?, val homeCrest: String?, val awayCrest: String?)

/** The TV tab: the programme, plus official highlights. */
@Composable
fun TvRootScreen(onMatch: (String) -> Unit) {
    val highlightsOn = isFeatureOn(Feature.HIGHLIGHTS)
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        BrandHeader(title = stringResource(R.string.tv_title))
        if (highlightsOn) {
            PrimaryTabRow(selectedTabIndex = page) {
                Tab(page == 0, { page = 0 }, text = { Text(stringResource(R.string.tv_tab_guide)) })
                Tab(page == 1, { page = 1 }, text = { Text(stringResource(R.string.tv_tab_highlights)) })
            }
        }
        if (highlightsOn && page == 1) HighlightsTab(onMatch) else TvGuideScreen(onMatch)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvGuideScreen(onMatch: (String) -> Unit) {
    val container = appContainer()
    val tv = container.tv
    val all by tv.items.collectAsStateWithLifecycle()
    val refreshing by tv.isRefreshing.collectAsStateWithLifecycle()
    val failed by tv.failed.collectAsStateWithLifecycle()
    val updatedAt by tv.updatedAt.collectAsStateWithLifecycle()
    val favourites by tv.favourites.collectAsStateWithLifecycle()
    val owned by container.broadcasts.subscriptions.collectAsStateWithLifecycle()
    val provider by container.provider.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val today = remember(nowMs / 3_600_000) { LocalDate.now(ZoneId.systemDefault()) }
    var day by rememberSaveable { mutableStateOf(today.toString()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sport by rememberSaveable { mutableStateOf<TvSport?>(null) }
    var liveOnly by rememberSaveable { mutableStateOf(false) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    var hidePast by rememberSaveable { mutableStateOf(true) }
    var dayMatches by remember(provider.info.id) { mutableStateOf<Map<String, List<Match>>>(emptyMap()) }

    LaunchedEffect(Unit) { tv.refresh() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    // Links listings to their match pages. The guide works fine without this.
    LaunchedEffect(day, provider.info.id) {
        if (day in dayMatches) return@LaunchedEffect
        try {
            val date = LocalDate.parse(day)
            val latest = provider.matchDay(date).lastOrNull()
            if (latest != null) dayMatches = dayMatches + (day to latest.matches)
        } catch (error: CancellationException) {
            throw error
        } catch (_: DataException) {
            // Listings stay visible, just without a link.
        }
    }

    val days = remember(all, today) {
        (all.map { it.date } + today.toString()).distinct().filter { it >= today.minusDays(1).toString() }.sorted()
    }
    val needle = remember(query) { TvLogic.plain(query.trim()) }
    val visible = remember(all, day, needle, sport, liveOnly, mineOnly, favouritesOnly, hidePast, nowMs, owned, favourites) {
        all.filter { item ->
            val status = TvLogic.statusOf(item, nowMs)
            (if (needle.isEmpty() && !liveOnly) item.date == day else true) &&
                (sport == null || item.sport == sport) &&
                (!liveOnly || status == TvStatus.LIVE) &&
                (!hidePast || status != TvStatus.PAST || day != today.toString() || needle.isNotEmpty()) &&
                (!favouritesOnly || item.key in favourites) &&
                (!mineOnly || TvLogic.channelIdOf(item.channel).let { it in owned || TvLogic.accessOf(it) == AccessType.FREE }) &&
                (needle.isEmpty() || TvLogic.plain("${item.title} ${item.competition} ${item.channel}").contains(needle))
        }
    }

    // Grouping listings and finding crests is real work, so it never runs on the screen's thread.
    val directory = container.teamDirectory
    val rows by produceState(emptyList<GuideRow>(), visible, dayMatches) {
        value = withContext(Dispatchers.Default) {
            TvLogic.groupEvents(visible).map { event ->
                val item = event.item
                val linked = dayMatches[item.date]?.let { tv.matchFor(item, it) }
                val teams = TvLogic.splitTeams(item.title)
                GuideRow(
                    event = event,
                    linked = linked,
                    homeCrest = linked?.home?.badgeUrl ?: teams?.let { directory.crest(it.first, item.sport) },
                    awayCrest = linked?.away?.badgeUrl ?: teams?.let { directory.crest(it.second, item.sport) },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DayStrip(days, day, today) { day = it }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            placeholder = { Text(stringResource(R.string.tv_search)) },
            leadingIcon = { Icon(KerkidaIcons.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(KerkidaIcons.Close, stringResource(R.string.action_clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(liveOnly, { liveOnly = !liveOnly }, { Text(stringResource(R.string.tv_live_now)) })
            FilterChip(mineOnly, { mineOnly = !mineOnly }, { Text(stringResource(R.string.tv_my_channels)) })
            FilterChip(favouritesOnly, { favouritesOnly = !favouritesOnly }, { Text(stringResource(R.string.filter_favourites)) })
            FilterChip(!hidePast, { hidePast = !hidePast }, { Text(stringResource(R.string.tv_show_past)) })
            TvSport.entries.filter { option -> option == sport || all.any { it.sport == option && it.date == day } }.forEach { option ->
                FilterChip(
                    sport == option,
                    { sport = if (sport == option) null else option },
                    { Text(tvSportLabel(option)) },
                    leadingIcon = { Icon(tvSportIcon(option), null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { tv.refresh(force = true) } },
            modifier = Modifier.weight(1f),
        ) {
            when {
                all.isEmpty() && refreshing -> LoadingView()
                all.isEmpty() && failed -> MessageView(
                    KerkidaIcons.Offline,
                    stringResource(R.string.tv_failed_title),
                    stringResource(R.string.tv_failed_body),
                    primaryLabel = stringResource(R.string.action_retry),
                    onPrimary = { scope.launch { tv.refresh(force = true) } },
                )
                visible.isEmpty() -> MessageView(
                    KerkidaIcons.Tv,
                    stringResource(R.string.tv_empty_title),
                    stringResource(R.string.tv_empty_body),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (day == today.toString() && needle.isEmpty() && !liveOnly) {
                        // Always present, so the list stays at the top when the picks arrive.
                        item(key = "tonight") { TonightSection(dayMatches[day].orEmpty(), onMatch) }
                    }
                    item(key = "list") {
                        SectionCard {
                            rows.forEachIndexed { index, row ->
                                val event = row.event
                                val item = event.item
                                val linked = row.linked
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TvRow(
                                    item = item,
                                    homeCrest = row.homeCrest,
                                    awayCrest = row.awayCrest,
                                    channels = event.channels,
                                    status = TvLogic.statusOf(item, nowMs),
                                    showDate = needle.isNotEmpty() || liveOnly,
                                    owned = owned,
                                    isFavourite = item.key in favourites,
                                    onFavourite = { tv.toggleFavourite(item.key) },
                                    onClick = linked?.let { match -> { onMatch(match.id) } },
                                )
                            }
                        }
                    }
                    if (updatedAt > 0) {
                        item(key = "updated") {
                            Text(
                                updatedText(Instant.ofEpochMilli(updatedAt)) + " · " + stringResource(R.string.tv_sources),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The whole day's TV programme, including games that have no match page in the app. */
@Composable
fun TvDayList(
    date: LocalDate,
    matches: List<Match>,
    onMatch: (String) -> Unit,
    sport: app.kerkida.data.model.Sport? = null,
) {
    val container = appContainer()
    val tv = container.tv
    val all by tv.items.collectAsStateWithLifecycle()
    val refreshing by tv.isRefreshing.collectAsStateWithLifecycle()
    val favourites by tv.favourites.collectAsStateWithLifecycle()
    val owned by container.broadcasts.subscriptions.collectAsStateWithLifecycle()
    val directory = container.teamDirectory
    val nowMs = remember(all, date) { System.currentTimeMillis() }
    LaunchedEffect(Unit) { tv.refresh() }
    val dayRows by produceState(emptyList<GuideRow>(), all, date, matches) {
        value = withContext(Dispatchers.Default) {
            TvLogic.groupEvents(all.filter { it.date == date.toString() }).map { event ->
                val item = event.item
                val linked = tv.matchFor(item, matches)
                val teams = TvLogic.splitTeams(item.title)
                GuideRow(
                    event = event,
                    linked = linked,
                    homeCrest = linked?.home?.badgeUrl ?: teams?.let { directory.crest(it.first, item.sport) },
                    awayCrest = linked?.away?.badgeUrl ?: teams?.let { directory.crest(it.second, item.sport) },
                )
            }
        }
    }
    val rows = remember(dayRows, sport) {
        if (sport == null) dayRows else dayRows.filter { it.event.item.sport == TvLogic.tvSportOf(sport) }
    }
    if (rows.isEmpty()) {
        if (refreshing) LoadingView() else MessageView(
            KerkidaIcons.Tv,
            stringResource(R.string.tv_empty_title),
            stringResource(R.string.tv_empty_body),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "tv-day-$date") {
            SectionCard {
                rows.forEachIndexed { index, row ->
                    val item = row.event.item
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TvRow(
                        item = item,
                        homeCrest = row.homeCrest,
                        awayCrest = row.awayCrest,
                        channels = row.event.channels,
                        status = TvLogic.statusOf(item, nowMs),
                        showDate = false,
                        owned = owned,
                        isFavourite = item.key in favourites,
                        onFavourite = { tv.toggleFavourite(item.key) },
                        onClick = row.linked?.let { match -> { onMatch(match.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayStrip(days: List<String>, selected: String, today: LocalDate, onSelect: (String) -> Unit) {
    val colors = KerkidaTheme.colors
    val locale = currentLocale()
    Surface(color = colors.brandBar, contentColor = colors.onBrandBar) {
        LazyRow(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(days, key = { it }) { value ->
                val date = LocalDate.parse(value)
                val isSelected = value == selected
                val label = when (date) {
                    today -> stringResource(R.string.date_today)
                    today.minusDays(1) -> stringResource(R.string.date_yesterday)
                    today.plusDays(1) -> stringResource(R.string.date_tomorrow)
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                }
                val textColor = if (isSelected) MaterialTheme.colorScheme.scrim else colors.onBrandBar
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            if (isSelected) colors.accent else colors.onBrandBar.copy(alpha = 0.1f),
                            MaterialTheme.shapes.small,
                        )
                        .clickable(role = Role.Tab) { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
                    Text(
                        date.format(DateTimeFormatter.ofPattern("dd/MM", locale)),
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

private fun networkColor(channelId: String): Color = when (channelId) {
    "nova" -> Color(0xFF3B82F6)
    "magenta" -> Color(0xFFE5398D)
    "eurosport" -> Color(0xFFF59E0B)
    "dazn" -> Color(0xFF9CA3AF)
    "ant1plus" -> Color(0xFF8B5CF6)
    "other" -> Color(0xFF8B93A5)
    else -> Color(0xFF00B37A)
}

@Composable
private fun TvRow(
    item: TvItem,
    homeCrest: String?,
    awayCrest: String?,
    channels: List<String>,
    status: TvStatus,
    showDate: Boolean,
    owned: Set<String>,
    isFavourite: Boolean,
    onFavourite: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val colors = KerkidaTheme.colors
    // The channel the person can actually use comes first.
    val ordered = channels.sortedBy { name ->
        val id = TvLogic.channelIdOf(name)
        if (id in owned) 0 else if (TvLogic.accessOf(id) == AccessType.FREE) 1 else 2
    }
    val channelId = TvLogic.channelIdOf(ordered.first())
    val isOwned = channelId in owned
    val start = Instant.ofEpochMilli(item.startMs).atZone(ZoneId.systemDefault())
    val teams = remember(item.title) { TvLogic.splitTeams(item.title) }
    val isLive = status == TvStatus.LIVE
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SportBadge(tvSportIcon(item.sport), tvSportLabel(item.sport))
            Spacer(Modifier.width(6.dp))
            if (teams != null) {
                Text(
                    teams.first,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Crest(homeCrest, teams.first, 26.dp)
                Spacer(Modifier.width(6.dp))
                TimePill(start.format(DateTimeFormatter.ofPattern("HH:mm")), isLive, if (showDate) start.format(DateTimeFormatter.ofPattern("dd/MM")) else null)
                Spacer(Modifier.width(6.dp))
                Crest(awayCrest, teams.second, 26.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    teams.second,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TimePill(start.format(DateTimeFormatter.ofPattern("HH:mm")), isLive, if (showDate) start.format(DateTimeFormatter.ofPattern("dd/MM")) else null)
                Spacer(Modifier.width(8.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onFavourite, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isFavourite) KerkidaIcons.StarFilled else KerkidaIcons.Star,
                    stringResource(if (isFavourite) R.string.action_favourite_remove else R.string.action_favourite_add),
                    tint = if (isFavourite) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp, end = 8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(networkColor(channelId)),
            )
            Spacer(Modifier.width(5.dp))
            val access = TvLogic.accessOf(channelId)
            val suffix = if (isOwned) stringResource(R.string.tv_you_have) else accessLabel(access)
            Text(
                ordered.joinToString(", ") + " · $suffix",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOwned || access == AccessType.FREE) colors.win else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status != TvStatus.PAST) {
            app.kerkida.ui.broadcast.NovaWatchButton(
                ordered,
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 7.dp, end = 8.dp),
            )
        }
        if (item.competition.isNotBlank()) {
            Text(
                item.competition,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun TimePill(time: String, isLive: Boolean, date: String?) {
    val colors = KerkidaTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isLive) colors.live else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                time,
                style = MaterialTheme.typography.titleSmall,
                color = if (isLive) colors.onLive else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        val label = if (isLive) stringResource(R.string.status_live) else date
        label?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (isLive) colors.live else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
