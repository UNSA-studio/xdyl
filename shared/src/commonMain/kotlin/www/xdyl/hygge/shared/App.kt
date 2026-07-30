package www.xdyl.hygge.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen() {
    var targetModsDir by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Nebula updater-NU 星云更新器")
        Button(onClick = {
            // 选择目录逻辑（平台相关）
        }) {
            Text("选择游戏目录")
        }
        Button(onClick = { /* 开始下载 */ }) {
            Text("开始下载")
        }
        // 其他组件...
    }
}
