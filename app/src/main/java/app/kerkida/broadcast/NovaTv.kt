package app.kerkida.broadcast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.Normalizer
import java.util.Locale

/**
 * Opens a channel in the Nova TV app through its official links.
 * Signing in and playing stay inside Nova's app, under the person's own subscription.
 */
object NovaTv {
    const val PACKAGE = "uc.flex.gr"

    private val Fixed = mapOf(
        "novasports1" to 23, "novasports2" to 24, "novasports3" to 25, "novasports4" to 26,
        "novasports5" to 28, "novasports6" to 90, "novasportsstart" to 89, "novasportsprime" to 91,
        "novasportsnews" to 6, "novasportspl" to 177, "novasportspremierleague" to 177,
        "novasportsextra1" to 165, "novasportsextra2" to 166, "novasportsextra3" to 167,
        "eurosport1" to 29, "eurosport2" to 31,
        "ert1" to 2, "ert2" to 3, "ert2spor" to 3, "ert3" to 4, "ertnews" to 184,
        "skai" to 30, "alpha" to 34, "star" to 33, "open" to 37, "openbeyond" to 37,
    )
    private val Magenta = Regex("^magentasport([1-9])(l|league)?$")
    private val NumberedLeague = Regex("^(novasports[1-6])league$")

    private fun key(name: String): String {
        val plain = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .replace("ερτ", "ert").replace("σπορ", "spor").replace("σκαι", "skai")
            .replace("ηd", "hd")
            .filter { it.isLetterOrDigit() }
        return plain.removeSuffix("hd")
    }

    fun channelNumber(name: String): Int? {
        val k = key(name)
        Fixed[k]?.let { return it }
        Magenta.find(k)?.let { return 210 + it.groupValues[1].toInt() }
        NumberedLeague.find(k)?.let { return Fixed[it.groupValues[1]] }
        return null
    }

    fun linkFor(name: String): String? = channelNumber(name)?.let { "https://novatv.gr/tv/channel/$it" }

    /** The first channel of a listing that Nova TV carries. */
    fun firstCarried(channels: List<String>): String? = channels.firstOrNull { channelNumber(it) != null }

    fun open(context: Context, channel: String) {
        val link = linkFor(channel) ?: return
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(app)
        } catch (_: ActivityNotFoundException) {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(store) }
        }
    }
}
