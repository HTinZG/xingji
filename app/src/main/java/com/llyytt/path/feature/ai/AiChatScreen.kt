package com.llyytt.path.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llyytt.path.XjApp
import com.llyytt.path.data.ai.AiResult
import com.llyytt.path.data.ai.ChatTurn
import com.llyytt.path.data.ai.TripContextBuilder
import com.llyytt.path.data.db.AiChatMessageEntity
import com.llyytt.path.data.db.ChatRole
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * AI 聊天。
 *
 * 没配 Key 时只显示引导，不发任何请求。
 * 首次发送前会弹一次隐私提示（数据会发到用户自己填的服务商）。
 * 发的内容是摘要，不是全量轨迹点。
 */
@Composable
fun AiChatScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val scope = rememberCoroutineScope()

    val config by app.aiSettings.config.collectAsState(initial = null)
    var messages by remember { mutableStateOf<List<AiChatMessageEntity>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDisclosure by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.aiChatRepository.observe().collect { messages = it }
    }

    fun reallySend(text: String) {
        val cfg = config ?: return
        if (text.isBlank() || sending) return
        scope.launch {
            sending = true
            error = null
            try {
                app.aiChatRepository.addUser(text)

                val trips = app.tripRepository.list(10)
                val latest = trips.firstOrNull()?.let { app.tripRepository.detail(it.trip.id) }
                val tripContext = buildString {
                    appendLine(TripContextBuilder.summarizeTrips(trips))
                    if (latest != null) appendLine(TripContextBuilder.summarizeDetail(latest))
                }

                val turns = buildList {
                    add(ChatTurn("system", TripContextBuilder.systemPrompt() + "\n\n" + tripContext))
                    app.aiChatRepository.recent(12).forEach {
                        add(
                            ChatTurn(
                                role = if (it.role == ChatRole.USER) "user" else "assistant",
                                content = it.content,
                            ),
                        )
                    }
                }

                when (val result = app.aiClient.chat(cfg, turns)) {
                    is AiResult.Success -> app.aiChatRepository.addAssistant(result.text)
                    is AiResult.Failure -> error = result.message
                }
            } catch (t: Throwable) {
                error = t.message ?: "发送失败"
            } finally {
                sending = false
            }
        }
    }

    fun onSendClick() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val cfg = config ?: return
        if (!cfg.disclosureAccepted) {
            pendingText = text
            showDisclosure = true
            return
        }
        input = ""
        reallySend(text)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    text = "AI 助手",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch { app.aiChatRepository.clear() }
                    }) { Text("清空") }
                }
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }

            val cfg = config
            when {
                cfg == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                !cfg.isReady -> GuideContent(onOpenSettings)

                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Text(
                                    text = "问点什么吧，比如「我最近走了多少公里」「哪次骑车最久」。\n" +
                                        "只会把行程摘要发给 ${cfg.baseUrl}，不会发原始轨迹点。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message)
                        }
                    }

                    error?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { error = null }) { Text("知道了") }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val palette = com.llyytt.path.ui.XingjiTheme.palette
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("问点关于行程的问题") },
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = palette.accent,
                                unfocusedIndicatorColor = palette.hairline,
                                cursorColor = palette.accent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onSendClick() },
                            enabled = input.isNotBlank() && !sending,
                        ) {
                            Text(if (sending) "…" else "发送")
                        }
                    }
                }
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false; pendingText = null },
            title = { Text("发出去之前说一声") },
            text = {
                Text(
                    "你的行程摘要（距离、时长、分段等，不含原始轨迹点）会发送到" +
                        "你自己配置的 API 服务商。App 不做任何中转，也不保存这次请求。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.aiSettings.acceptDisclosure()
                        showDisclosure = false
                        val text = pendingText
                        pendingText = null
                        if (text != null) {
                            input = ""
                            reallySend(text)
                        }
                    }
                }) { Text("同意并发送") }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false; pendingText = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GuideContent(onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有配置 AI 服务", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "AI 聊天需要你自己的 API：填一个 OpenAI 兼容的 BaseURL、模型名和 Key。" +
                    "App 不内置、不代理任何模型服务，费用在你的账号上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
private fun MessageBubble(message: AiChatMessageEntity) {
    val isUser = message.role == ChatRole.USER
    val palette = com.llyytt.path.ui.XingjiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                // 用户气泡：实橙底 + 深色文字（浅色模式）/ 近黑文字（深色模式）。
                // 之前的 primaryContainer 浅橙底 + 橙文字在浅色模式下发污。
                containerColor = if (isUser) palette.accent else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
