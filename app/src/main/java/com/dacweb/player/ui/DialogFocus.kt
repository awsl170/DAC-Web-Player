package com.dacweb.player.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.dacweb.player.R

/**
 * 遥控器没有触摸反馈：对话框底部按钮默认只有涟漪，焦点落在哪个按钮上看不出来。
 *
 * 注意不要直接换 background——MaterialButton 的底色是 backgroundTintList 画的，
 * 换掉 background 会把按钮画没/画歪；这里改「底色 + 文字色」。
 *
 * 另外把两个按钮的左右关系固定下来：电视上按「下」到最后一个输入框之后焦点落到
 * 「取消」，用户很难想到再按一次右键才能到「连接」。
 */
fun AlertDialog.focusButtons(lastField: View? = null) {
    val fill = ContextCompat.getColor(context, R.color.palette_purple)
    val onFill = Color.WHITE
    val buttons = mutableListOf<View>()

    for (id in intArrayOf(
        AlertDialog.BUTTON_POSITIVE,
        AlertDialog.BUTTON_NEGATIVE,
        AlertDialog.BUTTON_NEUTRAL
    )) {
        val b = getButton(id) ?: continue
        b.isFocusable = true
        b.isFocusableInTouchMode = false
        buttons += b
        val tint = b.backgroundTintList
        val textColor = b.textColors?.let { ColorStateList.valueOf(b.currentTextColor) }
        b.setOnFocusChangeListener { v, hasFocus ->
            v.backgroundTintList = if (hasFocus) ColorStateList.valueOf(fill) else tint
            (v as? TextView)?.let { t ->
                if (hasFocus) {
                    t.setTextColor(onFill)
                } else if (textColor != null) {
                    t.setTextColor(textColor)
                }
            }
        }
    }

    // 让「连接」紧挨在「取消」右边：左/右互通，按「下」直接落到主按钮
    val positive = getButton(AlertDialog.BUTTON_POSITIVE)
    val negative = getButton(AlertDialog.BUTTON_NEGATIVE)
    if (positive != null && negative != null) {
        negative.nextFocusRightId = positive.id
        positive.nextFocusLeftId = negative.id
        negative.nextFocusDownId = positive.id
        positive.nextFocusDownId = positive.id
        // 最后一个输入框按「下」直接进主按钮，不用先经过「取消」
        lastField?.nextFocusDownId = positive.id
    }
}
