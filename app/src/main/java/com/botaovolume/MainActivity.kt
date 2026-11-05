package com.botaovolume

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Tela de configurações do botão flutuante. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var overlayButton: Button
    private lateinit var silentButton: Button
    private val swatches = mutableListOf<Pair<View, Int>>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- Montagem da tela ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Botão de Volume"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(statusText)

        overlayButton = Button(this).apply {
            text = "Permitir aparecer sobre outros apps"
            setOnClickListener { openOverlaySettings() }
        }
        root.addView(overlayButton)

        silentButton = Button(this).apply {
            text = "Permitir controlar o modo silencioso"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        root.addView(silentButton)

        toggleButton = Button(this).apply {
            textSize = 17f
            setOnClickListener { toggleService() }
        }
        root.addView(toggleButton)

        // --- Aparência ---
        root.addView(section("Aparência"))

        root.addView(slider(
            label = { "Tamanho: $it dp" },
            min = 32, max = 100,
            value = prefs.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE)
        ) { prefs.edit().putInt(Prefs.SIZE, it).apply() })

        root.addView(slider(
            label = { "Opacidade: $it%" },
            min = 20, max = 100,
            value = prefs.getInt(Prefs.OPACITY, Prefs.DEFAULT_OPACITY)
        ) { prefs.edit().putInt(Prefs.OPACITY, it).apply() })

        root.addView(TextView(this).apply {
            text = "Cor"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(8))
        })
        root.addView(colorPicker())

        // --- Comportamento ---
        root.addView(section("Comportamento"))

        root.addView(switch(
            "Toque duplo liga/desliga o silencioso",
            prefs.getBoolean(Prefs.DOUBLE_TAP, true)
        ) { prefs.edit().putBoolean(Prefs.DOUBLE_TAP, it).apply() })

        root.addView(switch(
            "Fixar botão (não deixa arrastar)",
            prefs.getBoolean(Prefs.FIXED, false)
        ) { prefs.edit().putBoolean(Prefs.FIXED, it).apply() })

        root.addView(TextView(this).apply {
            text = "Como usar:\n" +
                "• Toque no botão: abre os controles − e + do volume\n" +
                "• Toque duplo: modo silencioso (se ativado acima)\n" +
                "• Segure e arraste: muda o botão de lugar\n" +
                "• As mudanças aparecem no botão na hora"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(28), 0, dp(4))
    }

    private fun slider(
        label: (Int) -> String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val text = TextView(this).apply {
            textSize = 16f
            text = label(value)
        }
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    text.text = label(p)
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(text)
        box.addView(bar)
        return box
    }

    private fun colorPicker(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in Prefs.COLORS) {
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(10)
                }
                setOnClickListener {
                    prefs.edit().putInt(Prefs.COLOR, color).apply()
                    refreshSwatches()
                }
            }
            swatches.add(swatch to color)
            row.addView(swatch)
        }
        refreshSwatches()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun refreshSwatches() {
        val selected = prefs.getInt(Prefs.COLOR, Prefs.DEFAULT_COLOR)
        for ((view, color) in swatches) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (color == selected) setStroke(dp(4), Color.BLACK)
                else setStroke(dp(1), Color.LTGRAY)
            }
        }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = checked
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }

    // ---------- Ações ----------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun toggleService() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val intent = Intent(this, FloatingService::class.java)
        if (FloatingService.running) {
            stopService(intent)
        } else {
            startForegroundService(intent)
        }
        toggleButton.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        val nm = getSystemService(NotificationManager::class.java)
        val canSilent = nm.isNotificationPolicyAccessGranted

        overlayButton.visibility = if (canOverlay) View.GONE else View.VISIBLE
        silentButton.visibility = if (canSilent) View.GONE else View.VISIBLE

        val running = FloatingService.running
        toggleButton.text = if (running) "Desligar botão" else "Ligar botão"
        statusText.text = when {
            !canOverlay -> "Primeiro, permita que o app apareça sobre outros apps."
            running -> "✅ O botão está na tela."
            else -> "O botão está desligado."
        }
    }
}
