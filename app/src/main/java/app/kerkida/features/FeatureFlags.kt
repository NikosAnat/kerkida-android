package app.kerkida.features

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every Stage 1 feature can be turned off from Settings, which restores the app's earlier behaviour.
 * Alerts start off because they need the person's permission.
 */
enum class Feature(val defaultOn: Boolean) {
    SPOILER_SHIELD(true),
    BROADCASTS(true),
    TV_GUIDE(true),
    RATINGS(true),
    PREVIEW(true),
    SMART_ALERTS(false),
    FANTASY(true),
    HIGHLIGHTS(true),
    GREEK_LOWER(true),
    GREEK_OPEN_RESULTS(true),
    NOVA_BUTTON(true),
    CLUB_PROFILE(true),
    PLAYER_PHOTOS(true),
    NEARBY_ALERTS(false),
    WEEKLY_DIGEST(false),
    FPL_DEADLINE(false),
}

class FeatureFlags(context: Context) {
    private val prefs = context.getSharedPreferences("kerkida_features", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(
        Feature.entries.filter { prefs.getBoolean(it.name, it.defaultOn) }.toSet(),
    )
    val enabled: StateFlow<Set<Feature>> = _enabled.asStateFlow()

    fun isOn(feature: Feature): Boolean = feature in _enabled.value

    fun set(feature: Feature, on: Boolean) {
        prefs.edit().putBoolean(feature.name, on).apply()
        _enabled.value = if (on) _enabled.value + feature else _enabled.value - feature
    }
}
