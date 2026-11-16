package com.llyytt.path.feature.offline

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
 * 离线地图说明页。
 *
 * 这是一个「如实告知」页，不是功能页：
 * 本 App 不提供离线包下载，只有高德 SDK 自带的底图缓存。
 * 文案刻意写得保守——不承诺"离线可用"，只说"可能看到部分已缓存区域"。
 */
@Composable
fun OfflineInfoScreen(onBack: () -> Unit) {
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
                Text(text = "离线地图", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "本 App 不提供离线地图包下载。",
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "高德地图 SDK 会在你浏览地图时缓存底图。因此：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Bullet("弱网或短暂断网时，能看到部分已缓存的区域")
                Bullet("不保证完整，也不保证是最新的")
                Bullet("长时间没有网络，地图可能变成空白")

                Spacer(Modifier.height(20.dp))

                SectionTitle("给个实用建议")
                Text(
                    text = "出发前在有网络的地方把要去的那片区域大致浏览一遍，" +
                        "途中弱网时能多看到一点。这不等于离线地图，只是提高一点可用性。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))

                SectionTitle("为什么不做下载功能")
                Text(
                    text = "本 App 的离线能力只依赖 SDK 自身的缓存机制（等级 CACHE_ONLY），" +
                        "不自建瓦片下载、不管理离线包。这样不会占掉几百 MB 存储，" +
                        "也不用处理「下载到一半换城市」这类状态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))

                SectionTitle("你找不到的东西（明确说明）")
                Text(
                    text = "没有城市列表、没有下载/暂停/更新/删除按钮。这不是藏起来了，是真的没有做。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
