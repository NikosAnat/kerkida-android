package app.kerkida.ui.nearby

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kerkida.R
import app.kerkida.nearby.NearbyMapLogic
import app.kerkida.nearby.NearbyMatch
import app.kerkida.nearby.Place
import app.kerkida.nearby.StadiumPin
import app.kerkida.ui.common.MatchRow
import app.kerkida.ui.common.SectionCard
import app.kerkida.ui.common.currentLocale
import app.kerkida.ui.theme.KerkidaIcons
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val GREEN = "#00B37A"
private const val NAVY = "#0B1F4B"
private const val WHITE = "#FFFFFF"
private const val AMBER = "#F2B134"
private const val GAMES = "games"
private const val PIN_KEY = "pin"
// The one font both the light and the dark map style provide.
private val LabelFont = arrayOf("Noto Sans Regular")

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
}

/** The alternative view of nearby games: one pin per ground, grouped when grounds sit close together. */
@Composable
fun NearbyMap(place: Place, radiusKm: Int, results: List<NearbyMatch>, onMatch: (String) -> Unit) {
    val pins = remember(results) { NearbyMapLogic.pins(results) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = pins.firstOrNull { it.key == selectedKey }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(Modifier.fillMaxSize()) {
        key(isDark) {
            GroundsMap(place, radiusKm, pins, selected?.key, isDark) { selectedKey = it }
        }
        val uri = LocalUriHandler.current
        Text(
            stringResource(R.string.nearby_map_credit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button) { uri.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        selected?.let { pin ->
            StadiumCard(pin, onMatch, { selectedKey = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun GroundsMap(
    place: Place,
    radiusKm: Int,
    pins: List<StadiumPin>,
    selected: String?,
    isDark: Boolean,
    onSelect: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val holder = remember { MapHolder() }
    var ready by remember { mutableStateOf(false) }
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                holder.map = map
                map.uiSettings.apply {
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isCompassEnabled = false
                    isLogoEnabled = false
                    // The credit is drawn by the screen, so the games card can never cover it.
                    isAttributionEnabled = false
                }
                map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/${if (isDark) "dark" else "positron"}")) { style ->
                    addGroundLayers(style)
                    holder.style = style
                    ready = true
                }
                map.addOnMapClickListener { point -> handleTap(map, holder, point) { select(it) } }
            }
        }
    }
    DisposableEffect(lifecycle, view) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { view.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(ready, pins, selected) {
        val style = holder.style.takeIf { ready } ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SOURCE_GROUNDS)?.setGeoJson(pinFeatures(pins))
        style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)?.setGeoJson(pinFeatures(pins.filter { it.key == selected }))
    }
    LaunchedEffect(ready, place, radiusKm) {
        val style = holder.style.takeIf { ready } ?: return@LaunchedEffect
        val ring = NearbyMapLogic.circle(place.lat, place.lng, radiusKm)
        val outline = ring.map { (lat, lng) -> Point.fromLngLat(lng, lat) }
        style.getSourceAs<GeoJsonSource>(SOURCE_RADIUS)?.setGeoJson(Polygon.fromLngLats(listOf(outline)))
        style.getSourceAs<GeoJsonSource>(SOURCE_PLACE)?.setGeoJson(Point.fromLngLat(place.lng, place.lat))
        val bounds = LatLngBounds.Builder().apply { ring.forEach { (lat, lng) -> include(LatLng(lat, lng)) } }.build()
        holder.map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56), 500)
    }
}

private const val SOURCE_GROUNDS = "kerkida-grounds"
private const val SOURCE_SELECTED = "kerkida-selected"
private const val SOURCE_RADIUS = "kerkida-radius"
private const val SOURCE_PLACE = "kerkida-place"
private const val LAYER_CLUSTER = "kerkida-cluster"
private const val LAYER_PIN = "kerkida-pin"

private fun addGroundLayers(style: Style) {
    val isCluster = Expression.has("point_count")
    style.addSource(GeoJsonSource(SOURCE_RADIUS))
    style.addSource(GeoJsonSource(SOURCE_PLACE))
    style.addSource(GeoJsonSource(SOURCE_SELECTED))
    style.addSource(
        GeoJsonSource(
            SOURCE_GROUNDS,
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(44)
                .withClusterMaxZoom(13)
                // A group shows how many games it holds, not how many grounds.
                .withClusterProperty(
                    GAMES,
                    Expression.sum(Expression.accumulated(), Expression.get(GAMES)),
                    Expression.get(GAMES),
                ),
        ),
    )
    style.addLayer(FillLayer("kerkida-radius-fill", SOURCE_RADIUS).withProperties(PropertyFactory.fillColor(GREEN), PropertyFactory.fillOpacity(0.07f)))
    style.addLayer(
        LineLayer("kerkida-radius-line", SOURCE_RADIUS).withProperties(
            PropertyFactory.lineColor(GREEN),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addLayer(
        CircleLayer("kerkida-place", SOURCE_PLACE).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(AMBER),
            PropertyFactory.circleStrokeColor(WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        CircleLayer("kerkida-selected-ring", SOURCE_SELECTED).withProperties(
            PropertyFactory.circleRadius(23f),
            PropertyFactory.circleColor(GREEN),
            PropertyFactory.circleOpacity(0.28f),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_CLUSTER, SOURCE_GROUNDS).withFilter(isCluster).withProperties(
            PropertyFactory.circleRadius(19f),
            PropertyFactory.circleColor(NAVY),
            PropertyFactory.circleStrokeColor(GREEN),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_PIN, SOURCE_GROUNDS).withFilter(Expression.not(isCluster)).withProperties(
            PropertyFactory.circleRadius(15f),
            PropertyFactory.circleColor(GREEN),
            PropertyFactory.circleStrokeColor(WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer("kerkida-count", SOURCE_GROUNDS).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get(GAMES))),
            PropertyFactory.textFont(LabelFont),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor(
                Expression.switchCase(isCluster, Expression.color(android.graphics.Color.WHITE), Expression.color(android.graphics.Color.parseColor(NAVY))),
            ),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
}

private fun pinFeatures(pins: List<StadiumPin>): FeatureCollection = FeatureCollection.fromFeatures(
    pins.map { pin ->
        Feature.fromGeometry(Point.fromLngLat(pin.lng, pin.lat)).apply {
            addStringProperty(PIN_KEY, pin.key)
            addNumberProperty(GAMES, pin.games.size)
        }
    },
)

private fun handleTap(map: MapLibreMap, holder: MapHolder, point: LatLng, onSelect: (String?) -> Unit): Boolean {
    val screen = map.projection.toScreenLocation(point)
    val reach = 28f * android.content.res.Resources.getSystem().displayMetrics.density
    val box = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
    val found = map.queryRenderedFeatures(box, LAYER_CLUSTER, LAYER_PIN).firstOrNull()
    if (found == null) {
        onSelect(null)
        return false
    }
    val spot = found.geometry() as? Point ?: return false
    val target = LatLng(spot.latitude(), spot.longitude())
    if (found.hasProperty("point_count")) {
        val zoom = holder.style?.getSourceAs<GeoJsonSource>(SOURCE_GROUNDS)?.getClusterExpansionZoom(found)?.toDouble()
            ?: (map.cameraPosition.zoom + 2)
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(target, zoom + 0.3), 450)
    } else {
        onSelect(found.getStringProperty(PIN_KEY))
        map.easeCamera(CameraUpdateFactory.newLatLng(target), 350)
    }
    return true
}

@Composable
private fun StadiumCard(pin: StadiumPin, onMatch: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val locale = currentLocale()
    val format = remember(locale) { DateTimeFormatter.ofPattern("EEE d/M", locale) }
    SectionCard(modifier.navigationBarsPadding().padding(10.dp)) {
        Column {
            Row(Modifier.padding(start = 14.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(KerkidaIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(pin.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.nearby_km, pin.games.first().km.toInt().coerceAtLeast(1)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(KerkidaIcons.Close, stringResource(R.string.action_close)) }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
                pin.games.forEach { game ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        listOfNotNull(game.match.kickoff?.atZone(ZoneId.systemDefault())?.format(format), game.match.league.name).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
                    )
                    MatchRow(game.match, onClick = { onMatch(game.match.id) })
                }
            }
        }
    }
}
