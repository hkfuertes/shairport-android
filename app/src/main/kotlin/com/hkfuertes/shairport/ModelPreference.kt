package com.hkfuertes.shairport

import android.app.AlertDialog
import android.content.Context
import android.preference.ListPreference
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView

/**
 * The advertised-model list, with each model's icon (R.array.model_icons, one per model) next to its name.
 * The icons are black-on-transparent SF Symbols, so they are tinted with the row's text color.
 */
@Suppress("DEPRECATION") // framework preferences, like the rest of the settings screen
class ModelPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    private val icons: List<Int> = context.resources.obtainTypedArray(R.array.model_icons).let { array ->
        try {
            List(array.length()) { array.getResourceId(it, 0) }
        } finally {
            array.recycle()
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val size = (32 * context.resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<CharSequence>(context, R.layout.model_choice, android.R.id.text1, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as CheckedTextView
                val icon = context.getDrawable(icons[position])?.mutate()?.apply {
                    setTint(row.currentTextColor)
                    setBounds(0, 0, size, size)
                }
                row.setCompoundDrawablesRelative(icon, null, null, null)
                return row
            }
        }
        builder.setSingleChoiceItems(adapter, findIndexOfValue(value)) { dialog, which ->
            val newValue = entryValues[which].toString()
            if (callChangeListener(newValue)) value = newValue
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
    }
}
