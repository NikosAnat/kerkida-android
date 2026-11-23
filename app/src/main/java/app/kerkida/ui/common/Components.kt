package app.kerkida.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.R
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.isInPlay
import app.kerkida.ui.broadcast.BroadcastLine
import app.kerkida.ui.shield.ShieldEye
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.shield.isHiddenAfterKickoff
import app.kerkida.ui.shield.rememberShieldedView
import app.kerkida.ui.shield.shieldedStatus
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import coil3.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ShortDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

fun Instant.localTimeText(): String = atZone(ZoneId.systemDefault()).format(TimeFormat)

/** Kerkida's mark: two scarves folded into a K, on its navy tile. */
@Composable
fun KerkidaMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(22))
            .background(colorResource(R.color.kerkida_navy)),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun BrandHeader(
    title: String,
    modifier: Modifier = Modifier,
    showMark: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = KerkidaTheme.colors
    Surface(color = colors.brandBar, contentColor = colors.onBrandBar, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .height(58.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showMark) {
                // The logo stands alone; the name stays available to screen readers.
                KerkidaMark(Modifier.size(36.dp).semantics { contentDescription = title })
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            actions()
        }
    }
}

@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = KerkidaTheme.colors
    Surface(color = colors.brandBar, contentColor = colors.onBrandBar, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(KerkidaIcons.Back, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

@Composable
fun DemoBanner() {
    val colors = KerkidaTheme.colors
    Surface(color = colors.demo, contentColor = colors.onDemo, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(KerkidaIcons.Flask, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun FavouriteButton(isFavourite: Boolean, onToggle: () -> Unit, tint: Color? = null) {
    val accent = KerkidaTheme.colors.accent
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (isFavourite) KerkidaIcons.StarFilled else KerkidaIcons.Star,
            contentDescription = stringResource(
                if (isFavourite) R.string.action_favourite_remove else R.string.action_favourite_add,
            ),
            tint = if (isFavourite) accent else tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A club's name as the person reads it: one Greek name in Greek, the source's name otherwise. */
@Composable
fun clubName(name: String): String {
    val locale = currentLocale()
    return remember(name, locale) { if (locale.language == "el") app.kerkida.data.GreekClubNames.greek(name) else name }
}

@Composable
fun placeName(name: String): String {
    val locale = currentLocale()
    return remember(name, locale) { app.kerkida.data.GreekClubNames.place(name, locale) }
}

/** A badge supplied by the data source, or a neutral initial when there is none. */
@Composable
fun Crest(source: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val url = remember(source) { CrestOverrides.resolve(source) }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(size * 0.3f))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                    style = if (size >= 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The loading bar, drawn as the scarf from the logo: long green lengths, contrast bands and a fringe at its end.
 * With [progress] the scarf unrolls as work completes. Without it, a short scarf travels across.
 */
@Composable
fun ScarfProgress(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    val green = KerkidaTheme.colors.accent
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val band = if (isDark) Color.White else colorResource(R.color.kerkida_navy)
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val target = progress?.invoke()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    // Sources can finish in quick bursts, so the scarf eases towards the true value instead of jumping.
    val filled by animateFloatAsState(target ?: 0f, tween(1_600, easing = LinearOutSlowInEasing), label = "scarf-fill")
    val travel by rememberInfiniteTransition(label = "scarf").animateFloat(
        initialValue = 0f,
        // Two crossings per cycle, one for each of the logo's scarves.
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "scarf-travel",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics {
                progressBarRangeInfo = if (target != null) ProgressBarRangeInfo(target, 0f..1f) else ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val bodyTop = 2.dp.toPx()
        val bodyHeight = size.height - bodyTop * 2
        val fringe = 7.dp.toPx()
        drawRect(track, Offset(0f, bodyTop), Size(size.width, bodyHeight))
        val start: Float
        val end: Float
        if (target != null) {
            // Room is kept on both sides, so neither fringe is cut off by the screen's edge.
            start = fringe
            end = fringe + (size.width - fringe * 2) * filled
        } else {
            val length = size.width * 0.32f
            start = -length - fringe + (size.width + length + fringe * 2) * (travel % 1f)
            end = start + length
        }
        // Every second crossing is the other scarf: the contrast colour with green bands.
        val isOtherScarf = target == null && travel >= 1f
        val cloth = if (isOtherScarf) band else green
        val stripe = if (isOtherScarf) green else band
        if (end <= start) return@Canvas
        val long = 30.dp.toPx()
        val short = 8.dp.toPx()
        clipRect(left = start, right = end) {
            drawRect(cloth, Offset(start, bodyTop), Size(end - start, bodyHeight))
            // The bands are printed on the cloth: as the scarf unrolls they stay put and new ones appear.
            var x = start + long
            while (x < end) {
                drawRect(stripe, Offset(x, bodyTop), Size(short, bodyHeight))
                x += long + short
            }
        }
        listOf(start to -1f, end to 1f).forEach { (edge, outward) ->
            repeat(3) { index ->
                val y = bodyTop + bodyHeight * (index + 0.5f) / 3f
                val reach = fringe * if (index == 1) 0.9f else 0.7f
                drawLine(
                    cloth,
                    Offset(edge + outward * 1.dp.toPx(), y),
                    Offset(edge + outward * reach, y + (index - 1) * 1.5.dp.toPx()),
                    1.5.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }
}

/** A round player photo, or the player's initial when there is none. */
@Composable
fun PlayerPhoto(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
fun NoticeLine(text: String, icon: ImageVector = KerkidaIcons.Info, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun updatedText(fetchedAt: Instant): String {
    val minutes = Duration.between(fetchedAt, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.updated_now)
        minutes < 60 -> stringResource(R.string.updated_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(R.string.updated_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.updated_days, (minutes / (24 * 60)).toInt())
    }
}

@Composable
fun FreshnessFooter(fetchedAt: Instant, isStale: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isStale) NoticeLine(stringResource(R.string.stale_notice), KerkidaIcons.Offline)
        Text(
            text = updatedText(fetchedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier, rows: Int = 6) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "pulse",
    )
    val loadingLabel = stringResource(R.string.loading)
    Column(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = loadingLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        repeat(rows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
fun MessageView(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    /** Must be false inside a list, which already scrolls. */
    scrollable: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onPrimary) { Text(primaryLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun ErrorView(
    error: Throwable,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val (title, body) = when (error) {
        is DataException.Offline ->
            stringResource(R.string.error_offline_title) to stringResource(R.string.error_offline_body)
        is DataException.RateLimited ->
            stringResource(R.string.error_rate_title) to
                stringResource(R.string.error_rate_body, error.retryAfterSeconds.toInt())
        is DataException.NotFound ->
            stringResource(R.string.error_not_found_title) to stringResource(R.string.error_not_found_body)
        is DataException.BadData ->
            stringResource(R.string.error_bad_data_title) to stringResource(R.string.error_bad_data_body)
        else -> stringResource(R.string.error_server_title) to stringResource(R.string.error_server_body)
    }
    MessageView(
        icon = if (error is DataException.Offline) KerkidaIcons.Offline else KerkidaIcons.Info,
        title = title,
        body = body,
        modifier = modifier,
        primaryLabel = stringResource(R.string.action_retry),
        onPrimary = onRetry,
        scrollable = scrollable,
    )
}

@Composable
fun statusLabel(match: Match): String = when (match.status) {
    MatchStatus.LIVE -> match.statusDetail?.takeIf { match.league.sport != app.kerkida.data.model.Sport.FOOTBALL } ?: match.minute?.let { stringResource(R.string.status_minute, it) }
        ?: stringResource(R.string.status_live)
    MatchStatus.HALF_TIME -> stringResource(R.string.status_half_time)
    MatchStatus.FINISHED -> stringResource(R.string.status_finished)
    MatchStatus.POSTPONED -> stringResource(R.string.status_postponed)
    MatchStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    MatchStatus.SCHEDULED -> stringResource(R.string.status_scheduled)
    MatchStatus.UNKNOWN -> stringResource(R.string.status_unknown)
}

/** Home on the left, away on the right, with the score or kick-off time in a centre pill. */
@Composable
fun MatchRow(
    match: Match,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lists that span several days show the date under the score or time. */
    showDate: Boolean = false,
) {
    if (match.eventTitle != null) {
        EventRow(match, onClick, modifier)
        return
    }
    // With very large text two names cannot sit beside the score without breaking mid-word.
    if (LocalDensity.current.fontScale >= STACKED_ROW_SCALE) {
        StackedMatchRow(match, onClick, modifier, showDate)
        return
    }
    val colors = KerkidaTheme.colors
    val view = rememberShieldedView(match)
    val shown = view.match
    val inPlay = shown.status.isInPlay
    val hasScore = shown.homeScore != null && shown.awayScore != null
    val hiddenScore = isHiddenAfterKickoff(view, match)
    // A hidden match shows its date like any other, so the label cannot hint that it has ended.
    val dateText = match.kickoff
        ?.takeIf {
            showDate && (!view.showClock || shown.status == MatchStatus.SCHEDULED || shown.status == MatchStatus.FINISHED)
        }
        ?.atZone(ZoneId.systemDefault())
        ?.format(ShortDateFormat)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = clubName(match.home.name),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isWinner(shown.homeScore, shown.awayScore, shown)) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Crest(match.home.badgeUrl, match.home.name, 26.dp)
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 66.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (inPlay) colors.live else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        hasScore -> "${shown.homeScore} – ${shown.awayScore}"
                        hiddenScore -> "? – ?"
                        else -> match.kickoff?.localTimeText() ?: "–"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (inPlay) colors.onLive else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = dateText ?: shieldedStatus(view, match),
                style = MaterialTheme.typography.labelSmall,
                color = if (inPlay) colors.live else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Crest(match.away.badgeUrl, match.away.name, 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = clubName(match.away.name),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isWinner(shown.awayScore, shown.homeScore, shown)) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ShieldEye(match)
    }
    shown.resultText?.takeIf { match.league.sport != app.kerkida.data.model.Sport.FOOTBALL }?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
    BroadcastLine(match, Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

private const val STACKED_ROW_SCALE = 1.5f

@Composable
private fun StackedMatchRow(match: Match, onClick: () -> Unit, modifier: Modifier, showDate: Boolean) {
    val colors = KerkidaTheme.colors
    val view = rememberShieldedView(match)
    val shown = view.match
    val inPlay = shown.status.isInPlay
    val hasScore = shown.homeScore != null && shown.awayScore != null
    val dateText = match.kickoff
        ?.takeIf { showDate && (!view.showClock || shown.status == MatchStatus.SCHEDULED || shown.status == MatchStatus.FINISHED) }
        ?.atZone(ZoneId.systemDefault())
        ?.format(ShortDateFormat)
    @Composable
    fun Side(name: String, badge: String?, score: Int?, won: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crest(badge, name, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                clubName(name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasScore) {
                Text(
                    score.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (inPlay) colors.live else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Side(match.home.name, match.home.badgeUrl, shown.homeScore, isWinner(shown.homeScore, shown.awayScore, shown))
                Side(match.away.name, match.away.badgeUrl, shown.awayScore, isWinner(shown.awayScore, shown.homeScore, shown))
            }
            ShieldEye(match)
        }
        Text(
            listOfNotNull(
                match.kickoff?.takeIf { !hasScore && !isHiddenAfterKickoff(view, match) }?.localTimeText(),
                dateText ?: shieldedStatus(view, match),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (inPlay) colors.live else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BroadcastLine(match, Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

/** A race session or similar event, which has a title instead of two sides. */
@Composable
private fun EventRow(match: Match, onClick: () -> Unit, modifier: Modifier) {
    val view = rememberShieldedView(match)
    val colors = KerkidaTheme.colors
    val inPlay = view.match.status.isInPlay
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(60.dp)) {
            Text(
                match.kickoff?.localTimeText() ?: "–",
                style = MaterialTheme.typography.titleSmall,
                color = if (inPlay) colors.live else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                shieldedStatus(view, match),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(match.eventTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            view.match.resultText?.let {
                Text(stringResource(R.string.event_winner, it), style = MaterialTheme.typography.labelMedium, color = colors.win, maxLines = 1)
            }
            match.venue?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ShieldEye(match)
    }
}

private fun isWinner(own: Int?, other: Int?, match: Match): Boolean =
    match.status == MatchStatus.FINISHED && own != null && other != null && own > other

@Composable
fun StandingsTable(
    standings: Standings,
    onTeamClick: (String) -> Unit,
    highlightTeamIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val shield = appContainer().shield
    val shieldEntries by shield.entries.collectAsStateWithLifecycle()
    val hidesResult = remember(standings.league.id, shieldEntries) { shield.hidesTable(standings.league.id) }
    var showAnyway by remember(standings.league.id) { mutableStateOf(false) }
    if (hidesResult && !showAnyway) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            NoticeLine(stringResource(R.string.shield_table_hidden), KerkidaIcons.EyeOff)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showAnyway = true }) { Text(stringResource(R.string.shield_table_show)) }
        }
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (standings.isPartial) NoticeLine(stringResource(R.string.table_partial))
        // With large text the columns grow and the table scrolls sideways, so no digit is ever cut.
        val textScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH * textScale)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
        SectionCard(Modifier.width(tableWidth)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCell(stringResource(R.string.table_pos), 24.dp)
                Text(
                    stringResource(R.string.table_team),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                HeaderCell(stringResource(R.string.table_played), 26.dp)
                HeaderCell(stringResource(R.string.table_won), 24.dp)
                HeaderCell(stringResource(R.string.table_drawn), 24.dp)
                HeaderCell(stringResource(R.string.table_lost), 24.dp)
                HeaderCell(stringResource(R.string.table_gd), 34.dp)
                HeaderCell(stringResource(R.string.table_points), 30.dp)
            }
            standings.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StandingLine(row, row.team.id in highlightTeamIds) { onTeamClick(row.team.id) }
            }
        }
        }
        }
    }
}

private val TABLE_MIN_WIDTH = 340.dp

@Composable
private fun scaledWidth(width: Dp) = width * LocalDensity.current.fontScale.coerceAtLeast(1f)

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(scaledWidth(width)),
    )
}

@Composable
private fun StandingLine(row: StandingRow, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberCell(row.rank.toString(), 24.dp, bold = true)
        Crest(row.team.badgeUrl, row.team.name, 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            clubName(row.team.name),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        NumberCell(row.played.toString(), 26.dp)
        NumberCell(row.won.toString(), 24.dp)
        NumberCell(row.drawn.toString(), 24.dp)
        NumberCell(row.lost.toString(), 24.dp)
        NumberCell(if (row.goalDifference > 0) "+${row.goalDifference}" else row.goalDifference.toString(), 34.dp)
        NumberCell(row.points.toString(), 30.dp, bold = true)
    }
}

@Composable
private fun NumberCell(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(scaledWidth(width)),
    )
}

val ScreenPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
