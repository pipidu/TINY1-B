package com.pipidu.tiny1b.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.core.Palettes
import com.pipidu.tiny1b.device.DeviceStatus
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.ui.formatTemp
import com.pipidu.tiny1b.ui.isLiveLike
import com.pipidu.tiny1b.ui.labelZh
import com.pipidu.tiny1b.ui.theme.Accent
import com.pipidu.tiny1b.ui.theme.AccentSoft
import com.pipidu.tiny1b.ui.theme.Cold
import com.pipidu.tiny1b.ui.theme.Hot
import com.pipidu.tiny1b.ui.theme.Ink
import com.pipidu.tiny1b.ui.theme.Live
import com.pipidu.tiny1b.ui.theme.Muted
import com.pipidu.tiny1b.ui.theme.Outline
import com.pipidu.tiny1b.ui.theme.Paper
import com.pipidu.tiny1b.ui.theme.Surface
import com.pipidu.tiny1b.ui.theme.SurfaceMuted

@Composable
fun LiveViewScreen(
    state: EngineState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onShutter: () -> Unit,
    onPalette: (PaletteId) -> Unit,
    onIsr: (IsrScale) -> Unit,
    onMeasureEdit: (Boolean) -> Unit,
    onAddOrSelect: (Float, Float) -> Unit,
    onBeginDrag: (Float, Float) -> Long?,
    onMoveUser: (Long, Float, Float) -> Unit,
    onRemoveNearest: (Float, Float) -> Unit,
    onRemoveSelected: () -> Unit,
    onClearPoints: () -> Unit,
) {
    var paletteOpen by remember { mutableStateOf(false) }
    val live = isLiveLike(state.status)

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        if (live && state.bitmap != null) {
            ThermalStage(
                bitmap = state.bitmap,
                points = state.measurement.points,
                fahrenheit = state.useFahrenheit,
                measureEdit = state.measureEdit,
                onAddOrSelect = onAddOrSelect,
                onBeginDrag = onBeginDrag,
                onMoveUser = onMoveUser,
                onRemoveNearest = onRemoveNearest,
                modifier = Modifier.fillMaxSize(),
            )
            ColorBar(
                palette = state.palette,
                minC = state.colorBarMin,
                maxC = state.colorBarMax,
                fahrenheit = state.useFahrenheit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 88.dp, bottom = 140.dp),
            )
        } else {
            ConnectPanel(
                state = state,
                onRetry = onRetry,
            )
        }

        TopChrome(
            state = state,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.measureEdit && live) {
                MeasureHintBar(
                    count = state.userPointCount,
                    onRemoveSelected = onRemoveSelected,
                    onClear = onClearPoints,
                )
            }
            if (paletteOpen) {
                PaletteStrip(selected = state.palette, onSelect = {
                    onPalette(it)
                    paletteOpen = false
                })
            }
            BottomDock(
                live = live,
                measureEdit = state.measureEdit,
                isr = state.isr,
                onShutter = onShutter,
                onPalette = { paletteOpen = !paletteOpen },
                onMeasure = { onMeasureEdit(!state.measureEdit) },
                onIsr = {
                    val next = when (state.isr) {
                        IsrScale.OFF -> IsrScale.X2
                        IsrScale.X2 -> IsrScale.X4
                        IsrScale.X4 -> IsrScale.OFF
                    }
                    onIsr(next)
                },
                onSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun TopChrome(
    state: EngineState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(22.dp), clip = false)
            .clip(RoundedCornerShape(22.dp))
            .background(Surface.copy(alpha = 0.96f))
            .border(1.dp, Outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("TINY1-B", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text("热成像 · 红外测温", color = Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state.status)
            if (state.fps > 0) {
                Text("${state.fps} fps", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Ink)
            }
        }
    }
}

@Composable
private fun StatusChip(status: DeviceStatus) {
    val color = when (status) {
        DeviceStatus.Live -> Live
        DeviceStatus.Sample -> Accent
        DeviceStatus.Error, DeviceStatus.PermissionDenied -> Hot
        DeviceStatus.Connecting, DeviceStatus.RequestingPermission, DeviceStatus.PermissionNeeded -> Accent
        DeviceStatus.Searching -> Muted
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(status.labelZh(), color = Ink, fontSize = 12.sp)
    }
}

@Composable
private fun ColorBar(
    palette: PaletteId,
    minC: Float,
    maxC: Float,
    fahrenheit: Boolean,
    modifier: Modifier = Modifier,
) {
    val lut = Palettes.get(palette).lut
    val colors = remember(palette) {
        listOf(0, 64, 128, 192, 255).map { Color(lut[it]) }.reversed()
    }
    Column(
        modifier = modifier
            .width(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface.copy(alpha = 0.92f))
            .border(1.dp, Outline, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatTemp(maxC, fahrenheit), color = Hot, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(12.dp)
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(colors)),
        )
        Spacer(Modifier.height(4.dp))
        Text(formatTemp(minC, fahrenheit), color = Cold, fontSize = 10.sp)
    }
}

@Composable
private fun BottomDock(
    live: Boolean,
    measureEdit: Boolean,
    isr: IsrScale,
    onShutter: () -> Unit,
    onPalette: () -> Unit,
    onMeasure: () -> Unit,
    onIsr: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(28.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem("快门", Icons.Outlined.Camera, enabled = live, onClick = onShutter)
        DockItem("色板", Icons.Outlined.Palette, onClick = onPalette)
        DockItem("测温", Icons.Outlined.MyLocation, active = measureEdit, onClick = onMeasure)
        DockItem(isr.labelZh, Icons.Outlined.AutoFixHigh, active = isr != IsrScale.OFF, onClick = onIsr)
        DockItem("设置", Icons.Outlined.Settings, onClick = onSettings)
    }
}

@Composable
private fun DockItem(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Muted.copy(alpha = 0.35f)
        active -> Accent
        else -> Ink
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) AccentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun PaletteStrip(selected: PaletteId, onSelect: (PaletteId) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp), clip = false)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(20.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(Palettes.all()) { palette ->
            val active = palette.id == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, if (active) Accent else Outline, RoundedCornerShape(14.dp))
                    .background(if (active) AccentSoft else Surface)
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
                Text(palette.id.labelZh, color = Ink, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MeasureHintBar(count: Int, onRemoveSelected: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("点按添加 · 拖动移动 · 长按删除  · 已有 ${count} 点", color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemoveSelected) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除选中点", tint = Ink)
        }
        TextButton(onClick = onClear) { Text("清空", color = Accent) }
    }
}

@Composable
private fun ConnectPanel(
    state: EngineState,
    onRetry: () -> Unit,
) {
    val isError = state.status == DeviceStatus.Error ||
        state.status == DeviceStatus.PermissionDenied
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(28.dp), clip = false)
                .clip(RoundedCornerShape(28.dp))
                .background(Surface)
                .border(
                    1.dp,
                    if (isError) Hot.copy(alpha = 0.35f) else Outline,
                    RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(if (isError) Hot.copy(alpha = 0.08f) else AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Usb,
                    contentDescription = null,
                    tint = if (isError) Hot else Accent,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = when (state.status) {
                    DeviceStatus.RequestingPermission -> "正在请求 USB 权限"
                    DeviceStatus.PermissionNeeded -> "请保持应用在前台"
                    DeviceStatus.PermissionDenied -> "USB 权限被拒绝"
                    DeviceStatus.Error -> "连接失败"
                    DeviceStatus.Connecting -> "正在连接 Tiny1-B"
                    else -> "未检测到 Tiny1-B"
                },
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.errorMessage ?: when (state.status) {
                    DeviceStatus.RequestingPermission -> "请在系统弹窗中选择「允许」。"
                    DeviceStatus.PermissionNeeded -> "Tiny1-B 已连接时，请将应用保持在前台。系统会请求 USB 权限，请选择「允许」。"
                    DeviceStatus.PermissionDenied -> "请点「重新扫描」，并在系统弹窗中选择「允许」。"
                    DeviceStatus.Connecting -> "正在打开相机…"
                    else -> "使用 USB OTG 连接 Infiray Tiny1-B 热像模组。VID 0BDA · PID 3901。"
                },
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
            )
            if (state.status == DeviceStatus.Connecting ||
                state.status == DeviceStatus.RequestingPermission
            ) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(8.dp)),
                    color = Accent,
                    trackColor = SurfaceMuted,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) { Text("重新扫描") }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "也可在设置中打开「样例画面」预览色板与测温，无需模组。",
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
