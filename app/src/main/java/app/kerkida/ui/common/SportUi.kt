package app.kerkida.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kerkida.R
import app.kerkida.data.model.Sport
import app.kerkida.tv.TvSport
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.theme.KerkidaTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** The small gesture each sport makes when it is chosen. */
private enum class SportMotion { ROLL, BOUNCE, SWING, WAVE, PUNCH, FLIP }

private fun motionOf(sport: Sport) = when (sport) {
    Sport.FOOTBALL, Sport.BASEBALL, Sport.VOLLEYBALL -> SportMotion.ROLL
    Sport.BASKETBALL -> SportMotion.BOUNCE
    Sport.TENNIS, Sport.HOCKEY, Sport.LACROSSE -> SportMotion.SWING
    Sport.MOTOR, Sport.GOLF -> SportMotion.WAVE
    Sport.MMA -> SportMotion.PUNCH
    Sport.RUGBY, Sport.AMERICAN_FOOTBALL, Sport.AUSSIE_RULES -> SportMotion.FLIP
}

/** Plays once when the sport becomes selected, and never on first display or while scrolling. */
@Composable
private fun AnimatedSportIcon(sport: Sport, isSelected: Boolean, tint: Color) {
    val motion = remember(sport) { motionOf(sport) }
    val clock = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(isSelected) }
    LaunchedEffect(isSelected) {
        if (isSelected && !wasSelected) {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(if (motion == SportMotion.ROLL) 760 else 680, easing = LinearEasing))
        }
        wasSelected = isSelected
    }
    Icon(
        sportIcon(sport),
        null,
        tint = tint,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer {
                val p = clock.value
                val fade = 1f - p
                when (motion) {
                    SportMotion.ROLL -> {
                        rotationZ = 360f * FastOutSlowInEasing.transform(p)
                        translationY = -2.5.dp.toPx() * sin(PI.toFloat() * p)
                    }
                    SportMotion.BOUNCE -> {
                        val hop = abs(sin(2f * PI.toFloat() * p)) * fade
                        translationY = -6.dp.toPx() * hop
                        // The ball flattens a little each time it meets the floor.
                        val squash = (1f - hop).pow(6) * fade * 0.16f
                        scaleY = 1f - squash
                        scaleX = 1f + squash
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    SportMotion.SWING -> {
                        rotationZ = -30f * sin(2.5f * PI.toFloat() * p) * fade.pow(1.5f)
                        transformOrigin = TransformOrigin(0.86f, 0.86f)
                    }
                    SportMotion.WAVE -> {
                        val ripple = sin(5f * PI.toFloat() * p) * fade
                        rotationZ = 7f * ripple
                        scaleX = 1f - 0.08f * abs(ripple)
                        transformOrigin = TransformOrigin(0.2f, 0.9f)
                    }
                    SportMotion.PUNCH -> {
                        val hit = sin(PI.toFloat() * p.pow(0.6f)) * fade.pow(0.5f)
                        translationX = 3.dp.toPx() * hit
                        scaleX = 1f + 0.16f * hit
                        scaleY = 1f + 0.16f * hit
                    }
                    SportMotion.FLIP -> {
                        rotationY = 360f * FastOutSlowInEasing.transform(p)
                        cameraDistance = 10f * density
                    }
                }
            },
    )
}

fun sportIcon(sport: Sport): ImageVector = when (sport) {
    Sport.FOOTBALL -> KerkidaIcons.Matches
    Sport.BASKETBALL -> KerkidaIcons.Basketball
    Sport.TENNIS -> KerkidaIcons.Tennis
    Sport.MOTOR -> KerkidaIcons.RaceFlag
    Sport.AMERICAN_FOOTBALL -> KerkidaIcons.AmericanFootball
    Sport.HOCKEY -> KerkidaIcons.Hockey
    Sport.BASEBALL -> KerkidaIcons.Baseball
    Sport.MMA -> KerkidaIcons.Glove
    Sport.VOLLEYBALL -> KerkidaIcons.Volleyball
    Sport.RUGBY -> KerkidaIcons.Rugby
    Sport.GOLF -> KerkidaIcons.Golf
    Sport.LACROSSE -> KerkidaIcons.Lacrosse
    Sport.AUSSIE_RULES -> KerkidaIcons.Rugby
}

fun tvSportIcon(sport: TvSport): ImageVector = when (sport) {
    TvSport.FOOTBALL -> KerkidaIcons.Matches
    TvSport.BASKETBALL -> KerkidaIcons.Basketball
    TvSport.TENNIS -> KerkidaIcons.Tennis
    TvSport.MOTOR -> KerkidaIcons.RaceFlag
    TvSport.VOLLEYBALL -> KerkidaIcons.Volleyball
    TvSport.HANDBALL -> KerkidaIcons.Handball
    TvSport.WATER_POLO -> KerkidaIcons.WaterPolo
    TvSport.RUGBY -> KerkidaIcons.Rugby
    TvSport.AMERICAN_FOOTBALL -> KerkidaIcons.AmericanFootball
    TvSport.HOCKEY -> KerkidaIcons.Hockey
    TvSport.BASEBALL -> KerkidaIcons.Baseball
    TvSport.MMA -> KerkidaIcons.Glove
    TvSport.GOLF -> KerkidaIcons.Golf
    TvSport.OTHER -> KerkidaIcons.Whistle
}

@Composable
fun sportLabel(sport: Sport): String = stringResource(
    when (sport) {
        Sport.FOOTBALL -> R.string.tv_sport_football
        Sport.BASKETBALL -> R.string.tv_sport_basketball
        Sport.TENNIS -> R.string.tv_sport_tennis
        Sport.MOTOR -> R.string.tv_sport_motor
        Sport.AMERICAN_FOOTBALL -> R.string.sport_american_football
        Sport.HOCKEY -> R.string.sport_hockey
        Sport.BASEBALL -> R.string.sport_baseball
        Sport.MMA -> R.string.sport_mma
        Sport.VOLLEYBALL -> R.string.sport_volleyball
        Sport.RUGBY -> R.string.sport_rugby
        Sport.GOLF -> R.string.sport_golf
        Sport.LACROSSE -> R.string.sport_lacrosse
        Sport.AUSSIE_RULES -> R.string.sport_aussie
    },
)

@Composable
fun tvSportLabel(sport: TvSport): String = stringResource(
    when (sport) {
        TvSport.FOOTBALL -> R.string.tv_sport_football
        TvSport.BASKETBALL -> R.string.tv_sport_basketball
        TvSport.TENNIS -> R.string.tv_sport_tennis
        TvSport.MOTOR -> R.string.tv_sport_motor
        TvSport.VOLLEYBALL -> R.string.sport_volleyball
        TvSport.HANDBALL -> R.string.sport_handball
        TvSport.WATER_POLO -> R.string.sport_water_polo
        TvSport.RUGBY -> R.string.sport_rugby
        TvSport.AMERICAN_FOOTBALL -> R.string.sport_american_football
        TvSport.HOCKEY -> R.string.sport_hockey
        TvSport.BASEBALL -> R.string.sport_baseball
        TvSport.MMA -> R.string.sport_mma
        TvSport.GOLF -> R.string.sport_golf
        TvSport.OTHER -> R.string.tv_sport_other
    },
)

/** A small round badge with the sport's symbol. */
@Composable
fun SportBadge(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.66f))
    }
}

@Composable
fun SportStrip(sports: List<Sport>, selected: Sport, onSelect: (Sport) -> Unit) {
    if (sports.size < 2) return
    val colors = KerkidaTheme.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(sports, key = { it.name }) { sport ->
            val isSelected = sport == selected
            val tint = if (isSelected) Color(0xFF0B1F4B) else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) colors.accent else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(role = Role.Tab) { onSelect(sport) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                AnimatedSportIcon(sport, isSelected, tint)
                Text(sportLabel(sport), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
