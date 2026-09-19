package io.github.wzhdgithub.liquidglass.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.wzhdgithub.liquidglass.GlassBarItem
import io.github.wzhdgithub.liquidglass.GlassBarSpace
import io.github.wzhdgithub.liquidglass.GlassShell
import io.github.wzhdgithub.liquidglass.isGlassBlurSupported

/**
 * 演示 App：一个可滚动的彩色长列表 + 浅色/深色切换，
 * 用于观察液态玻璃底栏的模糊、边缘折射、按压放大镜与拖动切换。
 *
 * 玻璃不可用（API < 33 或 AGSL 不可用）时，同一个 GlassShell 会自动走 fallbackBar
 * （这里是普通的 Material3 NavigationBar），不需要额外分支。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoApp() }
    }
}

private enum class DemoTab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Explore("发现", Icons.Filled.Search),
    Message("消息", Icons.Filled.Email),
    Profile("我的", Icons.Filled.Person)
}

private val LightScheme = lightColorScheme(primary = Color(0xFF0072E3))
private val DarkScheme = darkColorScheme(primary = Color(0xFF4DA3FF))

@Composable
private fun DemoApp() {
    var dark by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(DemoTab.Home) }
    val glass = isGlassBlurSupported()

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        GlassShell(
            glass = glass,
            darkTheme = dark,
            items = DemoTab.entries.map { GlassBarItem(icon = it.icon, label = it.label) },
            selectedIndex = DemoTab.entries.indexOf(tab),
            onSelect = { tab = DemoTab.entries[it] },
            // 演示不需要 Snackbar
            snackbarHost = {},
            // 玻璃不可用时的兜底底栏：这里直接用 Material3 原生实现
            fallbackBar = {
                NavigationBar {
                    DemoTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = { tab = item },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { _ ->
            // 内容铺满整屏，不额外避让底栏；只在末尾预留 GlassBarSpace，
            // 这样滚动时内容会从浮起的玻璃底栏下方穿过
            DemoPages(
                glass = glass,
                dark = dark,
                onToggleDark = { dark = !dark },
                tab = tab
            )
        }
    }
}

@Composable
private fun DemoPages(
    glass: Boolean,
    dark: Boolean,
    onToggleDark: () -> Unit,
    tab: DemoTab
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(16.dp))
                Text("LiquidGlassBar", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (glass) {
                        "真实玻璃已启用：背景模糊 / 边缘折射 / 色散 / 按压放大镜"
                    } else {
                        "当前设备不支持 RuntimeShader（API < 33 或 AGSL 不可用），已回退为 Material3 底栏"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (glass) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (glass) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "按住底栏左右拖动可切换标签；向上滚动可看到内容从玻璃下方穿过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = dark, onCheckedChange = { onToggleDark() })
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        items(40) { index -> DemoCard(index = index, tab = tab, dark = dark) }

        // 玻璃模式：底栏悬浮在内容之上，末尾必须预留其高度，最后一张卡片才能完整滚出
        if (glass) item { Spacer(Modifier.height(GlassBarSpace)) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DemoCard(index: Int, tab: DemoTab, dark: Boolean) {
    // 彩色卡片：滚动时经过底栏，最容易看清模糊与边缘折射
    val hue = ((index * 29 + tab.ordinal * 71) % 360).toFloat()
    val accent = Color.hsv(hue, 0.55f, if (dark) 0.8f else 0.95f)

    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "${tab.label} · 卡片 ${index + 1}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "彩色内容用于观察底栏的模糊与折射",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}