package com.v4atu.dopaminesuppressor.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.NumberPicker
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.v4atu.dopaminesuppressor.R
import com.v4atu.dopaminesuppressor.data.AppLimit

/** Editor de límite diario con selectores de horas y minutos y un interruptor (SPEC §7.3). */
object LimitEditorDialog {

    private const val DEFAULT_MINUTES = 30

    fun show(
        context: Context,
        row: AppRow,
        onSave: (AppLimit) -> Unit,
        onRemove: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_limit, null)
        val hours = view.findViewById<NumberPicker>(R.id.picker_hours)
        val minutes = view.findViewById<NumberPicker>(R.id.picker_minutes)
        val enabled = view.findViewById<MaterialSwitch>(R.id.switch_enabled)

        val current = row.limit
        val total = current?.dailyLimitMinutes ?: DEFAULT_MINUTES
        hours.minValue = 0
        hours.maxValue = 23
        hours.value = total / 60
        minutes.minValue = 0
        minutes.maxValue = 59
        minutes.value = total % 60
        enabled.isChecked = current?.enabled ?: true

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.editor_title, row.label))
            .setView(view)
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.editor_save, null)
        if (current != null) builder.setNeutralButton(R.string.editor_remove) { _, _ -> onRemove() }

        val dialog = builder.create()
        dialog.setOnShowListener {
            // El botón se maneja aparte para no cerrar el diálogo cuando el límite es inválido.
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val limitMinutes = hours.value * 60 + minutes.value
                if (limitMinutes <= 0) {
                    Toast.makeText(context, R.string.editor_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    onSave(AppLimit(row.packageName, limitMinutes, enabled.isChecked))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
