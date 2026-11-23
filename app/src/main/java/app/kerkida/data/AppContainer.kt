package app.kerkida.data

import android.content.Context
import app.kerkida.alerts.AlertStore
import app.kerkida.broadcast.BroadcastRepository
import app.kerkida.broadcast.ManualBroadcastProvider
import app.kerkida.broadcast.PrefsBroadcastStore
import app.kerkida.calendar.TeamCalendar
import app.kerkida.data.demo.DemoSportsDataProvider
import app.kerkida.data.espn.EspnProvider
import app.kerkida.data.greek.AchaiaFeed
import app.kerkida.data.greek.GblSource
import app.kerkida.data.greek.GreekLowerProvider
import app.kerkida.data.greek.GreekLowerSource
import app.kerkida.data.greek.LasithiFeed
import app.kerkida.data.greek.OpenResultsSource
import app.kerkida.data.greek.LocalLeagues
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.RateLimiter
import app.kerkida.data.net.ResponseCache
import app.kerkida.data.thesportsdb.TheSportsDbProvider
import app.kerkida.fantasy.FantasyInsights
import app.kerkida.highlights.HighlightsRepository
import app.kerkida.fantasy.FplHttpService
import app.kerkida.features.FeatureFlags
import app.kerkida.shield.ObservingProvider
import app.kerkida.shield.PrefsShieldStore
import app.kerkida.shield.ShieldController
import app.kerkida.stadium.StadiumPack
import app.kerkida.tv.SportFmSource
import app.kerkida.tv.TeamDirectory
import app.kerkida.tv.TvScheduleRepository
import app.kerkida.tv.XmltvSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** All selectable data sources. Add a new provider here to make it available in Settings. */
class ProviderRegistry(val providers: List<SportsDataProvider>) {
    val default: SportsDataProvider = providers.first { !it.info.isDemo }
    fun byId(id: String): SportsDataProvider = providers.firstOrNull { it.info.id == id } ?: default
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cachedClient = CachedHttpClient(
        http = httpClient,
        cache = ResponseCache(File(appContext.cacheDir, "kerkida-api")),
        limiter = RateLimiter(),
    )

    /** Open feeds get a plain app name as their client, and limits of their own. */
    private val openHttp = httpClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Kerkida/0.1 (Android)").build())
        }
        .build()

    private val espnClient = CachedHttpClient(
        http = openHttp,
        cache = ResponseCache(File(appContext.cacheDir, "kerkida-espn")),
        limiter = RateLimiter(minGapMs = 150, maxPerMinute = 120),
    )

    private val fplClient = CachedHttpClient(
        http = openHttp,
        cache = ResponseCache(File(appContext.cacheDir, "kerkida-fpl")),
        limiter = RateLimiter(minGapMs = 1_500, maxPerMinute = 20),
    )

    val flags = FeatureFlags(appContext)
    val alerts = AlertStore(appContext)
    val fantasyService = FplHttpService(fplClient)
    val fantasy = FantasyInsights(fantasyService)
    val playerPhotos = app.kerkida.data.photos.PlayerPhotos(appContext, fantasy)

    private val broadcastStore = PrefsBroadcastStore(appContext)
    val tv = TvScheduleRepository(
        appContext,
        listOf(
            SportFmSource(openHttp),
            XmltvSource(openHttp, File(appContext.cacheDir, "kerkida-guide.xml.gz")),
        ),
    )
    val broadcasts = BroadcastRepository(ManualBroadcastProvider(broadcastStore), broadcastStore, tv)
    private val greekLower = GreekLowerSource(cachedClient, { GreekLowerSource.readPack(appContext) })
    private val gbl = GblSource(
        CachedHttpClient(
            http = openHttp,
            cache = ResponseCache(File(appContext.cacheDir, "kerkida-gbl")),
            limiter = RateLimiter(minGapMs = 1_200, maxPerMinute = 30),
        ),
    )
    private val localPages = CachedHttpClient(
        http = openHttp,
        cache = ResponseCache(File(appContext.cacheDir, "kerkida-local")),
        limiter = RateLimiter(minGapMs = 1_500, maxPerMinute = 25),
    )
    private val localLeagues = LocalLeagues(listOf(AchaiaFeed(localPages), LasithiFeed(localPages)))
    private val openResults = OpenResultsSource(
        CachedHttpClient(
            http = openHttp,
            cache = ResponseCache(File(appContext.cacheDir, "kerkida-open")),
            limiter = RateLimiter(minGapMs = 1_500, maxPerMinute = 30),
        ),
    )
    val clubProfiles = app.kerkida.data.club.ClubProfiles(
        CachedHttpClient(
            http = openHttp,
            cache = ResponseCache(File(appContext.cacheDir, "kerkida-club")),
            limiter = RateLimiter(minGapMs = 1_500, maxPerMinute = 20),
        ),
        openHttp,
        File(appContext.cacheDir, "kerkida-club-photos"),
    )
    val stadiums = StadiumPack(appContext) { greekLower.stadiumOf(it) ?: gbl.stadiumOf(it) ?: openResults.stadiumOf(it) ?: localLeagues.stadiumOf(it) }
    val teamDirectory = TeamDirectory(appContext)
    val catalog = LeagueCatalog(appContext).also { leagues ->
        // Existing installs get Super League 2 and the Patras group once. They can be unticked after that.
        val prefs = appContext.getSharedPreferences("kerkida_leagues", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("greek_lower_added", false)) {
            leagues.setAll(listOf("grl.4640", "grl.5847", GblSource.LEAGUE_ID), true)
            prefs.edit().putBoolean("greek_lower_added", true).apply()
        }
        if (!prefs.getBoolean("cup_volley_added", false)) {
            leagues.setAll(listOf("grl.5830"), true)
            prefs.edit().putBoolean("cup_volley_added", true).apply()
        }
        if (!prefs.getBoolean("open_results_added", false)) {
            leagues.setAll(listOf(OpenResultsSource.ELITE, OpenResultsSource.VOLLEY_MEN, OpenResultsSource.VOLLEY_WOMEN), true)
            prefs.edit().putBoolean("open_results_added", true).apply()
        }
        if (!prefs.getBoolean("local_leagues_added", false)) {
            leagues.setAll(listOf("grl.ach.a1", "grl.ach.a2", LasithiFeed.LEAGUE_ID), true)
            prefs.edit().putBoolean("local_leagues_added", true).apply()
        }
    }
    val season = app.kerkida.season.SeasonStore(appContext)
    val teamCalendar = TeamCalendar(appContext)
    val highlights = HighlightsRepository(appContext, openHttp)

    val registry = ProviderRegistry(
        listOf(
            GreekLowerProvider(
                EspnProvider(appContext, espnClient, catalog = catalog, directory = teamDirectory),
                greekLower,
                catalog,
                gbl,
                localLeagues,
                openResults,
                { flags.isOn(app.kerkida.features.Feature.GREEK_OPEN_RESULTS) },
            ) { flags.isOn(app.kerkida.features.Feature.GREEK_LOWER) },
            TheSportsDbProvider(appContext, cachedClient),
            DemoSportsDataProvider(),
        ),
    )

    val settings = SettingsStore(appContext, registry.default.info.id)
    val favourites = FavouritesStore(appContext).also {
        val european = if (flags.isOn(app.kerkida.features.Feature.GREEK_LOWER)) app.kerkida.data.greek.GreekLowerLogic.EuropeanClubs else emptyMap()
        it.mergeTeams(registry.default.info.id, app.kerkida.data.greek.GreekLowerLogic.MergedTeams + european)
    }

    val shield = ShieldController(
        store = PrefsShieldStore(appContext),
        favouriteTeamIds = {
            favourites.favourites(settings.settings.value.providerId).value
                .filter { it.favouriteKind == FavouriteKind.TEAM }
                .map { it.id }
                .toSet()
        },
        isEnabled = { flags.isOn(app.kerkida.features.Feature.SPOILER_SHIELD) },
    )

    private val observed = registry.providers.associate { it.info.id to ObservingProvider(it, shield) }

    private fun observedById(id: String): SportsDataProvider = observed.getValue(registry.byId(id).info.id)

    /** The active source. It only changes when the person chooses, never as an automatic fallback. */
    val provider: StateFlow<SportsDataProvider> = settings.settings
        .map { observedById(it.providerId) }
        .stateIn(scope, SharingStarted.Eagerly, observedById(settings.settings.value.providerId))

    init {
        scope.launch { shield.runClock() }
    }

    private var warming: kotlinx.coroutines.Job? = null

    /** Starts today's games and the slowest source early, so they are ready when the screens open. */
    fun warmUp() {
        if (warming?.isActive == true || provider.value.info.isDemo) return
        val withGbl = flags.isOn(app.kerkida.features.Feature.GREEK_LOWER) && GblSource.LEAGUE_ID in catalog.selected.value
        warming = scope.launch {
            launch { runCatching { provider.value.matchDay(java.time.LocalDate.now(), emptyList(), false).collect { } } }
            if (withGbl) launch { runCatching { gbl.load() } }
        }
    }
}
