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

        // 按鈕文字 (繁中為主,加上常見變體)
        val JOIN_PROFILE_TEXTS = listOf(
            "建立個人檔案並加入", "建立個人資料並加入", "建立檔案並加入"
        )
        // 個人檔案頁右上角的「加入」按鈕
        val JOIN_BUTTON_TEXTS = listOf(
            "加入", "Join", "참여", "参加"
        )
        // 社群使用小提醒的「確定」
        val CONFIRM_TEXTS = listOf(
            "確定", "OK", "確認", "확인"
        )
        // 多群組選擇頁標題
        val MULTI_ROOM_TITLE_TEXTS = listOf(
            "可加入的聊天室", "可加入的聊天室列表"
        )
        // 「下一步」按鈕(多群組頁面右上)
        val NEXT_BUTTON_TEXTS = listOf(
            "下一步", "Next", "다음", "次へ"
        )
        // 偵測「需要回答問題」的關鍵字
        val QUESTION_KEYWORDS = listOf(
            "回答問題", "輸入答案", "請先回答", "回答上方的問題",
            "Answer the question", "答案"
        )
        // 偵測「已成功加入聊天室」的特徵
        val CHAT_ROOM_KEYWORDS = listOf(
            "管理員加入聊天", "加入聊天", "加入聊天室", "已加入聊天",
            "目前", "請按右上角"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L
    private val CLICK_COOLDOWN_MS = 1500L
    // 紀錄目前是否已經點過第一個多群組,避免重複點
    private var clickedFirstRoomFor: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!JoinerState.autoClickEnabled.value) return
        if (!JoinerState.isRunning.value) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != LINE_PACKAGE) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return

        // 稍微延遲讓畫面載完
        handler.postDelayed({
            analyzeAndAct()
        }, 800)
    }

    private fun analyzeAndAct() {
        val root = rootInActiveWindow ?: return

        // === 優先級 1: 偵測「需要回答問題」===
        if (containsAnyText(root, QUESTION_KEYWORDS)) {
            JoinerState.autoClickResult.value = AutoClickResult.NEED_ANSWER_QUESTION
            // 不點任何按鈕,讓 JoinerService 看到狀態並跳過
            lastClickTime = System.currentTimeMillis()
            return
        }

        // === 優先級 2: 偵測「已加入聊天室」===
        if (containsAnyText(root, CHAT_ROOM_KEYWORDS)) {
            // 聊天室特徵明顯(訊息泡泡、輸入框等)
            JoinerState.autoClickResult.value = AutoClickResult.JOINED_SUCCESS
            JoinerState.joinSuccessDetected.value = true
            return
        }

        // === 優先級 3: 多群組選擇頁 → 點第一個 + 下一步 ===
        if (containsAnyText(root, MULTI_ROOM_TITLE_TEXTS)) {
            handleMultiRoomPage(root)
            return
        }

        // === 優先級 4: 「社群使用小提醒」彈窗 → 點確定 ===
        if (root.findText("社群使用小提醒") || root.findText("禁止事項")) {
            for (t in CONFIRM_TEXTS) {
                if (findAndClickByExactText(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    return
                }
            }
        }

        // === 優先級 5: 個人檔案頁 → 點右上「加入」===
        if (root.findText("您可以設定要在此社群中使用的暱稱") ||
            root.findText("此社群中使用的暱稱") ||
            root.findText("社群專屬個人檔案")) {
            // 找「加入」按鈕
            for (t in JOIN_BUTTON_TEXTS) {
                if (findAndClickByExactText(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    return
                }
            }
        }

        // === 優先級 6: 社群首頁 → 點「建立個人檔案並加入」===
        for (t in JOIN_PROFILE_TEXTS) {
            if (findAndClickByExactText(root, t)) {
                lastClickTime = System.currentTimeMillis()
                return
            }
        }
    }

    private fun handleMultiRoomPage(root: AccessibilityNodeInfo) {
        val currentUrl = JoinerState.currentLink.value
        // 已經點過了不要再點
        if (clickedFirstRoomFor == currentUrl) {
            // 第二階段:點「下一步」
            for (t in NEXT_BUTTON_TEXTS) {
                if (findAndClickByExactText(root, t)) {
                    lastClickTime = System.currentTimeMillis()
                    return
                }
            }
            return
        }

        // 第一階段:點第一個聊天室
        // 嘗試從 root 遞迴找第一個可點擊的「聊天室項目」
        val firstClickable = findFirstClickableRoomItem(root)
        if (firstClickable != null) {
            firstClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickedFirstRoomFor = currentUrl
            lastClickTime = System.currentTimeMillis()
        }
    }

    /**
     * 在多群組頁找第一個聊天室項目
     * 通常 LINE 用 RecyclerView 或 ListView,每個 item 都有圖示+名稱+人數
     */
    private fun findFirstClickableRoomItem(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        // 在「可加入的聊天室」標題下面找
        // 簡單啟發式:找到第一個可點擊且包含數字(人數)的容器
        return findClickableWithNumber(node)
    }

    private fun findClickableWithNumber(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) {
            val text = collectTextRecursive(node)
            // 條件:包含括號數字 例如 (598) 表示人數
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
            if (root.findText(kw)) return true
        }
        return false
    }

    private fun AccessibilityNodeInfo.findText(text: String): Boolean {
        val nodes = findAccessibilityNodeInfosByText(text)
        return nodes.isNotEmpty()
    }

    private fun findAndClickByExactText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            // 確認是這個文字本身,不只是包含
            val nodeText = node.text?.toString() ?: ""
            if (nodeText == text || nodeText.trim() == text) {
                if (clickNodeOrParent(node)) return true
            }
        }
        // 如果嚴格匹配找不到,放寬條件
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
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}
