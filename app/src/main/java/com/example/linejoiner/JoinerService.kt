package com.example.linejoiner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class JoinerService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SKIP_TO_PENDING = "ACTION_SKIP_TO_PENDING"
        const val EXTRA_LINKS = "links"
        const val EXTRA_MIN_DELAY = "min"
        const val EXTRA_MAX_DELAY = "max"

        const val CHANNEL_ID = "linejoiner_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var processJob: Job? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var countdownText: TextView? = null
    private var statusText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val links = intent.getStringArrayListExtra(EXTRA_LINKS) ?: arrayListOf()
                val minDelay = intent.getIntExtra(EXTRA_MIN_DELAY, 100)
                val maxDelay = intent.getIntExtra(EXTRA_MAX_DELAY, 300)
                startProcess(links, minDelay, maxDelay)
            }
            ACTION_STOP -> stopProcess()
            ACTION_SKIP_TO_PENDING -> skipCurrentToPending()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LINE Joiner 服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "顯示加入社群進度"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, JoinerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startProcess(links: List<String>, minDelay: Int, maxDelay: Int) {
        if (links.isEmpty()) {
            stopSelf()
            return
        }

        JoinerState.queue.value = links
        JoinerState.resetForNewRun()
        JoinerState.isRunning.value = true

        startForeground(NOTIFICATION_ID, buildNotification("LINE Joiner 運行中", "準備處理 ${links.size} 個連結"))
        showFloatingWindow()

        processJob = scope.launch {
            processQueue(minDelay, maxDelay)
        }
    }

    private suspend fun processQueue(minDelay: Int, maxDelay: Int) {
        val remaining = JoinerState.queue.value.toMutableList()

        while (remaining.isNotEmpty() && JoinerState.isRunning.value) {
            val link = remaining.removeAt(0)
            JoinerState.queue.value = remaining.toList()
            JoinerState.currentLink.value = link

            // 重置自動點按狀態
            JoinerState.autoClickResult.value = AutoClickResult.UNKNOWN
            JoinerState.joinSuccessDetected.value = false
            LineAutoClickService.serviceInstance?.resetForNewLink()

            JoinerState.statusMessage.value = "正在開啟 LINE..."
            val opened = openLineLink(link)
            if (!opened) {
                JoinerState.addPending(link, "無法開啟 LINE")
                JoinerState.currentLink.value = null
                delay(1500)
                continue
            }

            JoinerState.statusMessage.value = "等待加入流程中..."

            // 開始隨機倒數
            val min = minDelay.coerceAtLeast(1)
            val max = maxDelay.coerceAtLeast(min)
            val seconds = Random.nextInt(min, max + 1)
            JoinerState.totalCountdown.value = seconds
            JoinerState.countdown.value = seconds
            updateFloatingDisplay()
            updateNotification("加入中 ($seconds 秒)", shortLink(link))

            var remainingSeconds = seconds
            var earlyExitReason: String? = null

            while (remainingSeconds > 0 && JoinerState.isRunning.value) {
                delay(1000)
                remainingSeconds--
                JoinerState.countdown.value = remainingSeconds
                updateFloatingDisplay()
                if (remainingSeconds % 10 == 0 || remainingSeconds <= 5) {
                    updateNotification("加入中 ($remainingSeconds 秒)", shortLink(link))
                }

                // === 提早結束的判斷 ===
                when (JoinerState.autoClickResult.value) {
                    AutoClickResult.NEED_ANSWER_QUESTION -> {
                        earlyExitReason = "需要回答問題"
                        break
                    }
                    AutoClickResult.JOINED_SUCCESS -> {
                        // 偵測到聊天室,但等個幾秒讓動作穩定
                        if (JoinerState.joinSuccessDetected.value &&
                            remainingSeconds < seconds - 8) {
                            // 至少跑了 8 秒以上才認定為成功(避免誤判)
                            break
                        }
                    }
                    else -> {}
                }
            }

            if (!JoinerState.isRunning.value) {
                JoinerState.currentLink.value?.let {
                    JoinerState.addPending(it, "手動停止")
                }
                break
            }

            // 結束時判斷狀態
            val finalResult = JoinerState.autoClickResult.value
            when {
                earlyExitReason == "需要回答問題" || finalResult == AutoClickResult.NEED_ANSWER_QUESTION -> {
                    JoinerState.addPending(link, "需要回答問題")
                }
                finalResult == AutoClickResult.JOINED_SUCCESS -> {
                    JoinerState.joined.value = JoinerState.joined.value + link
                }
                else -> {
                    // 倒數結束但沒偵測到成功
                    if (JoinerState.joinSuccessDetected.value) {
                        JoinerState.joined.value = JoinerState.joined.value + link
                    } else {
                        JoinerState.addPending(link, "無法處理或未知狀況")
                    }
                }
            }
            JoinerState.currentLink.value = null
        }

        JoinerState.isRunning.value = false
        JoinerState.currentLink.value = null
        JoinerState.statusMessage.value = if (JoinerState.queue.value.isEmpty()) "✅ 全部處理完成" else "已停止"
        hideFloatingWindow()

        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        } catch (_: Exception) {}

        updateNotification("LINE Joiner", JoinerState.statusMessage.value)
        scope.launch {
            delay(3000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun skipCurrentToPending() {
        val cur = JoinerState.currentLink.value ?: return
        JoinerState.addPending(cur, "手動移入")
        JoinerState.currentLink.value = null
        JoinerState.countdown.value = 0
        JoinerState.statusMessage.value = "已移入待處理"
    }

    private fun stopProcess() {
        JoinerState.isRunning.value = false
        processJob?.cancel()
        hideFloatingWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openLineLink(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                intent.setPackage("jp.naver.line.android")
                startActivity(intent)
                true
            } catch (e: Exception) {
                intent.setPackage(null)
                startActivity(intent)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun shortLink(url: String): String =
        if (url.length > 40) url.substring(0, 40) + "..." else url

    // ============ 浮動視窗 ============

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
    }

    private fun showFloatingWindow() {
        if (!canDrawOverlays() || floatingView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC06C755.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        countdownText = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        statusText = TextView(this).apply {
            text = "等待中"
            setTextColor(0xFFE8F5E9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        }

        container.addView(countdownText)
        container.addView(statusText)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(80)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX - (event.rawX - touchX)).toInt()
                    params.y = (initialY + (event.rawY - touchY)).toInt()
                    try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
            floatingView = container
        } catch (e: Exception) {
            floatingView = null
        }
    }

    private fun updateFloatingDisplay() {
        scope.launch(Dispatchers.Main) {
            countdownText?.text = JoinerState.countdown.value.toString()
            val done = JoinerState.joined.value.size
            val queue = JoinerState.queue.value.size
            val current = if (JoinerState.currentLink.value != null) 1 else 0
            statusText?.text = "${done}/${done + queue + current}"
        }
    }

    private fun hideFloatingWindow() {
        try { floatingView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingView = null
        countdownText = null
        statusText = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingWindow()
        scope.cancel()
    }
}
