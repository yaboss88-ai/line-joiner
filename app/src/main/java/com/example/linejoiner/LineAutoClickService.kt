package com.example.linejoiner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent

class LineAutoClickService : AccessibilityService() {

    companion object {
        const val LINE_PACKAGE = "jp.naver.line.android"

        @Volatile
        var serviceInstance: LineAutoClickService? = null

        private const val TRIGGER_COOLDOWN_MS = 30_000L

        private const val DELAY_OPENWITHLINE_MS = 2_000L
        private const val BTN_OPENWITHLINE_X = 0.50f
        private const val BTN_OPENWITHLINE_Y = 0.78f

        private const val DELAY_JOIN_PROFILE_MS = 6_000L
        private const val BTN_JOIN_PROFILE_X = 0.50f
        private const val BTN_JOIN_PROFILE_Y = 0.88f

        private const val DELAY_JOIN_BUTTON_MS = 9_000L
        private const val BTN_JOIN_X = 0.93f
        private const val BTN_JOIN_Y = 0.07f

        private const val DELAY_CONFIRM_MS = 12_000L
        private const val BTN_CONFIRM_X = 0.50f
        private const val BTN_CONFIRM_Y = 0.58f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != LINE_PACKAGE) return

        if (!JoinerState.autoClickEnabled.value) return
        if (!JoinerState.isRunning.value) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < TRIGGER_COOLDOWN_MS) return
        lastTriggerTime = now

        toast("✓ 偵測到 LINE,開始 4 連點")

        handler.postDelayed({
            clickAtRatio(BTN_OPENWITHLINE_X, BTN_OPENWITHLINE_Y, "以LINE開啟")
        }, DELAY_OPENWITHLINE_MS)

        handler.postDelayed({
            clickAtRatio(BTN_JOIN_PROFILE_X, BTN_JOIN_PROFILE_Y, "建立個人檔案並加入")
        }, DELAY_JOIN_PROFILE_MS)

        handler.postDelayed({
            clickAtRatio(BTN_JOIN_X, BTN_JOIN_Y, "加入(右上)")
        }, DELAY_JOIN_BUTTON_MS)

        handler.postDelayed({
            clickAtRatio(BTN_CONFIRM_X, BTN_CONFIRM_Y, "確定")
        }, DELAY_CONFIRM_MS)
    }

    private fun clickAtRatio(xRatio: Float, yRatio: Float, label: String) {
        try {
            val dm = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val x = (dm.widthPixels * xRatio)
            val y = (dm.heightPixels * yRatio)
            performClickAt(x, y, label)
        } catch (e: Exception) {
            toast("⚠️ 點按失敗: ${e.message}")
        }
    }

    private fun performClickAt(x: Float, y: Float, label: String) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                toast("✓ 已點「$label」")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                toast("⚠️ 「$label」被取消")
            }
        }, null)

        if (!ok) {
            toast("⚠️ 派送手勢失敗")
        }
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun resetForNewLink() {
        lastTriggerTime = 0L
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        toast("✓ 自動點按服務已啟動")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}
