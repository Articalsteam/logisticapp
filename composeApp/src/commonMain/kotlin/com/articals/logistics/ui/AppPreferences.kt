package com.articals.logistics.ui

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The appearance and layout choices that belong to *this device*.
 *
 * They survive a restart and a sign-out: a dispatcher who prefers dark mode and
 * larger text should not have to say so again every morning. Nothing here is a
 * credential or a business record — those live on the backend.
 *
 * In a demo build the control panel writes through these same functions, so what the
 * panel shows is exactly what a real preference would do.
 */
class AppPreferences(
    private val settings: Settings,
) {
    private val fontScaleState = MutableStateFlow(readFontScale())
    private val darkModeState = MutableStateFlow(readDarkMode())

    /** Multiplies the device's own font scale; clamped to something usable. */
    val fontScale: StateFlow<Float> = fontScaleState.asStateFlow()

    /** Null means "follow the device"; true and false pin it. */
    val darkMode: StateFlow<Boolean?> = darkModeState.asStateFlow()

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        fontScaleState.value = clamped
        settings.putFloat(KEY_FONT_SCALE, clamped)
    }

    fun setDarkMode(enabled: Boolean?) {
        darkModeState.value = enabled
        when (enabled) {
            null -> settings.remove(KEY_DARK_MODE)
            else -> settings.putBoolean(KEY_DARK_MODE, enabled)
        }
    }

    /** Put the device back the way it was; used by "reset the demo". */
    fun resetAppearance() {
        fontScaleState.value = 1f
        darkModeState.value = null
        settings.remove(KEY_FONT_SCALE)
        settings.remove(KEY_DARK_MODE)
    }

    private fun readFontScale(): Float =
        settings.getFloat(KEY_FONT_SCALE, 1f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    private fun readDarkMode(): Boolean? = when {
        !settings.hasKey(KEY_DARK_MODE) -> null
        else -> settings.getBoolean(KEY_DARK_MODE, false)
    }

    companion object {
        private const val KEY_FONT_SCALE = "ui.font_scale"
        private const val KEY_DARK_MODE = "ui.dark_mode"
        private const val MIN_FONT_SCALE = 0.8f
        private const val MAX_FONT_SCALE = 1.6f
    }
}
