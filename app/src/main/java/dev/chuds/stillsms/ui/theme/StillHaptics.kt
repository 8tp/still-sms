package dev.chuds.stillsms.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Tap-feedback hook. The app root provides either a real performer (driven by
 * `LocalHapticFeedback` + the user preference) or a no-op. Verbs invoke
 * `LocalHaptics.current()` before calling `onClick`, so individual screens never
 * need to thread the preference through.
 */
val LocalHaptics = staticCompositionLocalOf<() -> Unit> { {} }
