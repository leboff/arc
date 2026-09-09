package com.daydreamvr.player

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * The pre-flight "lobby" (ARCHITECTURE.md §11.6) — the one 2D touch screen,
 * shown before the phone goes in the viewer. Phase 1 is a stub: a title, a hint,
 * and one big **Enter VR** button that any gamepad `A` also activates.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (resources.displayMetrics.density * 24).toInt()

        val title = TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 24f
        }
        val subtitle = TextView(this).apply {
            setText(R.string.setup_subtitle)
            textSize = 14f
            setPadding(0, padding, 0, padding * 2)
        }
        val enterVr = Button(this).apply {
            id = ENTER_VR_ID
            setText(R.string.enter_vr)
            setOnClickListener { enterVr() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding * 2, padding * 2, padding * 2, padding * 2)
            addView(title, wrap())
            addView(subtitle, wrap())
            addView(enterVr, wrap())
        }
        setContentView(layout)

        enterVr.requestFocus()
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in CONFIRM_KEYS) {
            enterVr()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterVr() {
        startActivity(Intent(this, VrActivity::class.java))
    }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val ENTER_VR_ID = 0x7F5E0001

        val CONFIRM_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
    }
}
