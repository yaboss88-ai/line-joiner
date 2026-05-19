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

        private const val DELAY_BUTTON_1_MS = 2000L
        private const val DELAY_BUTTON_2_MS = 5000L
        private const val DELAY_BUTTON_3_MS = 8000L

        private const val BTN1_X_RATIO = 0.50f
        private const val BTN1_Y_RATIO = 0.88f
        private const val BTN2_X_RATIO = 0.93f
        private const val BTN2_Y_RATIO = 0.07f
        private const val BTN3_X_RATIO = 0.50f
        private const val BTN3_Y_RATIO = 0.58f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L
    private var debugConnectToastShown = false

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

        toast("✓ 進入 LINE,開始自動加入")

        handler.postDelayed({ clickAtRatio(BTN1_X_RATIO, BTN1_Y_RATIO, "建立個人檔案並加入") }, DELAY_BUTTON_1_MS)
        handler.postDelayed({ clickAtRatio(BTN2_X_RATIO, BTN2_Y_RATIO, "加入(右上)") }, DELAY_BUTTON_2_MS)
        handler.postDelayed({ clickAtRatio(BTN3_X_RATIO, BTN3_Y_RATIO, "確定") }, DELAY_BUTTON_3_MS)
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
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
            toast("⚠️ 無法派送手勢")
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
        if (!debugConnectToastShown) {
            toast("✓ 自動點按服務已啟動(座標版)")
            debugConnectToastShown = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}
