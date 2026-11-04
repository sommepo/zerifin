package org.jellyfin.mobile.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.edit

enum class LookupTheme { SYSTEM, LIGHT, DARK }

enum class LearningControlsTimeout(val milliseconds: Long?) {
    TWO_AND_HALF_SECONDS(2_500L),
    FIVE_SECONDS(5_000L),
    TEN_SECONDS(10_000L),
    ALWAYS_VISIBLE(null),
}

class LookupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("subtitle_lookup", Context.MODE_PRIVATE)

    var pauseOnLookup: Boolean
        get() = preferences.getBoolean("pause_on_lookup", true)
        set(value) { preferences.edit { putBoolean("pause_on_lookup", value) } }

    var pauseOnScreenTap: Boolean
        get() = preferences.getBoolean("pause_on_screen_tap", true)
        set(value) { preferences.edit { putBoolean("pause_on_screen_tap", value) } }

    var learningControlsTimeout: LearningControlsTimeout
        get() = LearningControlsTimeout.entries.firstOrNull {
            it.name == preferences.getString("learning_controls_timeout", null)
        } ?: LearningControlsTimeout.TWO_AND_HALF_SECONDS
        set(value) { preferences.edit { putString("learning_controls_timeout", value.name) } }

    var highlightKnownWords: Boolean
        get() = preferences.getBoolean("highlight_known_words", true)
        set(value) { preferences.edit { putBoolean("highlight_known_words", value) } }

    var blurAreaPerShow: Boolean
        get() = preferences.getBoolean("blur_area_per_show", true)
        set(value) { preferences.edit { putBoolean("blur_area_per_show", value) } }

    /** The blur area saved for one show (or film, or YouTube), falling back to the global one. */
    fun blurAreaFor(key: String?): BlurArea? {
        if (key == null || !blurAreaPerShow) return blurArea
        return BlurAreaCodec.decode(preferences.getString("blur_area_show_$key", null)) ?: blurArea
    }

    fun saveBlurArea(key: String?, area: BlurArea) {
        blurArea = area
        if (key != null && blurAreaPerShow) {
            preferences.edit { putString("blur_area_show_$key", BlurAreaCodec.encode(area)) }
        }
    }

    /** Whether the blur area was left on for this show; null when never set. */
    fun blurAreaShownFor(key: String?): Boolean? {
        if (key == null || !blurAreaPerShow || !preferences.contains("blur_area_on_$key")) return null
        return preferences.getBoolean("blur_area_on_$key", false)
    }

    fun setBlurAreaShown(key: String?, shown: Boolean) {
        if (key == null || !blurAreaPerShow) return
        preferences.edit { putBoolean("blur_area_on_$key", shown) }
    }

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

    var blurArea: BlurArea?
        get() {
            if (
                !preferences.contains("blur_area_x") ||
                !preferences.contains("blur_area_y") ||
                !preferences.contains("blur_area_width") ||
                !preferences.contains("blur_area_height")
            ) return null
            return BlurArea(
                x = preferences.getFloat("blur_area_x", 0.1f).coerceIn(0f, 1f),
                y = preferences.getFloat("blur_area_y", 0.72f).coerceIn(0f, 1f),
                width = preferences.getFloat("blur_area_width", 0.8f).coerceIn(0.05f, 1f),
                height = preferences.getFloat("blur_area_height", 0.16f).coerceIn(0.05f, 1f),
            )
        }
        set(value) {
            preferences.edit {
                if (value == null) {
                    remove("blur_area_x")
                    remove("blur_area_y")
                    remove("blur_area_width")
                    remove("blur_area_height")
                } else {
                    putFloat("blur_area_x", value.x.coerceIn(0f, 1f))
                    putFloat("blur_area_y", value.y.coerceIn(0f, 1f))
                    putFloat("blur_area_width", value.width.coerceIn(0.05f, 1f))
                    putFloat("blur_area_height", value.height.coerceIn(0.05f, 1f))
                }
            }
        }
}

data class LearningControlsPosition(val x: Float, val y: Float)

/** A blur rectangle expressed as fractions of the displayed video frame. */
data class BlurArea(val x: Float, val y: Float, val width: Float, val height: Float)

internal object BlurAreaCodec {
    fun encode(area: BlurArea): String = listOf(area.x, area.y, area.width, area.height).joinToString(",")

    fun decode(value: String?): BlurArea? {
        val parts = value?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
        if (parts.size != 4) return null
        return BlurArea(
            x = parts[0].coerceIn(0f, 1f),
            y = parts[1].coerceIn(0f, 1f),
            width = parts[2].coerceIn(0.05f, 1f),
            height = parts[3].coerceIn(0.05f, 1f),
        )
    }
}

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
