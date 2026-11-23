package app.kerkida.ui.match

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.TeamRef
import app.kerkida.fantasy.LineupRatings
import app.kerkida.fantasy.PlayerRating
import app.kerkida.fantasy.Position
import app.kerkida.fantasy.SquadPlayer
import app.kerkida.tv.TvLogic
import app.kerkida.ui.common.Crest
import java.util.Locale

data class PitchPlayer(
    val name: String,
    val jersey: String?,
    val positionCode: String?,
    val position: Position,
    val rating: PlayerRating? = null,
    val photoUrl: String? = null,
)

data class PitchTeam(val team: TeamRef, val formation: String?, val lines: List<List<PitchPlayer>>)

/** Places a starting eleven into lines from its formation and each player's position code. */
object FormationLayout {
    fun depth(code: String?, position: Position): Int {
        val value = code.orEmpty().uppercase()
        return when {
            position == Position.GKP || value == "G" || value == "GK" -> 0
            value.startsWith("CD") || value == "LB" || value == "RB" || value.endsWith("WB") -> 1
            value.startsWith("DM") -> 2
            value.startsWith("AM") -> 4
            value.startsWith("CF") || value == "F" || value == "LF" || value == "RF" || value == "ST" ||
                value == "LW" || value == "RW" -> 5
            value.startsWith("CM") || value == "LM" || value == "RM" || value == "M" -> 3
            position == Position.DEF -> 1
            position == Position.FWD -> 5
            else -> 3
        }
    }

    /** Negative is the player's left, positive his right. */
    fun lateral(code: String?): Int {
        val value = code.orEmpty().uppercase()
        return when {
            value.startsWith("L") -> -2
            value.startsWith("R") -> 2
            value.endsWith("-L") -> -1
            value.endsWith("-R") -> 1
            else -> 0
        }
    }

    fun parseFormation(formation: String?): List<Int>? {
        val numbers = formation?.split('-')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        return numbers.takeIf { it.size >= 2 && it.all { count -> count > 0 } }
    }

    /** Goalkeeper first, then each line from defence to attack, left to right. */
    fun lines(players: List<PitchPlayer>, formation: String?): List<List<PitchPlayer>> {
        if (players.isEmpty()) return emptyList()
        val keepers = players.filter { depth(it.positionCode, it.position) == 0 }.take(1)
        val outfield = (players - keepers.toSet()).sortedWith(
            compareBy({ depth(it.positionCode, it.position) }, { lateral(it.positionCode) }),
        )
        val counts = parseFormation(formation)?.takeIf { it.sum() == outfield.size }
        val groups = if (counts != null) {
            var from = 0
            counts.map { size -> outfield.subList(from, from + size).also { from += size } }
        } else {
            outfield.groupBy { depth(it.positionCode, it.position) }.toSortedMap().values.toList()
        }
        val ordered = groups.map { line -> line.sortedBy { lateral(it.positionCode) } }
        return (if (keepers.isEmpty()) ordered else listOf(keepers) + ordered)
    }

    fun displayFormation(lines: List<List<PitchPlayer>>): String? {
        val outfield = lines.drop(1).map { it.size }
        return outfield.takeIf { it.size >= 2 }?.joinToString("-")
    }

    fun surnameKey(name: String) = TvLogic.phonetic(name.trim().substringAfterLast(' '))

    fun fromLineup(players: List<LineupPlayer>, ratings: List<PlayerRating>, showPhotos: Boolean = false): List<PitchPlayer> {
        val byName = ratings.associateBy { surnameKey(it.name) }
        return players.filter { it.isStarter }.map { player ->
            PitchPlayer(
                name = player.name,
                jersey = player.jersey,
                positionCode = player.positionCode,
                position = LineupRatings.positionOf(player.positionCode),
                rating = byName[surnameKey(player.name)],
                photoUrl = (player.photoUrl ?: byName[surnameKey(player.name)]?.photoUrl).takeIf { showPhotos },
            )
        }
    }

    fun fromSquad(players: List<SquadPlayer>) = players.map {
        PitchPlayer(it.name, it.jersey, it.positionCode, it.position)
    }
}

private val Grass = Color(0xFF0E6B4C)
private val GrassDark = Color(0xFF0C6145)
private val PitchLine = Color(0x66FFFFFF)
private val HomeShirt = Color(0xFFFFFFFF)
private val AwayShirt = Color(0xFF0B1F4B)

/** Both elevens on one pitch: home attacking down, away attacking up. */
@Composable
fun MatchPitch(
    home: PitchTeam?,
    away: PitchTeam?,
    modifier: Modifier = Modifier,
    onPlayer: ((PlayerRating) -> Unit)? = null,
) {
    if (home == null && away == null) return
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Grass),
    ) {
        home?.let { TeamBanner(it) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (home != null && away != null) 620.dp else 340.dp),
        ) {
            PitchMarkings()
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                home?.let { team ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                        // Home attacks down the screen, so the player's left is on the viewer's right.
                        team.lines.forEach { line ->
                            PlayerLine(line.reversed(), HomeShirt, AwayShirt, onPlayer)
                        }
                    }
                }
                away?.let { team ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                        team.lines.reversed().forEach { line ->
                            PlayerLine(line, AwayShirt, HomeShirt, onPlayer)
                        }
                    }
                }
            }
        }
        away?.let { TeamBanner(it) }
    }
}

@Composable
private fun TeamBanner(team: PitchTeam) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GrassDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(team.team.badgeUrl, team.team.name, 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            app.kerkida.ui.common.clubName(team.team.name),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        (team.formation ?: FormationLayout.displayFormation(team.lines))?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun PitchMarkings() {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(1.5.dp.toPx())
        val inset = 6.dp.toPx()
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        drawRect(PitchLine, Offset(inset, inset), Size(width, height), style = stroke)
        drawLine(PitchLine, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), 1.5.dp.toPx())
        drawCircle(PitchLine, radius = width * 0.14f, center = Offset(size.width / 2, size.height / 2), style = stroke)
        val boxWidth = width * 0.56f
        val boxHeight = height * 0.11f
        val boxLeft = (size.width - boxWidth) / 2
        drawRect(PitchLine, Offset(boxLeft, inset), Size(boxWidth, boxHeight), style = stroke)
        drawRect(PitchLine, Offset(boxLeft, size.height - inset - boxHeight), Size(boxWidth, boxHeight), style = stroke)
    }
}

@Composable
private fun PlayerLine(
    line: List<PitchPlayer>,
    shirt: Color,
    number: Color,
    onPlayer: ((PlayerRating) -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        line.forEach { player -> PlayerDot(player, shirt, number, onPlayer, Modifier.weight(1f)) }
    }
}

@Composable
private fun PlayerDot(
    player: PitchPlayer,
    shirt: Color,
    number: Color,
    onPlayer: ((PlayerRating) -> Unit)?,
    modifier: Modifier,
) {
    val rating = player.rating
    val photoShown = androidx.compose.runtime.remember(player.photoUrl) { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier.then(
            if (rating != null && onPlayer != null) {
                Modifier.clickable(role = Role.Button) { onPlayer(rating) }
            } else {
                Modifier
            },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(shirt),
                contentAlignment = Alignment.Center,
            ) {
                if (!photoShown.value) {
                    Text(player.jersey.orEmpty(), color = number, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                player.photoUrl?.let { url ->
                    coil3.compose.AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        onSuccess = { photoShown.value = true },
                        onError = { photoShown.value = false },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            rating?.let {
                Box(
                    Modifier
                        .offset(x = 19.dp, y = (-11).dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(ratingBadgeColor(it.breakdown.rating))
                        .padding(horizontal = 3.dp),
                ) {
                    Text(
                        String.format(Locale.US, "%.1f", it.breakdown.rating),
                        color = Color(0xFF0B1F4B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            player.name.trim().substringAfterLast(' '),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 1.dp, end = 1.dp),
        )
    }
}

fun ratingBadgeColor(value: Double): Color = when {
    value >= 7.0 -> Color(0xFF3FE0A8)
    value >= 6.0 -> Color(0xFFB9F0DC)
    value >= 5.0 -> Color(0xFFF2B134)
    else -> Color(0xFFFF8A73)
}
