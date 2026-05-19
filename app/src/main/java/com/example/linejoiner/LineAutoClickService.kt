package com.example.linejoiner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LineAutoClickService : AccessibilityService() {

    companion object {
        const val LINE_PACKAGE = "jp.naver.line.android"

        @Volatile
        var serviceInstance: LineAutoClickService? = null

        val JOIN_PROFILE_TEXTS = listOf(
            "建立個人檔案並加入", "建立個人資料並加入", "建立檔案並加入"
        )
        val JOIN_BUTTON_TEXTS = listOf("加入", "Join", "참여", "参加")
        val CONFIRM_TEXTS = listOf("確定", "OK", "確認", "확인")
        val MULTI_ROOM_TITLE_TEXTS = listOf("可加入的聊天室", "可加入的聊天室列表")
        val NEXT_BUTTON_TEXTS = listOf("下一步", "Next", "다음", "次へ")
        val QUESTION_KEYWORDS = listOf(
            "請先回答上方的問題", "請先回答", "回答上方的問題",
            "輸入答案才可送出加入申請"
        )
        val CHAT_ROOM_KEYWORDS = listOf("加入聊天室列表", "點選聊天室列表")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L
    private val CLICK_COOLDOWN_MS = 1500L
    private var clickedFirstRoomFor: String? = null
    private var debugEventToastShown = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != LINE_PACKAGE) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        if (!debugEventToastShown) {
            toast("📨 收到 LINE 事件")
            debugEventToastShown = true
        }

        if (!JoinerState.autoClickEnabled.value) {
            toast("⚠️ 自動點按開關關閉")
            return
        }
        if (!JoinerState.isRunning.value) {
            toast("⚠️ isRunning=false")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return

        handler.postDelayed({ analyzeAndAct() }, 800)
    }

    private fun analyzeAndAct() {
        val root = rootInActiveWindow ?: run {
            toast("⚠️ 找不到畫面")
            return
        }

        if (containsAnyText(root, QUESTION_KEYWORDS)) {
            JoinerState.autoClickResult.value = AutoClickResult.NEED_ANSWER_QUESTION
            return
        }

        for (t in JOIN_PROFILE_TEXTS) {
            if (findAndClickByContains(root, t)) {
                lastClickTime = System.currentTimeMillis()
                toast("✓ 已點「$t」")
                return
            }
        }

        if (containsAnyText(root, listOf("社群使用小提醒", "禁止事項", "建議事項", "開心使用"))) {
            for (t in CONFIRM_TEXTS) {
                if (findAndClickByContains(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    toast("✓ 已點「$t」")
                    return
                }
            }
        }

        if (containsAnyText(root, listOf(
                "您可以設定要在此社群中使用的暱稱",
                "此社群中使用的暱稱",
                "社群專屬個人檔案",
                "在此社群中使用的暱稱及個人圖片"))) {
            for (t in JOIN_BUTTON_TEXTS) {
                if (findAndClickByContains(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    toast("✓ 已點「$t」")
                    return
                }
            }
        }

        if (containsAnyText(root, MULTI_ROOM_TITLE_TEXTS)) {
            handleMultiRoomPage(root)
            return
        }

        if (containsAnyText(root, CHAT_ROOM_KEYWORDS)) {
            JoinerState.autoClickResult.value = AutoClickResult.JOINED_SUCCESS
            JoinerState.joinSuccessDetected.value = true
            return
        }
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun handleMultiRoomPage(root: AccessibilityNodeInfo) {
        val currentUrl = JoinerState.currentLink.value
        if (clickedFirstRoomFor == currentUrl) {
            for (t in NEXT_BUTTON_TEXTS) {
                if (findAndClickByContains(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    toast("✓ 多群組:已點「$t」")
                    return
                }
            }
            return
        }

        val firstClickable = findFirstClickableRoomItem(root)
        if (firstClickable != null) {
            firstClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickedFirstRoomFor = currentUrl
            lastClickTime = System.currentTimeMillis()
            toast("✓ 多群組:已選第 1 個")
        }
    }

    private fun findFirstClickableRoomItem(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        return findClickableWithNumber(node)
    }

    private fun findClickableWithNumber(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) {
            val text = collectTextRecursive(node)
            if (Regex("\\(\\d+\\)").containsMatchIn(text) &&
                !text.contains("加入聊天室列表") &&
                !text.contains("加入聊天")) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val r = findClickableWithNumber(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 5) return ""
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            sb.append(collectTextRecursive(node.getChild(i), depth + 1))
        }
        return sb.toString()
    }

    private fun containsAnyText(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (nodes.isNotEmpty()) return true
        }
        return false
    }

    private fun findAndClickByContains(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (clickNodeOrParent(node)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    fun resetForNewLink() {
        clickedFirstRoomFor = null
        lastClickTime = 0L
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        debugEventToastShown = false
        toast("✓ 自動點按服務已啟動")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}
