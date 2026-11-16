package com.llyytt.path.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首启隐私门禁。
 *
 * 这是硬门槛：用户点「同意并继续」之前，
 * 不允许创建地图引擎、不允许创建任何 MapView、不启动定位。
 * 高德 SDK 的隐私合规接口也要等同意之后才调（见 XjApp.acceptPrivacy）。
 */
@Composable
fun PrivacyGateScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "行迹", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "记录走路、骑车、坐车的轨迹。只存在这台手机里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "三件事请你先知道", style = MaterialTheme.typography.titleMedium)

            PrivacyPoint("轨迹只存在本机", "没有账号、没有云端、没有服务器。删掉 App 数据，轨迹就没了，我们也找不回来。")
            PrivacyPoint("AI Key 加密存储", "AI 聊天要用你自己的 API Key。它用系统级加密存在本机，不会写进日志，也不会出现在导出文件里。")
            PrivacyPoint("不上传任何数据", "App 没有任何自建后端。除了你主动调用 AI 时把行程数据发给你自己填的 API，其他数据一律不出手机。")

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "关于第三方 SDK", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "本 App 使用「高德地图 SDK」显示地图底图。该 SDK 会收集设备标识与位置信息，" +
                    "仅用于地图渲染与定位展示；数据由高德按《高德开放平台隐私政策》处理。" +
                    "不使用地图时不会启动它。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("同意并继续")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onDecline) {
                    Text("不同意，退出")
                }
            }
            Text(
                text = "同意后，随时可以在「我的 → 隐私说明」里再看一遍。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyPoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
