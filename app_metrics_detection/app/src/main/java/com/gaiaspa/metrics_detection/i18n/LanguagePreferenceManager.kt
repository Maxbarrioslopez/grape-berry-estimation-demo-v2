package com.gaiaspa.metrics_detection.i18n

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.gaiaspa.metrics_detection.FeatureFlags
import com.gaiaspa.metrics_detection.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

object LanguagePreferenceManager {
    private const val PREFS_NAME = "language_preferences"
    private const val KEY_LANGUAGE = "app_language"
    private const val LANGUAGE_ES = "es"
    private const val LANGUAGE_EN = "en"

    fun getSavedLanguage(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
            ?.takeIf { it == LANGUAGE_ES || it == LANGUAGE_EN }
    }

    fun hasSavedLanguage(context: Context): Boolean = getSavedLanguage(context) != null

    fun applySavedLanguageIfAny(context: Context) {
        if (!FeatureFlags.FEATURE_LANGUAGE_SWITCH) return
        getSavedLanguage(context)?.let { applyLanguage(it) }
    }

    fun showSelector(
        activity: AppCompatActivity,
        cancelable: Boolean,
        onSelected: (() -> Unit)? = null
    ) {
        if (!FeatureFlags.FEATURE_LANGUAGE_SWITCH) return
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 26))
        }
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.language_selector_title)
            setTextColor(ContextCompat.getColor(activity, R.color.textPrimary))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(languageButton(activity, activity.getString(R.string.language_spanish), LANGUAGE_ES, dialog, onSelected))
        content.addView(languageButton(activity, activity.getString(R.string.language_english), LANGUAGE_EN, dialog, onSelected))
        dialog.setContentView(content)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.show()
    }

    private fun languageButton(
        activity: AppCompatActivity,
        label: String,
        languageCode: String,
        dialog: BottomSheetDialog,
        onSelected: (() -> Unit)?
    ): MaterialButton {
        val selected = getSavedLanguage(activity) == languageCode
        return MaterialButton(activity).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            minHeight = dp(activity, 56)
            cornerRadius = dp(activity, 16)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, if (selected) R.color.colorPrimaryLight else R.color.button_secondary_bg)
            )
            strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(activity, if (selected) R.color.colorPrimaryMedium else R.color.md_border_soft)
            )
            strokeWidth = dp(activity, 1)
            setTextColor(ContextCompat.getColor(activity, R.color.textPrimary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 10) }
            setOnClickListener {
                saveLanguage(activity, languageCode)
                applyLanguage(languageCode)
                dialog.dismiss()
                onSelected?.invoke()
            }
        }
    }

    private fun saveLanguage(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageCode)
            .apply()
    }

    private fun applyLanguage(languageCode: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageCode)
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
