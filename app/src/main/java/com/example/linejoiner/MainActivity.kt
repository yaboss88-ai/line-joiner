package com.example.linejoiner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 從浮動視窗權限頁面回來,什麼都不做
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 通知權限結果
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 申請通知權限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF06C755),
                    secondary = Color(0xFF00B900)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LineJoinerApp(
                        onRequestOverlay = { requestOverlayPermission() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            // 找不到設定頁,試其他方式
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineJoinerApp(
    onRequestOverlay: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current

    // UI 輸入狀態 (本地)
    var linksInput by remember { mutableStateOf("") }
    var minDelay by remember { mutableStateOf("100") }
    var maxDelay by remember { mutableStateOf("300") }

    // Service 狀態 (共享)
    val queue by JoinerState.queue.collectAsState()
    val pending by JoinerState.pending.collectAsState()
    val joined by JoinerState.joined.collectAsState()
    val currentLink by JoinerState.currentLink.collectAsState()
    val countdown by JoinerState.countdown.collectAsState()
    val totalCountdown by JoinerState.totalCountdown.collectAsState()
    val isRunning by JoinerState.isRunning.collectAsState()
    val statusMessage by JoinerState.statusMessage.collectAsState()
    val autoClickEnabled by JoinerState.autoClickEnabled.collectAsState()

    // 權限狀態
    val canOverlay = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(context)
        )
    }

    // 每次組件重組時檢查權限
    LaunchedEffect(Unit) {
        canOverlay.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "LINE 社群批次加入",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF06C755)
        )
        Text(
            "自動排隊 · 隨機間隔 · 一鍵全自動",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
        )

        // 權限提醒卡片
        if (!canOverlay.value || !autoClickEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "⚠️ 首次使用,請開啟兩個權限",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                    Spacer(Modifier.height(8.dp))

                    if (!canOverlay.value) {
                        Button(
                            onClick = onRequestOverlay,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D))
                        ) {
                            Text("1. 開啟浮動視窗權限")
                        }
                        Text(
                            "倒數計時會浮在 LINE 上面,讓你隨時看到",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D))
                    ) {
                        Text("2. 開啟自動點按服務")
                    }
                    Text(
                        "找「LINE 社群批次加入」開啟。開了之後 APP 才能自動幫你按「建立個人檔案並加入」",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 輸入區
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("📋 社群連結(每行一個)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = linksInput,
                    onValueChange = { linksInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = {
                        Text(
                            "https://line.me/ti/g/...\n" +
                            "https://line.me/ti/p/...",
                            fontSize = 12.sp
                        )
                    },
                    enabled = !isRunning,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Text(
                    "目前共 ${linksInput.lines().count { it.isNotBlank() }} 個連結",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 設定區
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("⏱ 間隔秒數(隨機)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = minDelay,
                        onValueChange = { v -> minDelay = v.filter { it.isDigit() } },
                        label = { Text("最少秒") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning,
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = maxDelay,
                        onValueChange = { v -> maxDelay = v.filter { it.isDigit() } },
                        label = { Text("最多秒") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning,
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoClickEnabled,
                        onCheckedChange = { JoinerState.autoClickEnabled.value = it },
                        enabled = !isRunning
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("自動按「加入」鈕", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "需先開啟輔助功能權限才有效",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 控制按鈕
        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val links = linksInput.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (links.isNotEmpty()) {
                        val intent = Intent(context, JoinerService::class.java).apply {
                            action = JoinerService.ACTION_START
                            putStringArrayListExtra(JoinerService.EXTRA_LINKS, ArrayList(links))
                            putExtra(JoinerService.EXTRA_MIN_DELAY, minDelay.toIntOrNull() ?: 100)
                            putExtra(JoinerService.EXTRA_MAX_DELAY, maxDelay.toIntOrNull() ?: 300)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                enabled = !isRunning && linksInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
            ) {
                Text("🚀 一鍵開始", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(context, JoinerService::class.java).apply {
                        action = JoinerService.ACTION_STOP
                    }
                    context.startService(intent)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                enabled = isRunning
            ) {
                Text("⏹ 停止", fontSize = 16.sp)
            }
        }

        if (statusMessage.isNotEmpty()) {
            Text(
                statusMessage,
                fontSize = 12.sp,
                color = Color(0xFF06C755),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 倒數顯示
        if (currentLink != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("📍 正在處理", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        currentLink ?: "",
                        fontSize = 11.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$countdown",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    }
                    Text(
                        "秒後自動跳下一個",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    if (totalCountdown > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (totalCountdown - countdown).toFloat() / totalCountdown
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            color = Color(0xFF06C755)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val intent = Intent(context, JoinerService::class.java).apply {
                                action = JoinerService.ACTION_SKIP_TO_PENDING
                            }
                            context.startService(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373))
                    ) {
                        Text("❌ 無法加入 → 待處理", fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 統計
        Row(Modifier.fillMaxWidth()) {
            StatCard("剩餘", queue.size, Color(0xFF1976D2), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("已加入", joined.size, Color(0xFF388E3C), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatCard("待處理", pending.size, Color(0xFFD32F2F), Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // 待處理區塊 - 可選取文字框
        if (pending.isNotEmpty()) {
            val pendingText = pending.joinToString("\n\n") { item ->
                "# ${item.reason}\n${item.url}"
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚠️ 待處理 (${pending.size})",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { JoinerState.pending.value = emptyList() }) {
                            Text("清空", color = Color(0xFFC62828))
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // 可選取的文字框 (跟輸入框一樣可以複製貼上)
                    OutlinedTextField(
                        value = pendingText,
                        onValueChange = { /* readonly, but allows selection */ },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 280.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // 操作按鈕
                    Row {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                val urls = pending.joinToString("\n") { it.url }
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("待處理連結", urls)
                                )
                                android.widget.Toast.makeText(
                                    context,
                                    "已複製 ${pending.size} 個連結",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 複製連結", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // 把待處理的全部丟回輸入框
                                val newInput = pending.joinToString("\n") { it.url }
                                linksInput = if (linksInput.isBlank()) newInput
                                else "$linksInput\n$newInput"
                                JoinerState.pending.value = emptyList()
                                android.widget.Toast.makeText(
                                    context,
                                    "已放回輸入框",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D))
                        ) {
                            Text("🔄 放回輸入框", fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun StatCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 12.sp, color = color)
            Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
