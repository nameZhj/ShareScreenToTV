package com.sharescreen.receiver.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sharescreen.receiver.R

class TvDialogBuilder(private val context: Context) {
    private var title: String? = null
    private var message: String? = null
    private val items = mutableListOf<String>()
    private var selectedIndex = -1
    private var onItemClick: ((Int) -> Unit)? = null
    private var positiveText: String? = null
    private var onPositiveClick: (() -> Unit)? = null
    private var isPositiveDanger: Boolean = false
    private var negativeText: String? = null
    private var onNegativeClick: (() -> Unit)? = null

    fun setTitle(title: String) = apply { this.title = title }
    fun setMessage(message: String) = apply { this.message = message }

    fun setItems(items: Array<String>, onClick: (Int) -> Unit) = apply {
        this.items.clear()
        this.items.addAll(items)
        this.onItemClick = onClick
    }

    fun setSingleChoiceItems(items: Array<String>, checkedItem: Int, onClick: (Int) -> Unit) = apply {
        this.items.clear()
        this.items.addAll(items)
        this.selectedIndex = checkedItem
        this.onItemClick = onClick
    }

    fun setPositiveButton(text: String, isDanger: Boolean = false, onClick: () -> Unit) = apply {
        this.positiveText = text
        this.isPositiveDanger = isDanger
        this.onPositiveClick = onClick
    }

    fun setNegativeButton(text: String, onClick: (() -> Unit)? = null) = apply {
        this.negativeText = text
        this.onNegativeClick = onClick
    }

    fun show(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_tv_custom, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.optionsContainer)
        val buttonsContainer = view.findViewById<LinearLayout>(R.id.buttonsContainer)
        val btnPositive = view.findViewById<Button>(R.id.btnPositive)
        val btnNegative = view.findViewById<Button>(R.id.btnNegative)

        tvTitle.text = title ?: ""
        if (message != null) {
            tvMessage.text = message
            tvMessage.visibility = View.VISIBLE
        } else {
            tvMessage.visibility = View.GONE
        }

        var firstFocusableView: View? = null

        // Add menu items with native TV focus selector
        for (i in items.indices) {
            val itemText = items[i]
            val itemView = inflater.inflate(R.layout.item_tv_dialog_option, optionsContainer, false) as TextView
            val isSelected = (i == selectedIndex)
            itemView.text = if (isSelected) "✔  $itemText" else itemText
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.03f).scaleY(1.03f).translationZ(12f).setDuration(120).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }

            itemView.setOnClickListener {
                dialog.dismiss()
                onItemClick?.invoke(i)
            }
            optionsContainer.addView(itemView)

            if (isSelected || (firstFocusableView == null && selectedIndex == -1)) {
                firstFocusableView = itemView
            }
        }

        // Positive button
        if (positiveText != null) {
            btnPositive.text = positiveText
            btnPositive.visibility = View.VISIBLE
            if (isPositiveDanger) {
                btnPositive.setBackgroundResource(R.drawable.btn_danger_tv_selector)
            } else {
                btnPositive.setBackgroundResource(R.drawable.btn_tv_selector)
            }
            btnPositive.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(12f).setDuration(120).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }
            btnPositive.setOnClickListener {
                dialog.dismiss()
                onPositiveClick?.invoke()
            }
            if (firstFocusableView == null) firstFocusableView = btnPositive
        } else {
            btnPositive.visibility = View.GONE
        }

        // Negative button
        if (negativeText != null) {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
            btnNegative.setBackgroundResource(R.drawable.btn_tv_selector)
            btnNegative.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(12f).setDuration(120).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }
            btnNegative.setOnClickListener {
                dialog.dismiss()
                onNegativeClick?.invoke()
            }
            if (firstFocusableView == null) firstFocusableView = btnNegative
        } else {
            btnNegative.visibility = View.GONE
        }

        if (positiveText == null && negativeText == null) {
            buttonsContainer.visibility = View.GONE
        }

        dialog.show()
        firstFocusableView?.post {
            firstFocusableView.requestFocus()
        }
        return dialog
    }
}
