package com.pipidu.tiny1b.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import com.pipidu.tiny1b.core.DisplayRotation
import com.pipidu.tiny1b.core.FrameGenScale
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.core.Palettes
import com.pipidu.tiny1b.data.AppCache
import com.pipidu.tiny1b.data.AppSettings
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.ui.formatTemp
import com.pipidu.tiny1b.ui.isHardwareLive
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
    onFrameGen: (FrameGenScale) -> Unit,
    onShowCenter: (Boolean) -> Unit,
    onShowMinMax: (Boolean) -> Unit,
    onMirror: (Boolean) -> Unit,
    onRotation: (DisplayRotation) -> Unit,
    onFahrenheit: (Boolean) -> Unit,
    onSample: (Boolean) -> Unit,
    onDenoiseAmount: (Int) -> Unit,
    onMarkerOpacity: (Int) -> Unit,
    onSharpen: (Int) -> Unit,
    onSpanFixed: (Boolean) -> Unit,
    onSpanLow: (Float) -> Unit,
    onSpanHigh: (Float) -> Unit,
    onPalette: (PaletteId) -> Unit,
    onShutter: () -> Unit,
    onShutterMax: (Int) -> Unit,
    onKbCal: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallPermission: () -> Intent,
    onInstall: () -> Intent?,
    onClearCache: () -> String,
) {
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf(AppCache.sizeBytes(context)) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
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
                Text("色板", color = Ink, fontSize = 14.sp)
                Text("伪彩色只作用在热成像画面上。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                PalettePicker(selected = state.palette, onSelect = onPalette)
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "固定上下限",
                    "打开后色板按你设定的温度范围上色，超出范围夹到两端。关闭则每帧自动拉伸。",
                    state.spanFixed,
                    onSpanFixed,
                )
                if (state.spanFixed) {
                    Text(
                        "下限  ${formatTemp(state.spanLowC, state.useFahrenheit)}",
                        color = Ink,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = state.spanLowC,
                        onValueChange = onSpanLow,
                        valueRange = AppSettings.SPAN_MIN_C..(state.spanHighC - AppSettings.SPAN_MIN_GAP_C),
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = SurfaceMuted,
                        ),
                    )
                    Text(
                        "上限  ${formatTemp(state.spanHighC, state.useFahrenheit)}",
                        color = Ink,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = state.spanHighC,
                        onValueChange = onSpanHigh,
                        valueRange = (state.spanLowC + AppSettings.SPAN_MIN_GAP_C)..AppSettings.SPAN_MAX_C,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = SurfaceMuted,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                Text("帧生成", color = Ink, fontSize = 14.sp)
                Text(
                    "模组约 25fps 时，2× 插到约 50fps，3× 插到约 75fps（不超过屏幕刷新）。插帧按等间隔排在两帧之间，不是下一帧到了再一次性补。厂商 demo 没有此项。默认关闭；只有模组帧率已经达到屏幕刷新时才不再插。",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameGenScale.entries.forEach { scale ->
                        val active = state.frameGen == scale
                        TextButton(
                            onClick = { onFrameGen(scale) },
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
                Text(
                    if (state.denoiseAmount <= 0) "降噪  关闭" else "降噪  ${state.denoiseAmount}%",
                    color = Ink,
                    fontSize = 14.sp,
                )
                Text(
                    "默认关闭。可分离 3 点中值，只作用显示画面，测温仍读未滤波的原生温度网格。0 为关；越高越接近满强度滤波。",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Slider(
                    value = state.denoiseAmount.toFloat(),
                    onValueChange = { onDenoiseAmount(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = SurfaceMuted,
                    ),
                )
                Text(
                    if (state.sharpenAmount <= 0) "锐化  关闭" else "锐化  ${state.sharpenAmount}%",
                    color = Ink,
                    fontSize = 14.sp,
                )
                Text(
                    "默认关闭。只锐化显示用的热图，不改测温用的开尔文网格。",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Slider(
                    value = state.sharpenAmount.toFloat(),
                    onValueChange = { onSharpen(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = SurfaceMuted,
                    ),
                )
            }
            Section("测温") {
                ToggleRow("中心测温点", "始终显示画面中心温度", state.showCenter, onShowCenter)
                ToggleRow("最高 / 最低温", "在画面上标注全幅极值位置", state.showMinMax, onShowMinMax)
                ToggleRow("使用华氏度", "界面温度改为 °F", state.useFahrenheit, onFahrenheit)
                Text("标注透明度  ${state.markerOpacity}%", color = Ink, fontSize = 14.sp)
                Text("作用于中心、最高、最低温的十字和标签。自定义测温点保持不透明，方便编辑。", color = Muted, fontSize = 12.sp)
                Slider(
                    value = state.markerOpacity.toFloat(),
                    onValueChange = { onMarkerOpacity(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = SurfaceMuted,
                    ),
                )
            }
            Section("模组") {
                Text("手动快门", color = Ink, fontSize = 14.sp)
                Text("对焦或画面发糊时点一次，触发模组快门校正。仅在已连接时生效。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onShutter,
                    enabled = isHardwareLive(state.status),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("快门校正") }
                Spacer(Modifier.height(8.dp))
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
                Text("手动快门在上方；KB 二次标定仅在已连接时生效。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onKbCal(true) }) { Text("开启 KB 标定", color = Accent) }
                    TextButton(onClick = { onKbCal(false) }) { Text("关闭 KB 标定", color = Muted) }
                }
            }
            Section("预览与诊断") {
                ToggleRow(
                    "样例画面",
                    "无模组时生成合成热图，便于调试色板与画面。不会代替真机取流。快门和测温仍需连接 Tiny1-B。",
                    state.samplePreview,
                    onSample,
                )
            }
            Section("存储") {
                Text("软件缓存  ${AppCache.formatSize(cacheBytes)}", color = Ink, fontSize = 14.sp)
                Text(
                    "实时画面最多 3 张轮换位图；插帧只保留最近两帧；更新安装包只留一份。清理不会中断 USB 取流。相册照片和录像不会删除。",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        cacheMessage = onClearCache()
                        cacheBytes = AppCache.sizeBytes(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("清除缓存") }
                val msg = cacheMessage
                if (!msg.isNullOrBlank()) {
                    Text(msg, color = Live, fontSize = 13.sp)
                }
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
private fun PalettePicker(selected: PaletteId, onSelect: (PaletteId) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Palettes.all()) { palette ->
            val active = palette.id == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, if (active) Accent else Outline, RoundedCornerShape(14.dp))
                    .background(if (active) AccentSoft else SurfaceMuted)
                    .clickable { onSelect(palette.id) }
                    .padding(8.dp)
                    .width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val colors = listOf(0, 80, 160, 255).map { Color(palette.lut[it]) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(colors)),
                )
                Spacer(Modifier.height(6.dp))
                Text(palette.id.labelZh, color = if (active) Accent else Ink, fontSize = 12.sp)
            }
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
