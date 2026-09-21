package com.pipidu.tiny1b.ui

import com.pipidu.tiny1b.core.PointKind
import com.pipidu.tiny1b.device.DeviceStatus
import kotlin.math.roundToInt

fun formatTemp(celsius: Float, fahrenheit: Boolean): String {
    val value = if (fahrenheit) celsius * 9f / 5f + 32f else celsius
    val unit = if (fahrenheit) "°F" else "°C"
    return String.format("%.1f%s", value, unit)
}

fun DeviceStatus.labelZh(): String = when (this) {
    DeviceStatus.Searching -> "未连接"
    DeviceStatus.RequestingPermission -> "请求权限"
    DeviceStatus.PermissionNeeded -> "请插入"
    DeviceStatus.PermissionDenied -> "权限被拒"
    DeviceStatus.Connecting -> "正在连接"
    DeviceStatus.Live -> "已连接"
    DeviceStatus.Sample -> "样例画面"
    DeviceStatus.Error -> "出错"
}

fun PointKind.labelZh(): String = when (this) {
    PointKind.CENTER -> "中心"
    PointKind.USER -> "测温点"
    PointKind.HOT -> "最高"
    PointKind.COLD -> "最低"
}

fun shutterLabel(seconds: Int): String = "${seconds}s"
fun isLiveLike(status: DeviceStatus): Boolean =
    status == DeviceStatus.Live || status == DeviceStatus.Sample

fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
