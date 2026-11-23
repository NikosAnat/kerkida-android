package app.kerkida.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/** Lets an error screen offer the demo mode. It is null while demo mode is already on. */
val LocalDemoSwitch = staticCompositionLocalOf<(() -> Unit)?> { null }
