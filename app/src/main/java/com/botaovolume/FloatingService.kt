package com.botaovolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Serviço que mantém o botão flutuante na tela, por cima de qualquer app.
 *  - Toque: abre o painel com − e + (e o controle de volume do sistema)
 *  - Toque duplo (opcional): liga/desliga o modo silencioso
 *  - Arrastar: muda o botão de lugar (a menos que esteja fixado)
 */
class FloatingService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL_ID = "botao_volume"
        private const val NOTIF_ID = 1
        private const val PANEL_TIMEOUT_MS = 4000L
    }

    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: FrameLayout
    private lateinit var icon: ImageView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleAttached = false
    private var receiverRegistered = false

    private var panel: View? = null
    private var panelClosedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hidePanelTask = Runnable { removePanel() }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateIcon()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = Prefs.get(this)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        prefs.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ringerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(ringerReceiver, filter)
        }
        receiverRegistered = true

        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(ringerReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        removePanel()
        if (bubbleAttached) {
            try { wm.removeView(bubble) } catch (_: Exception) {}
            bubbleAttached = false
        }
        super.onDestroy()
    }

    // ---------- Notificação obrigatória do serviço em primeiro plano ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Botão de volume", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle("Botão de volume ativo")
            .setContentText("Toque para abrir as configurações")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- Botão flutuante ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createBubble() {
        bubble = FrameLayout(this)
        icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        bubble.addView(
            icon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val size = dp(prefs.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE))
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.POS_X, resources.displayMetrics.widthPixels - size)
            y = prefs.getInt(Prefs.POS_Y, resources.displayMetrics.heightPixels / 3)
        }

        applyStyle()
        setupTouch()

        try {
            wm.addView(bubble, bubbleParams)
            bubbleAttached = true
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível mostrar o botão", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** Aplica tamanho, cor e opacidade escolhidos nas configurações. */
    private fun applyStyle() {
        val size = dp(prefs.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE))
        bubbleParams.width = size
        bubbleParams.height = size

        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(prefs.getInt(Prefs.COLOR, Prefs.DEFAULT_COLOR))
            setStroke(dp(2), 0x66FFFFFF)
        }
        bubble.alpha = prefs.getInt(Prefs.OPACITY, Prefs.DEFAULT_OPACITY) / 100f

        val pad = size / 4
        icon.setPadding(pad, pad, pad, pad)
        updateIcon()

        clampPosition()
        if (bubbleAttached) wm.updateViewLayout(bubble, bubbleParams)
    }

    private fun updateIcon() {
        if (!::icon.isInitialized) return
        val silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        icon.setImageResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume)
    }

    private fun clampPosition() {
        val dm = resources.displayMetrics
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxOf(0, dm.widthPixels - bubbleParams.width))
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxOf(0, dm.heightPixels - bubbleParams.height))
    }

    private fun doubleTapEnabled() = prefs.getBoolean(Prefs.DOUBLE_TAP, true)
    private fun isFixed() = prefs.getBoolean(Prefs.FIXED, false)

    private fun setupTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            // Sem toque duplo: responde na hora, sem esperar
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!doubleTapEnabled()) onTap()
                return true
            }

            // Com toque duplo: espera para ter certeza que foi toque simples
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (doubleTapEnabled()) onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (doubleTapEnabled()) toggleSilent()
                return true
            }
        })

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isFixed() && (dragging || abs(dx) > slop || abs(dy) > slop)) {
                        if (!dragging) {
                            dragging = true
                            removePanel()
                            // Cancela o reconhecimento de toque, já que virou arrasto
                            val cancel = MotionEvent.obtain(ev)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            detector.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampPosition()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit()
                            .putInt(Prefs.POS_X, bubbleParams.x)
                            .putInt(Prefs.POS_Y, bubbleParams.y)
                            .apply()
                    }
                }
            }
            if (!dragging) detector.onTouchEvent(ev)
            true
        }
    }

    private fun onTap() {
        if (panel != null) {
            removePanel()
            return
        }
        // Evita reabrir quando o toque no botão acabou de fechar o painel
        if (SystemClock.uptimeMillis() - panelClosedAt < 700) return
        showPanel()
    }

    // ---------- Volume e modo silencioso ----------

    private fun adjust(direction: Int) {
        try {
            audio.adjustSuggestedStreamVolume(
                direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
            )
        } catch (e: SecurityException) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun toggleSilent() {
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val nm = getSystemService(NotificationManager::class.java)
        try {
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                if (nm.isNotificationPolicyAccessGranted) {
                    audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                    toast("Modo silencioso")
                } else {
                    audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    toast("Modo vibrar (permita o silencioso no app)")
                }
            } else {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                toast("Som ativado")
            }
        } catch (e: SecurityException) {
            toast("Abra o app e permita o acesso ao modo silencioso")
        }
        updateIcon()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Painel com − e + ----------

    private fun showPanel() {
        val buttonSize = dp(56)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(34).toFloat()
                setColor(0xEE202124.toInt())
            }
        }

        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener {
                action()
                scheduleHide()
            }
        }

        row.addView(button("−") { adjust(AudioManager.ADJUST_LOWER) })
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button("+") { adjust(AudioManager.ADJUST_RAISE) })

        val panelWidth = buttonSize * 2 + dp(8) + dp(12)
        val panelHeight = buttonSize + dp(12)
        val screenW = resources.displayMetrics.widthPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Abre ao lado do botão, do lado que tiver espaço
            val right = bubbleParams.x + bubbleParams.width + dp(8)
            x = if (right + panelWidth <= screenW) right
                else maxOf(0, bubbleParams.x - panelWidth - dp(8))
            y = maxOf(0, bubbleParams.y + (bubbleParams.height - panelHeight) / 2)
        }

        row.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                removePanel()
                true
            } else false
        }

        try {
            wm.addView(row, params)
            panel = row
            adjust(AudioManager.ADJUST_SAME) // mostra o controle de volume do sistema
            scheduleHide()
        } catch (_: Exception) {
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hidePanelTask)
        handler.postDelayed(hidePanelTask, PANEL_TIMEOUT_MS)
    }

    private fun removePanel() {
        handler.removeCallbacks(hidePanelTask)
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            panel = null
            panelClosedAt = SystemClock.uptimeMillis()
        }
    }

    // ---------- Reage às mudanças feitas na tela de configurações ----------

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.SIZE, Prefs.COLOR, Prefs.OPACITY -> applyStyle()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubbleAttached) {
            removePanel()
            clampPosition()
            wm.updateViewLayout(bubble, bubbleParams)
        }
    }
}
