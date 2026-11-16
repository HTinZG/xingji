package com.llyytt.path.feature.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 隐私说明页。
 *
 * 原则：不写漂亮话，只写"数据到底去哪了"。
 * 每一条都要能被用户自己验证（比如断网后功能是否还能用）。
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
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
                Text(text = "隐私说明", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "一句话：这个 App 没有服务器，你的数据全在你自己手机上。",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(20.dp))

                Section(
                    title = "轨迹只存本机",
                    body = "所有 GPS 点存在手机本地的 SQLite 数据库里，不经过任何服务器。" +
                        "没有账号、没有登录、没有云端同步。卸载 App 数据就没了（除非你自己导出）。",
                )

                Section(
                    title = "AI Key 是加密存的",
                    body = "你填的 API Key 用 Android Keystore 里的 AES-256-GCM 加密后才落盘，" +
                        "解密用的密钥由系统芯片保管，App 自己都读不到明文。" +
                        "换一台手机把这个文件拷过去也解不开。",
                )

                Section(
                    title = "AI 请求发给你配置的服务商",
                    body = "AI 聊天时，App 把**行程摘要**（距离、时长、分段这些）直接发到你填的 BaseURL，" +
                        "不经过我们任何中转。数据怎么处理、留多久，取决于你选的那家服务商的条款。" +
                        "不发原始轨迹点，也不发 Key 以外的任何凭据。",
                )

                Section(
                    title = "导出文件由你控制",
                    body = "导出 GPX/KML 时用系统的文件选择器，存哪个目录、给谁，完全由你决定。" +
                        "导出文件里只有轨迹点、时间、分段方式和停留点——没有 Key、没有 BaseURL、没有聊天记录。",
                )

                Section(
                    title = "不上传服务器",
                    body = "本 App 没有任何自建后端。除高德地图 SDK 加载底图、" +
                        "以及你自己触发的 AI 请求之外，App 不主动联网。",
                )

                Section(
                    title = "用了哪些权限，为什么",
                    body = "定位：记录轨迹，必须。\n" +
                        "前台服务：锁屏后继续记录，必须。\n" +
                        "通知：显示记录状态，必须。\n" +
                        "存储：只在导出和分享时通过系统选择器访问，不扫描你的文件。",
                )

                Section(
                    title = "怎么删数据",
                    body = "卸载 App 即全部清除。想留着轨迹就先导出 GPX/KML 再卸载。" +
                        "AI 设置里的 Key 可以在设置页清空，清空时会连 Keystore 里的密钥一起删掉。",
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = body.replace("**", ""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(18.dp))
}
