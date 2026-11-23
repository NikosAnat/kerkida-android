package app.kerkida.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.stadium.Stadium
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Place(val name: String, val lat: Double, val lng: Double)

data class NearbyMatch(val match: Match, val stadium: Stadium, val km: Double)

/** Quick choices for anyone who prefers not to share their location. */
val GreekPlaces = listOf(
    Place("Πάτρα", 38.2466, 21.7346), Place("Αίγιο", 38.2505, 22.0852), Place("Αθήνα", 37.9838, 23.7275),
    Place("Πειραιάς", 37.9429, 23.6469), Place("Θεσσαλονίκη", 40.6401, 22.9444), Place("Ηράκλειο", 35.3387, 25.1442), Place("Λάρισα", 39.6390, 22.4191),
    Place("Βόλος", 39.3622, 22.9420), Place("Ιωάννινα", 39.6650, 20.8537), Place("Τρίπολη", 37.5089, 22.3794),
    Place("Καλαμάτα", 37.0389, 22.1142), Place("Αγρίνιο", 38.6214, 21.4078), Place("Χανιά", 35.5138, 24.0180),
    Place("Ρόδος", 36.4341, 28.2176), Place("Ξάνθη", 41.1349, 24.8880), Place("Λιβαδειά", 38.4361, 22.8750),
)

object NearbyLogic {
    private const val EARTH_KM = 6371.0

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_KM * asin(sqrt(a))
    }

    /** Games still to come, or running now, at grounds within the radius. Soonest first. */
    fun filter(
        matches: List<Match>,
        place: Place,
        radiusKm: Int,
        now: Instant,
        stadiumOf: (Match) -> Stadium?,
    ): List<NearbyMatch> = matches.asSequence()
        .distinctBy { it.id }
        .filter { it.status != MatchStatus.FINISHED && it.status != MatchStatus.CANCELLED && it.status != MatchStatus.POSTPONED }
        .filter { game -> game.kickoff?.let { !it.isBefore(now.minusSeconds(3 * 3600)) } == true }
        .mapNotNull { game ->
            val stadium = stadiumOf(game) ?: return@mapNotNull null
            val lat = stadium.lat ?: return@mapNotNull null
            val lng = stadium.lng ?: return@mapNotNull null
            val km = distanceKm(place.lat, place.lng, lat, lng)
            if (km <= radiusKm) NearbyMatch(game, stadium, km) else null
        }
        .sortedWith(compareBy({ it.match.kickoff }, { it.km }))
        .toList()
}

/** A rough, one-off position. It is only used on the phone and never stored or sent anywhere. */
@SuppressLint("MissingPermission")
suspend fun currentPlace(context: Context, label: String): Place? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = manager.getProviders(true)
    val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    val fresh = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull()
    val location: Location? = fresh?.let { provider ->
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                val executor = Executors.newSingleThreadExecutor()
                continuation.invokeOnCancellation {
                    signal.cancel()
                    executor.shutdown()
                }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { result ->
                    executor.shutdown()
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }
    } ?: known
    return location?.let { Place(label, it.latitude, it.longitude) }
}
