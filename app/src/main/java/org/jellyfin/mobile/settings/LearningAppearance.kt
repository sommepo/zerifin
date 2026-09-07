package org.jellyfin.mobile.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.edit

enum class LookupTheme { SYSTEM, LIGHT, DARK }

class LookupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("subtitle_lookup", Context.MODE_PRIVATE)

    var pauseOnLookup: Boolean
        get() = preferences.getBoolean("pause_on_lookup", true)
        set(value) { preferences.edit { putBoolean("pause_on_lookup", value) } }

    var pauseOnScreenTap: Boolean
        get() = preferences.getBoolean("pause_on_screen_tap", true)
        set(value) { preferences.edit { putBoolean("pause_on_screen_tap", value) } }

    var theme: LookupTheme
        get() = LookupTheme.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: LookupTheme.SYSTEM
        set(value) { preferences.edit { putString("theme", value.name) } }

    var learningControlsPosition: LearningControlsPosition?
        get() {
            if (!preferences.contains("learning_controls_x") || !preferences.contains("learning_controls_y")) return null
            return LearningControlsPosition(
                preferences.getFloat("learning_controls_x", 1f).coerceIn(0f, 1f),
                preferences.getFloat("learning_controls_y", 0f).coerceIn(0f, 1f),
            )
        }
        set(value) {
            preferences.edit {
                if (value == null) {
                    remove("learning_controls_x")
                    remove("learning_controls_y")
                } else {
                    putFloat("learning_controls_x", value.x.coerceIn(0f, 1f))
                    putFloat("learning_controls_y", value.y.coerceIn(0f, 1f))
                }
            }
        }
}

data class LearningControlsPosition(val x: Float, val y: Float)

data class LearningPalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val secondary: Int,
    val border: Int,
    val accent: Int,
) {
    companion object {
        fun usesDark(theme: LookupTheme, systemDark: Boolean): Boolean = when (theme) {
            LookupTheme.SYSTEM -> systemDark
            LookupTheme.LIGHT -> false
            LookupTheme.DARK -> true
        }

        fun get(context: Context, theme: LookupTheme = LookupTheme.SYSTEM): LearningPalette {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (usesDark(theme, dark)) {
                LearningPalette(
                    Color.rgb(16, 20, 19),
                    Color.rgb(25, 32, 29),
                    Color.rgb(235, 242, 238),
                    Color.rgb(170, 186, 178),
                    Color.rgb(63, 78, 69),
                    Color.rgb(80, 213, 151)
                )
            } else {
                LearningPalette(
                    Color.rgb(246, 249, 247),
                    Color.rgb(252, 254, 252),
                    Color.rgb(28, 40, 32),
                    Color.rgb(91, 110, 99),
                    Color.rgb(200, 213, 205),
                    Color.rgb(15, 127, 82)
                )
            }
        }
    }
}
