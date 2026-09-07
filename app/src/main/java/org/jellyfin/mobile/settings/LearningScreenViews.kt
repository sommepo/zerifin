package org.jellyfin.mobile.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins

internal fun Context.learningDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun learningBackground(context: Context, radius: Int = 22, selected: Boolean = false): GradientDrawable {
    val palette = LearningPalette.get(context)
    return GradientDrawable().apply {
        cornerRadius = context.learningDp(radius).toFloat()
        setColor(if (selected) (palette.accent and 0x00ffffff) or 0x18000000 else palette.surface)
        setStroke(context.learningDp(1).coerceAtLeast(1), if (selected) palette.accent else palette.border)
    }
}

internal class LearningPage(context: Context, title: String, onBack: () -> Unit) : LinearLayout(context) {
    val toolbar = Toolbar(context)
    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        val palette = LearningPalette.get(context)
        setBackgroundColor(palette.background)
        applyWindowInsetsAsMargins()
        toolbar.title = title
        toolbar.setTitleTextColor(palette.text)
        toolbar.setNavigationIcon(R.drawable.ic_learning_back)
        toolbar.navigationIcon?.setTint(palette.text)
        toolbar.navigationContentDescription = context.getString(R.string.learning_back)
        toolbar.setNavigationOnClickListener { onBack() }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, context.learningDp(64)))
    }
}

internal fun learningText(context: Context, value: String, size: Float = 16f, secondary: Boolean = false): TextView =
    TextView(context).apply {
        text = value
        textSize = size
        setTextColor(LearningPalette.get(context).let { if (secondary) it.secondary else it.text })
    }

internal fun learningButton(context: Context, label: String, action: () -> Unit): Button = Button(context).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    setTextColor(LearningPalette.get(context).accent)
    background = learningBackground(context, 16)
    minimumHeight = context.learningDp(48)
    setPadding(context.learningDp(16), 0, context.learningDp(16), 0)
    setOnClickListener { action() }
}

internal fun learningSwitch(context: Context, label: String, checked: Boolean, changed: (Boolean) -> Unit): SwitchCompat =
    SwitchCompat(context).apply {
        text = label
        textSize = 16f
        setTextColor(LearningPalette.get(context).text)
        isChecked = checked
        minHeight = context.learningDp(64)
        setPadding(context.learningDp(20), context.learningDp(12), context.learningDp(20), context.learningDp(12))
        switchPadding = context.learningDp(20)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(LearningPalette.get(context).accent, LearningPalette.get(context).secondary)
        )
        background = learningBackground(context)
        setOnCheckedChangeListener { _, value -> changed(value) }
    }

/** A contained selector; no popup-window spinner drawable is attached to the scrolling form. */
internal class LearningChoice(context: Context, private val label: String) : LinearLayout(context) {
    private val valueView = learningText(context, "", 16f)
    private var choices = emptyList<String>()
    var selectedPosition: Int = 0
        private set
    var onSelected: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.learningDp(80)
        setPadding(context.learningDp(20), context.learningDp(14), context.learningDp(20), context.learningDp(14))
        background = learningBackground(context)
        isFocusable = true
        isClickable = true
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(learningText(context, label, 12f, true).apply { setPadding(0, 0, 0, context.learningDp(5)) })
            addView(valueView)
        }
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(
            learningText(context, "›", 26f, true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(context.learningDp(24), LayoutParams.WRAP_CONTENT)
        )
        setOnClickListener {
            if (choices.isNotEmpty()) showChoiceList(context, label, choices, selectedPosition) {
                setSelection(it)
                onSelected?.invoke(it)
            }
        }
    }

    fun setItems(items: List<String>, selected: Int = 0) {
        choices = items
        setSelection(selected)
    }

    fun setSelection(index: Int) {
        selectedPosition = index.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        valueView.text = choices.getOrNull(selectedPosition).orEmpty()
        contentDescription = "$label: ${valueView.text}"
    }
}

internal fun showChoiceList(context: Context, title: String, choices: List<String>, selected: Int, chosen: (Int) -> Unit) {
    val palette = LearningPalette.get(context)
    val isDark = LearningPalette.usesDark(
        LookupTheme.SYSTEM,
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    )
    val theme = if (isDark) androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert else androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert
    val dialog = AlertDialog.Builder(ContextThemeWrapper(context, theme)).setTitle(title)
        .setSingleChoiceItems(choices.toTypedArray(), selected) { window, index ->
            chosen(index)

            window.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null).create()
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawable(learningBackground(context))
        dialog.listView.setBackgroundColor(palette.surface)
    }
    dialog.show()
}
