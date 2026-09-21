package com.pipidu.tiny1b.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.pipidu.tiny1b.core.DisplayRotation
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.ui.theme.Accent
import com.pipidu.tiny1b.ui.theme.AccentSoft
import com.pipidu.tiny1b.ui.theme.Hot
import com.pipidu.tiny1b.ui.theme.Ink
import com.pipidu.tiny1b.ui.theme.Live
import com.pipidu.tiny1b.ui.theme.Muted
import com.pipidu.tiny1b.ui.theme.Outline
import com.pipidu.tiny1b.ui.theme.Paper
import com.pipidu.tiny1b.ui.theme.Surface
import com.pipidu.tiny1b.ui.theme.SurfaceMuted
import com.pipidu.tiny1b.update.UpdateStatus

@Composable
fun SettingsScreen(
    state: EngineState,
    updateStatus: UpdateStatus,
    currentVersion: String,
    currentVersionCode: Int,
    onBack: () -> Unit,
    onIsr: (IsrScale) -> Unit,
    onShowCenter: (Boolean) -> Unit,
    onShowMinMax: (Boolean) -> Unit,
    onMirror: (Boolean) -> Unit,
    onRotation: (DisplayRotation) -> Unit,
    onFahrenheit: (Boolean) -> Unit,
    onSample: (Boolean) -> Unit,
    onDenoise: (Boolean) -> Unit,
    onShutterMax: (Int) -> Unit,
    onKbCal: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallPermission: () -> Intent,
    onInstall: () -> Intent?,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Text("设置", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("画面") {
                Text("软件 ISR 超分辨率", color = Ink, fontSize = 14.sp)
                Text("温度场双线性放大后叠亮度细节。比旧版双三次快很多，测温仍在旋转后的原生网格上取样。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IsrScale.entries.forEach { scale ->
                        val active = state.isr == scale
                        TextButton(
                            onClick = { onIsr(scale) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) AccentSoft else SurfaceMuted),
                        ) {
                            Text(scale.labelZh, color = if (active) Accent else Ink)
                        }
                    }
                }
                Text("画面旋转", color = Ink, fontSize = 14.sp)
                Text("顺时针旋转实时画面与测温点，下次启动仍保持该角度。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayRotation.entries.forEach { rotation ->
                        val active = state.rotation == rotation
                        TextButton(
                            onClick = { onRotation(rotation) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) AccentSoft else SurfaceMuted),
                        ) {
                            Text(rotation.labelZh, color = if (active) Accent else Ink)
                        }
                    }
                }
                ToggleRow("水平镜像", "左右翻转实时画面", state.mirror, onMirror)
                ToggleRow(
                    "降噪",
                    "默认关闭。用中值滤波去掉画面散斑；测温仍读取未滤波的原生温度网格。",
                    state.denoise,
                    onDenoise,
                )
            }
            Section("测温") {
                ToggleRow("中心测温点", "始终显示画面中心温度", state.showCenter, onShowCenter)
                ToggleRow("最高 / 最低温", "在画面上标注全幅极值位置", state.showMinMax, onShowMinMax)
                ToggleRow("使用华氏度", "界面温度改为 °F", state.useFahrenheit, onFahrenheit)
            }
            Section("模组") {
                Text("自动快门最大间隔  ${state.shutterMaxSeconds} 秒", color = Ink, fontSize = 14.sp)
                Slider(
                    value = state.shutterMaxSeconds.toFloat(),
                    onValueChange = { onShutterMax(it.toInt()) },
                    valueRange = 5f..120f,
                    steps = 22,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = SurfaceMuted,
                    ),
                )
                Text("手动快门请在实时画面点「快门」。KB 二次标定仅在已连接时生效。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onKbCal(true) }) { Text("开启 KB 标定", color = Accent) }
                    TextButton(onClick = { onKbCal(false) }) { Text("关闭 KB 标定", color = Muted) }
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
            Section("更新") {
                Text("当前版本  $currentVersion  ($currentVersionCode)", color = Ink, fontSize = 14.sp)
                Text("从 GitHub Releases（pipidu/TINY1-B）检查新版本，由你手动下载安装，不会强制更新。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                when (val status = updateStatus) {
                    UpdateStatus.Idle -> {
                        Button(
                            onClick = onCheckUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("检查更新") }
                    }
                    UpdateStatus.Checking -> Text("正在检查…", color = Muted, fontSize = 13.sp)
                    UpdateStatus.UpToDate -> {
                        Text("已是最新版本", color = Live, fontSize = 13.sp)
                        TextButton(onClick = onCheckUpdate) { Text("重新检查", color = Accent) }
                    }
                    is UpdateStatus.Available -> {
                        Text("发现新版本  ${status.release.version}", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (status.release.body.isNotBlank()) {
                            Text(status.release.body.take(240), color = Muted, fontSize = 12.sp)
                        }
                        var downloadStarted by remember(status.release.version) { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (downloadStarted) return@Button
                                downloadStarted = true
                                onDownloadUpdate()
                            },
                            enabled = !downloadStarted,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(if (downloadStarted) "正在开始下载…" else "下载并安装") }
                    }
                    is UpdateStatus.Downloading -> {
                        Text("正在下载  ${(status.progress * 100).toInt()}%", color = Ink, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                            color = Accent,
                            trackColor = SurfaceMuted,
                        )
                    }
                    is UpdateStatus.NeedsPermission -> {
                        Text("需要允许安装未知应用，才能安装下载的 APK。", color = Accent, fontSize = 13.sp)
                        Button(
                            onClick = { context.startActivity(onInstallPermission()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("去授权") }
                    }
                    is UpdateStatus.Ready -> {
                        Text("已下载 ${status.release.version}，可以安装。", color = Live, fontSize = 13.sp)
                        Button(
                            onClick = { onInstall()?.let { context.startActivity(it) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("立即安装") }
                    }
                    is UpdateStatus.Error -> {
                        Text(status.message, color = Hot, fontSize = 13.sp)
                        TextButton(onClick = onCheckUpdate) { Text("重试", color = Accent) }
                    }
                }
            }
            Section("关于") {
                Text("TINY1-B 热成像  $currentVersion", color = Ink, fontSize = 14.sp)
                Text("Infiray Tiny1-B · USB VID 0BDA / PID 3901 · 256×384 YUYV 叠温", color = Muted, fontSize = 12.sp)
                Text("USB 取流使用厂商 demo 的 libUVCCamera（Java 包 com.zz.infisense.camera）。应用 ID 仍是 com.pipidu.tiny1b。", color = Muted, fontSize = 12.sp)
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
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
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
            Text(title, color = Ink, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = SurfaceMuted,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Outline,
            ),
        )
    }
}
