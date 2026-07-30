package www.xdyl.hygge.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen() {
    var targetModsDir by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp)
    ) {
        // 标题
        Column {
            Text(
                "Nebula updater-NU",
                color = Color(0xFFA0C4FF),
                fontSize = 40.sp
            )
            Text(
                "星云更新器",
                color = Color(0xFFA0C4FF).copy(alpha = 0.8f),
                fontSize = 28.sp
            )
        }
        Spacer(Modifier.height(32.dp))
        // 选择目录按钮
        Button(
            onClick = {
                // 平台相关调用，例如选择目录
                // 在 iOS 上可以触发文件选择器
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFA0C4FF),
                contentColor = Color.Black
            )
        ) {
            Text("选择游戏目录", fontSize = 28.sp)
        }
        Spacer(Modifier.height(20.dp))
        // 开始下载按钮
        Button(
            onClick = {
                downloading = true
                // 下载逻辑
            },
            enabled = targetModsDir.isNotEmpty() && !downloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFA0C4FF),
                contentColor = Color.Black
            )
        ) {
            Text("开始下载", fontSize = 28.sp)
        }
        Spacer(Modifier.height(24.dp))
        // 进度条
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = Color(0xFFA0C4FF),
            trackColor = Color(0xFFA0C4FF).copy(alpha = 0.2f)
        )
        Text(statusText, color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        // 日志区域
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scrollState = rememberScrollState()
            Text(
                logText,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(8.dp)
                    .fillMaxWidth(),
                fontSize = 18.sp,
                color = Color.LightGray,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip
            )
        }
    }
}
