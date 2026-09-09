package com.daydreamvr.player

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.daydreamvr.player.media.local.MediaPermission
import kotlinx.coroutines.launch

/**
 * The pre-flight "lobby" (ARCHITECTURE.md §11.6) — the 2D touch screen shown
 * before the phone goes in the viewer. Displays discovery status, discovered servers,
 * an input field to add/test a server IP with touch, and the "Enter VR" button.
 */
class SetupActivity : ComponentActivity() {

    private lateinit var mediaAccessRow: TextView

    /**
     * The runtime storage permission is requested **here only** — a system dialog
     * raised from inside the headset is unreadable (UI_REDESIGN_REVIEWED_PLAN.md §8.2).
     */
    private val requestMediaAccess =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshMediaAccessRow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PlayerApp).container
        val padding = (resources.displayMetrics.density * 20).toInt()

        val title = TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            setText(R.string.setup_subtitle)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding)
        }

        val serverStatus = TextView(this).apply {
            text = "Searching local network for UPnP servers…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, padding / 2)
        }

        val manualInput = EditText(this).apply {
            hint = "http://192.168.50.10:49152/upnp/description.xml"
            setText("http://192.168.50.10:49152/upnp/description.xml")
            textSize = 14f
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.density * 300).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        val addServerBtn = Button(this).apply {
            text = "Connect / Test Server"
            setOnClickListener {
                val host = manualInput.text.toString().trim()
                if (host.isNotEmpty()) {
                    serverStatus.text = "Connecting to $host…"
                    lifecycleScope.launch {
                        container.mediaServerDirectory.addManual(host).fold(
                            onSuccess = { srv ->
                                runCatching { container.serverStore.remember(srv, manual = true) }
                                serverStatus.text = "✓ Connected to ${srv.friendlyName} ($host)"
                                Toast.makeText(this@SetupActivity, "Added ${srv.friendlyName}", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { err ->
                                serverStatus.text = "✗ Failed: ${err.message}"
                                Toast.makeText(this@SetupActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                }
            }
        }

        mediaAccessRow = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, 0)
        }
        val grantMediaBtn = Button(this).apply {
            text = "Allow access to videos on this phone"
            setOnClickListener {
                val perms = buildList {
                    add(MediaPermission.required())
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    }
                }.toTypedArray()
                requestMediaAccess.launch(perms)
            }
        }

        val enterVr = Button(this).apply {
            id = ENTER_VR_ID
            setText(R.string.enter_vr)
            textSize = 16f
            setPadding(padding, padding / 2, padding, padding / 2)
            setOnClickListener { enterVr() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(title, wrap())
            addView(subtitle, wrap())
            addView(serverStatus, wrap())
            addView(manualInput)
            addView(addServerBtn, wrap())
            addView(mediaAccessRow, wrap())
            addView(grantMediaBtn, wrap())
            addView(
                TextView(this@SetupActivity).apply {
                    setPadding(0, padding / 2, 0, padding / 2)
                },
                wrap(),
            )
            addView(enterVr, wrap())
        }
        setContentView(layout)
        refreshMediaAccessRow()

        // Observe discovered servers
        lifecycleScope.launch {
            container.mediaServerDirectory.servers.collect { list ->
                if (list.isNotEmpty()) {
                    val names = list.joinToString("\n") { "• ${it.friendlyName} (${it.descriptionUrl.host}:${it.descriptionUrl.port})" }
                    serverStatus.text = "Discovered ${list.size} server(s):\n$names"
                }
            }
        }

        // Trigger discovery immediately in background
        lifecycleScope.launch {
            val known = runCatching { container.serverStore.descriptionUrls() }.getOrDefault(emptyList())
            if (known.isNotEmpty()) {
                runCatching { (container.mediaServerDirectory as? com.daydreamvr.upnp.MediaServerDirectoryImpl)?.reprobeKnown(known) }
            }
            container.mediaServerDirectory.discover()
        }

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

    private fun refreshMediaAccessRow() {
        mediaAccessRow.text = when (MediaPermission.status(this)) {
            MediaPermission.Grant.FULL -> "Videos on this phone: Granted"
            MediaPermission.Grant.PARTIAL -> "Videos on this phone: Limited (selected items only)"
            MediaPermission.Grant.DENIED -> "Videos on this phone: Not granted"
        }
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
