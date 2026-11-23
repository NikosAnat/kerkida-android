package app.kerkida.nearby

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One ground on the map, with every upcoming game played there. */
data class StadiumPin(
    val key: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val games: List<NearbyMatch>,
)

object NearbyMapLogic {
    private const val EARTH_KM = 6371.0

    /** Games at the same spot share a pin, soonest first, even if sources spell the ground differently. */
    fun pins(results: List<NearbyMatch>): List<StadiumPin> = results
        .filter { it.stadium.lat != null && it.stadium.lng != null }
        .groupBy { String.format(Locale.US, "%.4f,%.4f", it.stadium.lat, it.stadium.lng) }
        .map { (key, games) ->
            val ordered = games.sortedBy { it.match.kickoff }
            StadiumPin(key, ordered.first().stadium.name, ordered.first().stadium.lat!!, ordered.first().stadium.lng!!, ordered)
        }

    /** The radius drawn on the map, as a closed ring of (lat, lng) points. */
    fun circle(lat: Double, lng: Double, radiusKm: Int, steps: Int = 72): List<Pair<Double, Double>> {
        val distance = radiusKm / EARTH_KM
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        return (0..steps).map { step ->
            val bearing = 2 * PI * (step % steps) / steps
            val pointLat = asin(sin(latRad) * cos(distance) + cos(latRad) * sin(distance) * cos(bearing))
            val pointLng = lngRad + atan2(sin(bearing) * sin(distance) * cos(latRad), cos(distance) - sin(latRad) * sin(pointLat))
            Math.toDegrees(pointLat) to Math.toDegrees(pointLng)
        }
    }
}
