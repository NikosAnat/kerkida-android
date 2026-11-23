package app.kerkida.data

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String?) { GREEK("el"), ENGLISH("en"), SYSTEM(null) }

data class AppSettings(
    val theme: ThemeMode,
    val language: AppLanguage,
    val providerId: String,
)

class SettingsStore(context: Context, defaultProviderId: String) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            theme = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
            language = readLanguage(context),
            providerId = prefs.getString(KEY_PROVIDER, null) ?: defaultProviderId,
        ),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setTheme(theme: ThemeMode) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _settings.update { it.copy(theme = theme) }
    }

    fun setLanguage(language: AppLanguage) {
        // Committed synchronously: the screen is rebuilt right after and reads this value.
        prefs.edit().putString(KEY_LANGUAGE, language.name).commit()
        _settings.update { it.copy(language = language) }
    }

    fun setProvider(providerId: String) {
        prefs.edit().putString(KEY_PROVIDER, providerId).apply()
        _settings.update { it.copy(providerId = providerId) }
    }

    companion object {
        private const val FILE = "kerkida_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROVIDER = "provider"

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        fun readLanguage(context: Context): AppLanguage = enumOr(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null),
            AppLanguage.GREEK,
        )

        /** Greek by default. Any string missing from the Greek set falls back to English. */
        fun localized(base: Context): Context {
            val tag = readLanguage(base).tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}

enum class FavouriteKind { TEAM, LEAGUE, MATCH }

@Serializable
data class Favourite(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badgeUrl: String? = null,
    val awayBadgeUrl: String? = null,
    val kickoffEpochSeconds: Long? = null,
) {
    val favouriteKind: FavouriteKind? get() = FavouriteKind.entries.firstOrNull { it.name == kind }
    val key: String get() = "$kind:$id"
}

/** Favourites stay on this device, kept apart per data source so demo items never mix with real ones. */
class FavouritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("kerkida_favourites", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val flows = HashMap<String, MutableStateFlow<List<Favourite>>>()

    @Synchronized
    fun favourites(providerId: String): StateFlow<List<Favourite>> = flowFor(providerId).asStateFlow()

    @Synchronized
    fun toggle(providerId: String, item: Favourite) {
        val flow = flowFor(providerId)
        val current = flow.value
        val updated = if (current.any { it.key == item.key }) {
            current.filterNot { it.key == item.key }
        } else {
            current + item
        }
        flow.value = updated
        prefs.edit().putString(providerId, json.encodeToString(updated)).apply()
    }

    /** Points favourites of a merged profile at the club's remaining one. */
    @Synchronized
    fun mergeTeams(providerId: String, merged: Map<String, String>) {
        val flow = flowFor(providerId)
        val updated = flow.value.map { item -> merged[item.id]?.let { item.copy(id = it) } ?: item }.distinctBy { it.key }
        if (updated == flow.value) return
        flow.value = updated
        prefs.edit().putString(providerId, json.encodeToString(updated)).apply()
    }

    private fun flowFor(providerId: String): MutableStateFlow<List<Favourite>> =
        flows.getOrPut(providerId) {
            val saved = prefs.getString(providerId, null)
            val items = saved?.let { runCatching { json.decodeFromString<List<Favourite>>(it) }.getOrNull() }
            MutableStateFlow(items.orEmpty())
        }
}
