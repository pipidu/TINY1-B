package com.pipidu.tiny1b.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.pipidu.tiny1b.core.MeasurePoint
import com.pipidu.tiny1b.core.PointKind
import com.pipidu.tiny1b.ui.formatTemp
import com.pipidu.tiny1b.ui.theme.Cold
import com.pipidu.tiny1b.ui.theme.Hot
import com.pipidu.tiny1b.ui.theme.Paper
import kotlin.math.min

@Composable
fun ThermalStage(
    bitmap: Bitmap?,
    points: List<MeasurePoint>,
    fahrenheit: Boolean,
    measureEdit: Boolean,
    onAddOrSelect: (Float, Float) -> Unit,
    onBeginDrag: (Float, Float) -> Long?,
    onMoveUser: (Long, Float, Float) -> Unit,
    onRemoveNearest: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var draggingId by remember { mutableStateOf<Long?>(null) }

    BoxWithConstraints(modifier = modifier.background(Paper)) {
        val viewW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val imgW = bitmap?.width?.toFloat() ?: 192f
        val imgH = bitmap?.height?.toFloat() ?: 256f
        val fit = remember(viewW, viewH, imgW, imgH) { fitRect(viewW, viewH, imgW, imgH) }

        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "热成像画面",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (measureEdit) {
                        Modifier
                            .pointerInput(fit) {
                                detectTapGestures(
                                    onLongPress = { offset ->
                                        toNorm(offset, fit)?.let { (nx, ny) -> onRemoveNearest(nx, ny) }
                                    },
                                    onTap = { offset ->
                                        toNorm(offset, fit)?.let { (nx, ny) -> onAddOrSelect(nx, ny) }
                                    },
                                )
                            }
                            .pointerInput(fit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val n = toNorm(offset, fit) ?: return@detectDragGestures
                                        draggingId = onBeginDrag(n.first, n.second)
                                    },
                                    onDragEnd = { draggingId = null },
                                    onDragCancel = { draggingId = null },
                                    onDrag = { change, _ ->
                                        val id = draggingId ?: return@detectDragGestures
                                        val n = toNorm(change.position, fit) ?: return@detectDragGestures
                                        onMoveUser(id, n.first, n.second)
                                    },
                                )
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            points.forEach { point ->
                val x = fit.left + point.nx * fit.width
                val y = fit.top + point.ny * fit.height
                val color = when (point.kind) {
                    PointKind.HOT -> Hot
                    PointKind.COLD -> Cold
                    PointKind.CENTER -> Color(0xFFF8FAFC)
                    PointKind.USER -> if (point.selected) Color(0xFFFFD36A) else Color(0xFFE8D5B5)
                }
                val arm = 14f
                drawLine(color, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 2.4f)
                drawLine(color, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 2.4f)
                drawCircle(color, radius = 5.5f, center = Offset(x, y), style = Stroke(width = 1.6f))
                if (point.selected) {
                    drawCircle(Color(0x66FFD36A), radius = 16f, center = Offset(x, y))
                }
                val label = when (point.kind) {
                    PointKind.HOT -> "最高 ${formatTemp(point.celsius, fahrenheit)}"
                    PointKind.COLD -> "最低 ${formatTemp(point.celsius, fahrenheit)}"
                    PointKind.CENTER -> "中心 ${formatTemp(point.celsius, fahrenheit)}"
                    PointKind.USER -> "P${point.id} ${formatTemp(point.celsius, fahrenheit)}"
                }
                val layout = measurer.measure(
                    label,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                val tx = (x + 10f).coerceAtMost(size.width - layout.size.width - 8f)
                val ty = (y - layout.size.height - 8f).coerceAtLeast(8f)
                drawRect(
                    color = Color(0xCC07080D),
                    topLeft = Offset(tx - 6f, ty - 3f),
                    size = Size(layout.size.width + 12f, layout.size.height + 6f),
                )
                drawText(layout, topLeft = Offset(tx, ty))
            }
        }
    }
}

private fun fitRect(viewW: Float, viewH: Float, imgW: Float, imgH: Float): Rect {
    val scale = min(viewW / imgW, viewH / imgH)
    val dw = imgW * scale
    val dh = imgH * scale
    val left = (viewW - dw) / 2f
    val top = (viewH - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

private fun toNorm(offset: Offset, rect: Rect): Pair<Float, Float>? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    if (!rect.contains(offset)) return null
    val nx = ((offset.x - rect.left) / rect.width).coerceIn(0f, 1f)
    val ny = ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f)
    return nx to ny
}
