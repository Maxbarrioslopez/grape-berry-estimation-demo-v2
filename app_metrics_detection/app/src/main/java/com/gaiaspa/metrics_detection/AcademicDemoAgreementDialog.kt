package com.gaiaspa.metrics_detection

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Mandatory academic demonstration agreement shown on every app launch.
 *
 * The dialog is non-cancelable: the user must either accept the terms by
 * checking the checkbox and pressing "Continue", or exit the app via "Exit".
 *
 * Acceptance is **session-only** — it lives in memory for the current app
 * process and is never persisted. Closing/killing the app requires re-acceptance.
 */
object AcademicDemoAgreementDialog {

    /** In-memory flag: valid only for the current app process. */
    var hasBeenAccepted: Boolean = false
        private set

    /**
     * Shows the agreement dialog on [activity].
     *
     * @param activity The calling Activity (must be in the foreground).
     * @param onAccepted Called when the user checks the box and taps "Continue".
     * @param onExit Called when the user taps "Exit". The activity should call finish().
     */
    fun show(
        activity: Activity,
        onAccepted: () -> Unit,
        onExit: () -> Unit
    ) {
        // Reset flag on every show; acceptance is never cached.
        hasBeenAccepted = false

        val context = activity
        val body = context.getString(R.string.demo_agreement_body)

        val dialogView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)

            val bodyText = android.widget.TextView(context).apply {
                text = body
                setTextColor(Color.parseColor("#2B2B2B"))
                textSize = 14f
                setLineSpacing(4f, 1.1f)
            }
            addView(bodyText)

            val spacer = android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24
                )
            }
            addView(spacer)

            val checkBox = android.widget.CheckBox(context).apply {
                id = android.R.id.checkbox
                text = context.getString(R.string.demo_agreement_checkbox)
                setTextColor(Color.parseColor("#3D3D3D"))
                textSize = 14f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            }
            addView(checkBox)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.demo_agreement_title))
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(context.getString(R.string.demo_agreement_continue)) { _, _ ->
                hasBeenAccepted = true
                onAccepted()
            }
            .setNegativeButton(context.getString(R.string.demo_agreement_exit)) { _, _ ->
                onExit()
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.setTextColor(Color.parseColor("#808080"))

            val checkbox = dialog.findViewById<android.widget.CheckBox>(android.R.id.checkbox)
            checkbox?.setOnCheckedChangeListener { _, isChecked ->
                positiveButton.isEnabled = isChecked
                positiveButton.setTextColor(
                    if (isChecked) Color.parseColor("#2E7D32") else Color.parseColor("#808080")
                )
            }
        }

        dialog.show()
    }

    /**
     * Resets the in-memory acceptance flag. Called when the app process is
     * killed or when explicitly resetting for testing.
     */
    fun reset() {
        hasBeenAccepted = false
    }
}
