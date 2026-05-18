package com.example.linejoiner

import kotlinx.coroutines.flow.MutableStateFlow

data class PendingItem(
    val url: String,
    val reason: String
)

object JoinerState {
    val queue = MutableStateFlow<List<String>>(emptyList())
    val pending = MutableStateFlow<List<PendingItem>>(emptyList())
    val joined = MutableStateFlow<List<String>>(emptyList())
    val currentLink = MutableStateFlow<String?>(null)
    val countdown = MutableStateFlow(0)
    val totalCountdown = MutableStateFlow(0)
    val isRunning = MutableStateFlow(false)
    val statusMessage = MutableStateFlow("")

    // 設定
    val autoClickEnabled = MutableStateFlow(true)

    // 自動點按服務回報的狀態
    // 由 LineAutoClickService 設定,JoinerService 讀取
    val autoClickResult = MutableStateFlow<AutoClickResult>(AutoClickResult.UNKNOWN)
    val joinSuccessDetected = MutableStateFlow(false)

    fun addPending(url: String, reason: String) {
        pending.value = pending.value + PendingItem(url, reason)
    }

    fun resetForNewRun() {
        joined.value = emptyList()
        pending.value = emptyList()
        autoClickResult.value = AutoClickResult.UNKNOWN
        joinSuccessDetected.value = false
    }
}

enum class AutoClickResult {
    UNKNOWN,             // 還沒結果
    JOINED_SUCCESS,      // 成功加入(偵測到聊天室)
    NEED_ANSWER_QUESTION,// 需回答問題 → 丟待處理
    UNKNOWN_ERROR        // 其他未知狀況
}
