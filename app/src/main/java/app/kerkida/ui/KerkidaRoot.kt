package app.kerkida.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kerkida.KerkidaApp
import app.kerkida.R
import app.kerkida.data.demo.DemoSportsDataProvider
import app.kerkida.ui.common.DemoBanner
import app.kerkida.ui.common.LocalDemoSwitch
import app.kerkida.ui.fantasy.FantasyScreen
import app.kerkida.ui.favourites.FavouritesScreen
import app.kerkida.ui.league.LeagueScreen
import app.kerkida.ui.league.LeaguesScreen
import app.kerkida.ui.match.MatchScreen
import app.kerkida.ui.matches.MatchesScreen
import app.kerkida.ui.search.SearchScreen
import app.kerkida.ui.season.MySeasonScreen
import app.kerkida.ui.nearby.NearbyScreen
import app.kerkida.ui.planner.WeekendScreen
import app.kerkida.ui.settings.LeaguePickerScreen
import app.kerkida.ui.settings.SettingsScreen
import app.kerkida.ui.team.TeamScreen
import app.kerkida.features.Feature
import app.kerkida.ui.shield.isFeatureOn
import app.kerkida.ui.theme.KerkidaIcons
import app.kerkida.ui.tv.TvRootScreen

private object Routes {
    const val MATCHES = "matches"
    const val TV = "tv"
    const val LEAGUES = "leagues"
    const val SEARCH = "search"
    const val FAVOURITES = "favourites"
    const val SETTINGS = "settings"
    const val FANTASY = "fantasy"
    const val NEARBY = "nearby"
    const val WEEKEND = "weekend"
    const val SEASON = "my-season"
    const val LEAGUE_PICKER = "league-picker"
    const val MATCH = "match/{id}"
    const val LEAGUE = "league/{id}"
    const val TEAM = "team/{id}"

    fun match(id: String) = "match/${android.net.Uri.encode(id)}"
    fun league(id: String) = "league/${android.net.Uri.encode(id)}"
    fun team(id: String) = "team/${android.net.Uri.encode(id)}"
}

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab(Routes.MATCHES, R.string.nav_matches, KerkidaIcons.Matches),
    Tab(Routes.TV, R.string.nav_tv, KerkidaIcons.Tv),
    Tab(Routes.LEAGUES, R.string.nav_leagues, KerkidaIcons.Leagues),
    Tab(Routes.SEARCH, R.string.nav_search, KerkidaIcons.Search),
    Tab(Routes.FAVOURITES, R.string.nav_favourites, KerkidaIcons.Star),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KerkidaRoot() {
    val container = (LocalContext.current.applicationContext as KerkidaApp).container
    val provider by container.provider.collectAsStateWithLifecycle()
    val isDemo = provider.info.isDemo
    val switchToDemo: (() -> Unit)? = if (isDemo) null else {
        { container.settings.setProvider(DemoSportsDataProvider.ID) }
    }

    CompositionLocalProvider(
        LocalDemoSwitch provides switchToDemo,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (isDemo) DemoBanner()
            // Each source gets a fresh navigation history, so demo screens can never linger under real data.
            key(provider.info.id) {
                val navController = rememberNavController()
                val isShort = LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (isShort) SideRail(navController)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (isDemo) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
                            .imePadding(),
                    ) {
                        KerkidaNavHost(navController)
                    }
                }
                if (!isShort && !WindowInsets.isImeVisible) BottomBar(navController)
            }
        }
    }
}

private const val SHORT_SCREEN_DP = 480
private const val MENU_MAX_TEXT_SCALE = 1.15f

private fun openTab(navController: NavHostController, route: String) {
    val start = navController.graph.findStartDestination().id
    val currentRoute = navController.currentDestination?.route
    when {
        route == Routes.MATCHES -> navController.popBackStack(start, inclusive = false)
        currentRoute == route -> Unit
        else -> navController.navigate(route) {
            // Remember a tab only from its top level, so tapping it always leaves detail pages.
            popUpTo(start) { saveState = Tabs.any { it.route == currentRoute } }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** The five menu words must fit side by side, so they grow only a little with the system text size. */
@Composable
private fun MenuLabel(text: String) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val size = MaterialTheme.typography.labelMedium.fontSize * (minOf(scale, MENU_MAX_TEXT_SCALE) / scale)
    Text(text, fontSize = size, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SideRail(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showTv = isFeatureOn(Feature.TV_GUIDE)
    androidx.compose.material3.NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Tabs.filter { showTv || it.route != Routes.TV }.forEach { tab ->
            androidx.compose.material3.NavigationRailItem(
                selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                onClick = { openTab(navController, tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { MenuLabel(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showTv = isFeatureOn(Feature.TV_GUIDE)
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.navigationBars,
    ) {
        Tabs.filter { showTv || it.route != Routes.TV }.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { openTab(navController, tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { MenuLabel(stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun KerkidaNavHost(navController: NavHostController) {
    val openMatch: (String) -> Unit = { navController.navigate(Routes.match(it)) }
    val openLeague: (String) -> Unit = { navController.navigate(Routes.league(it)) }
    val openTeam: (String) -> Unit = { navController.navigate(Routes.team(it)) }
    val back: () -> Unit = { navController.popBackStack() }
    val idArgument = listOf(navArgument("id") { type = NavType.StringType })

    NavHost(navController = navController, startDestination = Routes.MATCHES) {
        composable(Routes.MATCHES) {
            MatchesScreen(
                onMatch = openMatch,
                onLeague = openLeague,
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onFantasy = { navController.navigate(Routes.FANTASY) },
                onNearby = { navController.navigate(Routes.NEARBY) },
                onWeekend = { navController.navigate(Routes.WEEKEND) },
            )
        }
        composable(Routes.TV) { TvRootScreen(onMatch = openMatch) }
        composable(Routes.LEAGUES) { LeaguesScreen(onLeague = openLeague) }
        composable(Routes.SEARCH) { SearchScreen(onTeam = openTeam, onLeague = openLeague) }
        composable(Routes.FAVOURITES) {
            FavouritesScreen(
                onTeam = openTeam,
                onLeague = openLeague,
                onMatch = openMatch,
                onSeason = { navController.navigate(Routes.SEASON) },
                onSearch = {
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back, onLeagues = { navController.navigate(Routes.LEAGUE_PICKER) }) }
        composable(Routes.LEAGUE_PICKER) { LeaguePickerScreen(onBack = back) }
        composable(Routes.SEASON) { MySeasonScreen(onBack = back, onMatch = openMatch) }
        composable(Routes.NEARBY) { NearbyScreen(onBack = back, onMatch = openMatch) }
        composable(Routes.WEEKEND) { WeekendScreen(onBack = back, onMatch = openMatch) }
        composable(Routes.FANTASY) { FantasyScreen(onBack = back) }
        composable(Routes.MATCH, idArgument) { entry ->
            MatchScreen(
                matchId = entry.arguments?.getString("id").orEmpty(),
                onBack = back,
                onTeam = openTeam,
                onLeague = openLeague,
            )
        }
        composable(Routes.LEAGUE, idArgument) { entry ->
            LeagueScreen(
                leagueId = entry.arguments?.getString("id").orEmpty(),
                onBack = back,
                onMatch = openMatch,
                onTeam = openTeam,
            )
        }
        composable(Routes.TEAM, idArgument) { entry ->
            TeamScreen(
                teamId = entry.arguments?.getString("id").orEmpty(),
                onBack = back,
                onMatch = openMatch,
                onLeague = openLeague,
                onTeam = openTeam,
            )
        }
    }
}
