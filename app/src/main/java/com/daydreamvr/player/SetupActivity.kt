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
import com.daydreamvr.player.data.OpticsSettingsResolver
import com.daydreamvr.player.media.local.MediaPermission
import com.daydreamvr.vrcore.profile.DeviceProfiles
import kotlinx.coroutines.launch

/**
 * The pre-flight "lobby" (ARCHITECTURE.md §11.6) — the 2D touch screen shown
 * before the phone goes in the viewer. Displays discovery status, discovered servers,
 * an input field to add/test a server IP with touch, and the "Enter VR" button.
 */
class SetupActivity : ComponentActivity() {

    private lateinit var mediaAccessRow: TextView
    private lateinit var profileBtn: Button
    private lateinit var rootLayout: LinearLayout
    private var crashBanner: android.view.View? = null

    private val container by lazy { (application as PlayerApp).container }

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

        profileBtn = Button(this).apply {
            textSize = 14f
            text = "Viewer Profile: ${container.deviceProfile.displayName}"
            setOnClickListener {
                lifecycleScope.launch {
                    val current = container.settingsStore.current()
                    val updated = OpticsSettingsResolver.cycleProfile(current, 1)
                    container.settingsStore.save(updated)
                    container.deviceProfile = OpticsSettingsResolver.resolveDeviceProfile(updated)
                    text = "Viewer Profile: ${container.deviceProfile.displayName}"
                    Toast.makeText(this@SetupActivity, "Set profile to ${container.deviceProfile.displayName}", Toast.LENGTH_SHORT).show()
                }
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
                    setPadding(0, padding / 4, 0, padding / 4)
                },
                wrap(),
            )
            addView(profileBtn, wrap())
            addView(
                TextView(this@SetupActivity).apply {
                    setPadding(0, padding / 4, 0, padding / 4)
                },
                wrap(),
            )
            addView(enterVr, wrap())
        }
        rootLayout = layout
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

    override fun onResume() {
        super.onResume()
        showCrashBannerIfPresent()
        if (::profileBtn.isInitialized) {
            profileBtn.text = "Viewer Profile: ${container.deviceProfile.displayName}"
        }
    }

    /**
     * If the previous session crashed, [PlayerApp] persisted the stack trace to
     * `last_crash.txt`. Surface it in the lobby so a headset crash is not silent.
     */
    private fun showCrashBannerIfPresent() {
        crashBanner?.let { rootLayout.removeView(it); crashBanner = null }

        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (!crashFile.exists() || crashFile.length() == 0L) return

        val crashText = runCatching { crashFile.readText() }.getOrNull().orEmpty()
        val padding = (resources.displayMetrics.density * 12).toInt()

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF7A1F1F.toInt())
            setPadding(padding, padding, padding, padding)
            addView(
                TextView(this@SetupActivity).apply {
                    text = "The previous session crashed"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                },
                wrap(),
            )
            addView(
                TextView(this@SetupActivity).apply {
                    text = crashText.take(4000)
                    setTextColor(0xFFFFE0E0.toInt())
                    textSize = 10f
                    setPadding(0, padding / 2, 0, padding / 2)
                },
                wrap(),
            )
            addView(
                Button(this@SetupActivity).apply {
                    text = "Clear Crash Log"
                    setOnClickListener {
                        crashFile.delete()
                        showCrashBannerIfPresent()
                    }
                },
                wrap(),
            )
        }
        rootLayout.addView(banner, 0, wrap())
        crashBanner = banner
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
