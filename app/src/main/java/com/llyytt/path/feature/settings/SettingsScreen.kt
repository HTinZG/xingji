package com.llyytt.path.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.llyytt.path.XjApp
import com.llyytt.path.data.ai.UrlValidator
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 两组：显示偏好（瞬时速度开关）和 AI 服务（BaseURL / 模型 / Key）。
 * Key 输入框永远回显空，只提示"已保存"——避免明文在屏幕上长期停留。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenOfflineInfo: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val scope = rememberCoroutineScope()

    var showSpeed by remember { mutableStateOf(app.settings.showInstantSpeed) }

    val config by app.aiSettings.config.collectAsState(initial = null)
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingHttpUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config) {
        val c = config
        if (!initialized && c != null) {
            baseUrl = c.baseUrl
            model = c.model
            initialized = true
        }
    }

    fun doSave(url: String) {
        scope.launch {
            runCatching {
                app.aiSettings.save(url, model.trim(), keyInput.trim())
            }.onSuccess {
                baseUrl = url
                keyInput = ""
                status = "已保存"
            }.onFailure {
                status = "保存失败：${it.message}"
            }
        }
    }

    fun save() {
        status = null
        when (val outcome = UrlValidator.validate(baseUrl)) {
            is UrlValidator.Outcome.Invalid -> status = outcome.reason
            is UrlValidator.Outcome.Https -> doSave(outcome.normalized)
            is UrlValidator.Outcome.Http -> pendingHttpUrl = outcome.normalized
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(text = "设置", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                // ---------- 显示 ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示瞬时速度", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "记录页显示实时速度，回放时显示该时刻速度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showSpeed,
                        onCheckedChange = {
                            showSpeed = it
                            app.settings.showInstantSpeed = it
                        },
                    )
                }

                Text(
                    text = "默认关闭。室外步行精度验证通过前，建议先关着——" +
                        "室内没有卫星信号时打开会一直显示「等待定位」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOfflineInfo() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("离线地图说明", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "弱网时能看到多少、为什么不做下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivacy() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐私说明", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "数据存在哪、AI 请求发到哪、怎么删",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // ---------- AI ----------
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("AI 服务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "OpenAI 兼容接口。Key 用系统级加密存本机，不写日志、不进导出文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    // 当前已保存了什么——不显示 Key 明文，只说"已保存"
                    val saved = config
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "当前已保存",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (saved == null || !saved.isReady) {
                                Text(
                                    text = "还没有配置。填下面的三项并保存即可。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text(
                                    text = "BaseURL：${saved.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "模型：${saved.model}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Key：已保存（加密存储，不显示原文）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            app.aiSettings.clear()
                                            baseUrl = ""
                                            model = ""
                                            keyInput = ""
                                            status = "已清除 AI 配置"
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text("清除配置", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; status = null },
                        label = { Text("BaseURL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; status = null },
                        label = { Text("模型名") },
                        placeholder = { Text("gpt-4o-mini") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; status = null },
                        label = { Text("API Key") },
                        placeholder = {
                            Text(if (config?.apiKey?.isNotBlank() == true) "已保存，留空表示不修改" else "sk-...")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { save() }) { Text("保存") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = status.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == "已保存") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    pendingHttpUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingHttpUrl = null },
            title = { Text("这是明文传输") },
            text = {
                Text(
                    "http:// 不加密。你和服务器之间的任何一跳都能看到 Key 和行程内容，" +
                        "公共 Wi-Fi 下尤其危险。\n\n确定要用 $url 吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingHttpUrl = null
                    doSave(url)
                }) { Text("我知道风险，继续") }
            },
            dismissButton = {
                TextButton(onClick = { pendingHttpUrl = null }) { Text("改用 https") }
            },
        )
    }
}
