package app.kerkida.ui.match

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kerkida.R
import app.kerkida.alerts.AlertNotifier
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.nearby.GreekPlaces
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.Place
import app.kerkida.planner.GroundTrip
import app.kerkida.stadium.Stadium
import app.kerkida.stadium.StadiumPack
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.shield.appContainer
import app.kerkida.ui.theme.KerkidaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The ground's photo and name. Tapping opens it on the map. */
@Composable
fun StadiumCard(match: Match) {
    val pack = appContainer().stadiums
    val context = LocalContext.current
    val stadium = remember(match.venue, match.home.id) { pack.find(match) } ?: return
    val photo by produceState<Bitmap?>(null, stadium) {
        value = withContext(Dispatchers.IO) { pack.photo(stadium) }
    }
    SectionCard {
        Column(
            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.stadium_open_map)) {
                val opened = StadiumPack.mapIntents(stadium).any { intent ->
                    try {
                        context.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
                if (!opened) return@clickable
            },
        ) {
            photo?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.stadium_photo, stadium.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(KerkidaIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stadium.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(stadium.city, stringResource(R.string.stadium_open_map)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(KerkidaIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if (stadium.photo != null) {
                Text(
                    stringResource(R.string.stadium_credit, stadium.author ?: "Wikimedia Commons", stadium.license.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
        if (stadium.lat != null && stadium.lng != null) TripRow(match, stadium)
    }
}

/** Directions, and a reminder that fires when it is time to leave. */
@Composable
private fun TripRow(match: Match, stadium: Stadium) {
    val context = LocalContext.current
    var reminder by rememberSaveable(match.id) { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("kerkida_nearby", Context.MODE_PRIVATE) }
    val from = remember {
        prefs.getString("name", null)?.let { Place(it, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lng", 0f).toDouble()) }
            ?: GreekPlaces.first { it.name == "Πάτρα" }
    }
    val km = remember(stadium) { NearbyLogic.distanceKm(from.lat, from.lng, stadium.lat!!, stadium.lng!!) }
    val tooLate = stringResource(R.string.trip_too_late)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            reminder = GroundTrip.remind(context, match, stadium, km)
                ?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("EEE HH:mm")) ?: tooLate
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
        TextButton(onClick = {
            GroundTrip.directions(stadium)?.let { intent ->
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No browser or map app on this phone.
                }
            }
        }) { Text(stringResource(R.string.trip_directions)) }
        if (match.status == MatchStatus.SCHEDULED) {
            TextButton(onClick = {
                if (AlertNotifier.canNotify(context)) {
                    reminder = GroundTrip.remind(context, match, stadium, km)
                        ?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("EEE HH:mm")) ?: tooLate
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text(reminder?.let { stringResource(R.string.trip_reminder_set, it) } ?: stringResource(R.string.trip_remind)) }
        }
    }
}
