package app.kerkida

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kerkida.data.AppContainer
import app.kerkida.data.SettingsStore
import app.kerkida.data.ThemeMode
import app.kerkida.ui.KerkidaRoot
import app.kerkida.ui.intro.LogoIntro
import app.kerkida.ui.theme.KerkidaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(SettingsStore.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The header is navy in both themes, so the status bar always uses light icons.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()))
        super.onCreate(savedInstanceState)
        val app = application as KerkidaApp
        val isFreshStart = savedInstanceState == null
        setContent {
            // The app's data is prepared off the main thread, so the logo can start at once.
            val container by produceState<AppContainer?>(null) {
                value = withContext(Dispatchers.Default) {
                    app.container.also { if (isFreshStart) it.warmUp() }
                }
            }
            // Survives rotation, so the opening plays once per launch.
            var showIntro by rememberSaveable { mutableStateOf(true) }
            var showApp by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                // The app's first draw is heavy, so it waits until the scarves have stopped moving.
                container?.takeIf { showApp }?.let { ThemedApp(it) }
                if (showIntro) {
                    LogoIntro(
                        isReady = container != null,
                        onSettled = { showApp = true },
                        onDone = { showIntro = false },
                    )
                }
            }
        }
    }

    @Composable
    private fun ThemedApp(container: AppContainer) {
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        // Android draws the opening screen before the app runs, so it is told the chosen theme ahead of time.
        LaunchedEffect(settings.theme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                splashScreen.setSplashScreenTheme(
                    when (settings.theme) {
                        ThemeMode.DARK -> R.style.Theme_Kerkida_SplashDark
                        ThemeMode.LIGHT -> R.style.Theme_Kerkida_SplashLight
                        ThemeMode.SYSTEM -> Resources.ID_NULL
                    },
                )
            }
        }
        KerkidaTheme(themeMode = settings.theme) {
            KerkidaRoot()
        }
    }
}
