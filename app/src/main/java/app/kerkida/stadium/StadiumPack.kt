package app.kerkida.stadium

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.kerkida.data.model.Match
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

@Serializable
data class Stadium(
    val name: String,
    val city: String? = null,
    val country: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val wikipedia: String? = null,
    val photo: String? = null,
    val author: String? = null,
    val license: String? = null,
    val source: String? = null,
)

@Serializable
data class StadiumIndex(
    val venues: Map<String, Stadium> = emptyMap(),
    val teams: Map<String, String> = emptyMap(),
)

/** Stadium photos and locations shipped inside the app, so they cost no requests or keys. */
class StadiumPack(
    private val context: Context,
    /** Positions of small grounds that have no entry in the pack. */
    private val localGround: (Match) -> Stadium? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val index: StadiumIndex by lazy {
        runCatching {
            context.assets.open("$FOLDER/stadiums.json").bufferedReader().use { json.decodeFromString<StadiumIndex>(it.readText()) }
        }.getOrDefault(StadiumIndex())
    }

    /** The named venue wins. The home team's ground is only assumed when no venue is given. */
    fun find(match: Match): Stadium? {
        val known = find(match.venue, match.home.id)
        return if (known?.lat != null) known else localGround(match) ?: known
    }

    fun find(venue: String?, homeTeamId: String?): Stadium? {
        if (!venue.isNullOrBlank()) return index.venues[key(venue)] ?: Stadium(name = venue)
        return homeTeamId?.let { index.teams[it] }?.let { index.venues[it] }
    }

    fun photo(stadium: Stadium): Bitmap? {
        val file = stadium.photo ?: return null
        return runCatching {
            context.assets.open("$FOLDER/photos/$file").use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    companion object {
        private const val FOLDER = "stadiums"

        fun key(name: String): String {
            val plain = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.replace(Regex("[^a-z0-9α-ω]+"), " ").trim()
        }

        /** Opens the phone's map app with no API key. Falls back to the web map. */
        fun mapIntents(stadium: Stadium): List<Intent> {
            val label = listOfNotNull(stadium.name, stadium.city).joinToString(", ")
            val query = Uri.encode(label)
            val geo = if (stadium.lat != null && stadium.lng != null) {
                "geo:${stadium.lat},${stadium.lng}?q=${stadium.lat},${stadium.lng}(${Uri.encode(stadium.name)})"
            } else {
                "geo:0,0?q=$query"
            }
            val target = if (stadium.lat != null && stadium.lng != null) "${stadium.lat},${stadium.lng}" else query
            return listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse(geo)),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$target")),
            )
        }
    }
}
