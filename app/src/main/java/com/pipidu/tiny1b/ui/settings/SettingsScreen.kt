package com.pipidu.tiny1b.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.ui.theme.Ember
import com.pipidu.tiny1b.ui.theme.Ink
import com.pipidu.tiny1b.ui.theme.InkElevated
import com.pipidu.tiny1b.ui.theme.Mist
import com.pipidu.tiny1b.ui.theme.Sand

@Composable
fun SettingsScreen(
    state: EngineState,
    onBack: () -> Unit,
    onIsr: (IsrScale) -> Unit,
    onShowCenter: (Boolean) -> Unit,
    onShowMinMax: (Boolean) -> Unit,
    onMirror: (Boolean) -> Unit,
    onFahrenheit: (Boolean) -> Unit,
    onSample: (Boolean) -> Unit,
    onShutterMax: (Int) -> Unit,
    onKbCal: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Sand)
            }
            Text("设置", color = Sand, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("画面") {
                Text("软件 ISR 超分辨率", color = Sand, fontSize = 14.sp)
                Text("在温度场双三次放大后叠加亮度细节，测量仍在原生 192×256 网格上取样。", color = Mist, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IsrScale.entries.forEach { scale ->
                        val active = state.isr == scale
                        TextButton(
                            onClick = { onIsr(scale) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) Ember.copy(alpha = 0.2f) else Ink),
                        ) {
                            Text(scale.labelZh, color = if (active) Ember else Sand)
                        }
                    }
                }
                ToggleRow("水平镜像", "左右翻转实时画面", state.mirror, onMirror)
            }
            Section("测温") {
                ToggleRow("中心测温点", "始终显示画面中心温度", state.showCenter, onShowCenter)
                ToggleRow("最高 / 最低温", "在画面上标注全幅极值位置", state.showMinMax, onShowMinMax)
                ToggleRow("使用华氏度", "界面温度改为 °F", state.useFahrenheit, onFahrenheit)
            }
            Section("模组") {
                Text("自动快门最大间隔  ${state.shutterMaxSeconds} 秒", color = Sand, fontSize = 14.sp)
                Slider(
                    value = state.shutterMaxSeconds.toFloat(),
                    onValueChange = { onShutterMax(it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10,
                )
                Text("手动快门请在实时画面点「快门」。KB 二次标定仅在已连接时生效。", color = Mist, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onKbCal(true) }) { Text("开启 KB 标定", color = Ember) }
                    TextButton(onClick = { onKbCal(false) }) { Text("关闭 KB 标定", color = Mist) }
                }
            }
            Section("预览与诊断") {
                ToggleRow(
                    "样例画面",
                    "无模组时生成合成热图，便于调试色板与测温点。不会代替真机取流。",
                    state.samplePreview,
                    onSample,
                )
            }
            Section("关于") {
                Text("TINY1-B 热成像  1.0.0", color = Sand, fontSize = 14.sp)
                Text("Infiray Tiny1-B · USB VID 0BDA / PID 3901 · 256×384 YUYV 叠温", color = Mist, fontSize = 12.sp)
                Text("本应用为独立产品，不含厂商 demo 工程。", color = Mist, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Ember, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sand, fontSize = 15.sp)
            Text(subtitle, color = Mist, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ember),
        )
    }
}
