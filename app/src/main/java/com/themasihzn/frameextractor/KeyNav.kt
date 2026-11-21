package com.themasihzn.frameextractor

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * Keyboard interface, reusable by any feature of the OS:
 * hold Ctrl and type any text visible on screen (button label, field, hint).
 * The best match is highlighted; release Ctrl (or press Enter) to press it.
 * Backspace edits the query, Esc cancels. OCR of text inside images comes later.
 */
class KeyNav(private val activity: Activity, private val hud: TextView) {
    private var query = ""
    private var match: View? = null

    fun dispatch(e: KeyEvent): Boolean {
        val ctrlKey = e.keyCode == KeyEvent.KEYCODE_CTRL_LEFT || e.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        if (ctrlKey && e.action == KeyEvent.ACTION_UP && query.isNotEmpty()) { activate(); return true }
        if (!e.isCtrlPressed || e.action != KeyEvent.ACTION_DOWN || ctrlKey) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> query = query.dropLast(1)
            KeyEvent.KEYCODE_ESCAPE -> { clear(); return true }
            KeyEvent.KEYCODE_ENTER -> { activate(); return true }
            else -> {
                val c = e.getUnicodeChar(e.metaState and KeyEvent.META_CTRL_MASK.inv())
                if (c == 0) return false
                query += c.toChar()
            }
        }
        update()
        return true
    }

    private fun labelOf(v: View): String {
        val t = (v as? TextView)?.let { tv ->
            tv.text?.toString().takeUnless { it.isNullOrBlank() } ?: tv.hint?.toString()
        }
        return t ?: v.contentDescription?.toString() ?: ""
    }

    private fun collect(v: View, out: MutableList<View>) {
        if (v.visibility != View.VISIBLE || v === hud) return
        if (labelOf(v).isNotBlank()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    private fun update() {
        match?.isPressed = false
        match = null
        if (query.isEmpty()) { hud.visibility = View.GONE; return }
        val all = mutableListOf<View>()
        collect(activity.window.decorView, all)
        val q = query.lowercase()
        val hits = all.filter { labelOf(it).lowercase().contains(q) }
        // Prefer actionable views, then labels that start with the query.
        match = hits.sortedWith(compareBy(
            { !(it.isClickable || it is EditText) },
            { !labelOf(it).lowercase().startsWith(q) }
        )).firstOrNull()
        match?.isPressed = true
        hud.visibility = View.VISIBLE
        hud.text = "⌃ $query → " + (match?.let { labelOf(it).take(24) } ?: "no match")
    }

    private fun activate() {
        val v = match
        clear()
        if (v == null) return
        if (v is EditText) {
            v.requestFocus()
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        var target: View? = v
        while (target != null && !target.isClickable) target = target.parent as? View
        (target ?: v).performClick()
    }

    private fun clear() {
        match?.isPressed = false
        match = null
        query = ""
        hud.visibility = View.GONE
    }
}
