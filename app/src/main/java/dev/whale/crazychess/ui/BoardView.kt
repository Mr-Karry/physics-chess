package dev.whale.crazychess.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whale.crazychess.game.GameEngine
import dev.whale.crazychess.game.Piece
import dev.whale.crazychess.game.PieceType
import dev.whale.crazychess.game.Rules
import dev.whale.crazychess.game.Side
import kotlin.math.hypot
import kotlin.math.min

/** 棋盘四周留出的空隙（占画布宽度的比例），让贴边的棋子不会被圆角裁掉。 */
private const val PAD = 0.055f

private fun DrawScope.cellSize(): Float = (size.width - size.width * PAD * 2f) / Rules.BOARD

private fun DrawScope.boardPad(): Float = size.width * PAD

/**
 * 把物理坐标（格）换算成画布坐标。
 *
 * [flip] 用于局域网里执上方的一方：整盘 180° 镜像，让他自己的子出现在屏幕下方，
 * 不用把手机倒过来拿。只翻坐标不翻画布，棋子字形始终是正的。
 */
private fun DrawScope.toPx(x: Float, y: Float, flip: Boolean = false): Offset {
    val pad = boardPad()
    val cell = cellSize()
    val bx = if (flip) Rules.BOARD - x else x
    val by = if (flip) Rules.BOARD - y else y
    return Offset(pad + bx * cell, pad + by * cell)
}

/**
 * 棋盘。物理坐标以"格"为单位（0..8），这里统一换算成像素。
 *
 * 尺寸完全由外部传入的 [modifier] 决定（竖屏用 fillMaxWidth，横屏用 fillMaxHeight），
 * 内部不再写死宽高比，这样平板横屏也不会撑出屏幕。
 *
 * @param controlSide 当前这台设备可以操作的阵营；人机模式下是下方，双人模式下随回合切换，
 *   局域网里固定是自己那一方。
 * @param inputEnabled 局域网同步未对齐时先关掉输入，避免两边各算一套物理。
 * @param onShot 出手成功时回调（带上棋子和拖拽向量），局域网靠它把这一手发给对端。
 */
@Composable
fun BoardView(
    engine: GameEngine,
    colors: BoardColors,
    controlSide: Side,
    frameTick: Long,
    panFraction: Float,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
    flipped: Boolean = false,
    onShot: (Piece, Float, Float) -> Unit,
    onSelect: () -> Unit,
    onInvalid: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 24)
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf<Piece?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    // 横向平移由外部的横条驱动（拖空白处容易误触，改成专门的拖动条），这里只负责按比例绘制。
    val pan = rememberUpdatedState(panFraction)

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    // 读取 frameTick 让每一帧都重绘
    val t = frameTick

    // 页面离开时清掉残留的拖拽状态，避免下次进来看到一条幽灵瞄准线
    DisposableEffect(Unit) {
        onDispose { dragging = null }
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(engine, controlSide, inputEnabled, flipped) {
                detectDragGestures(
                    onDragStart = { pos ->
                        if (!inputEnabled) {
                            dragging = null
                            onInvalid()
                            return@detectDragGestures
                        }
                        val cell = (size.width - size.width * PAD * 2f) / Rules.BOARD
                        val pad = size.width * PAD
                        val panPx = size.width * pan.value
                        var bx = (pos.x - panPx - pad) / cell
                        var by = (pos.y - pad) / cell
                        if (flipped) {
                            bx = Rules.BOARD - bx
                            by = Rules.BOARD - by
                        }
                        val hit = engine.hitTest(bx, by)
                        if (hit == null) {
                            dragging = null
                            return@detectDragGestures
                        }
                        val playable = hit.side == controlSide && engine.canAct(hit.side)
                        if (!playable) {
                            onInvalid()
                            dragging = null
                            return@detectDragGestures
                        }
                        dragging = hit
                        dragPos = Offset(bx, by)
                        onSelect()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val cell = (size.width - size.width * PAD * 2f) / Rules.BOARD
                        val pad = size.width * PAD
                        val panPx = size.width * pan.value
                        var mx = (change.position.x - panPx - pad) / cell
                        var my = (change.position.y - pad) / cell
                        if (flipped) {
                            mx = Rules.BOARD - mx
                            my = Rules.BOARD - my
                        }
                        dragPos = Offset(mx, my)
                    },
                    onDragEnd = {
                        val piece = dragging
                        dragging = null
                        if (piece != null) {
                            val dx = dragPos.x - piece.x
                            val dy = dragPos.y - piece.y
                            val len = hypot(dx, dy).coerceAtMost(Rules.MAX_DRAG)
                            if (len >= Rules.MIN_DRAG && engine.shoot(piece, dx, dy)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onShot(piece, dx, dy)
                            } else {
                                onInvalid()
                            }
                        }
                    },
                    onDragCancel = {
                        dragging = null
                    },
                )
            },
    ) {
        withTransform({ translate(panFraction * size.width, 0f) }) {
        drawBoardBackground(colors)
        drawGrid(colors)

        for (p in engine.pieces) {
            if (!p.alive) continue
            drawPiece(p, colors, textMeasurer, density.density, selected = p === dragging, pulse = pulse, flip = flipped)
        }

        // 时间基准与 GameEngine.tick 传入的 nowMs 一致
        val now = android.os.SystemClock.elapsedRealtime()

        // 掉出棋盘的棋子：继续往外飞、缩小、淡出
        for (fx in engine.falls) {
            val progress = (now - fx.startTimeMs) / Rules.FALL_FX_MS.toFloat()
            if (progress > 1f) continue
            val center = toPx(
                fx.fromX + fx.vx * 0.16f * progress,
                fx.fromY + fx.vy * 0.16f * progress,
                flip = flipped,
            )
            drawPieceAt(
                center = center,
                radius = Rules.RADIUS * cellSize() * (1f - 0.45f * progress),
                type = fx.type,
                side = fx.side,
                colors = colors,
                textMeasurer = textMeasurer,
                density = density.density,
                selected = false,
                pulse = pulse,
                alpha = 1f - progress * progress,
            )
        }

        // 最近一次出局的碎片飞溅
        for (fx in engine.killFx) {
            val age = (now - fx.startTimeMs) / 700f
            if (age in 0f..1f) {
                val c = colors.of(fx.colorSide).first
                val center = toPx(fx.x, fx.y, flip = flipped)
                val r = (0.2f + age * 1.2f) * cellSize()
                drawCircle(
                    color = c.copy(alpha = (1f - age) * 0.45f),
                    radius = r,
                    center = center,
                    style = Stroke(width = (3f * (1f - age) + 1f) * density.density),
                )
            }
        }

        // 瞄准线
        val piece = dragging
        if (piece != null) {
            drawAim(
                toPx(piece.x, piece.y, flip = flipped),
                toPx(dragPos.x, dragPos.y, flip = flipped),
                colors,
            )
        }
        // 让编译器不要把 t 优化掉
        if (t < 0) drawLine(Color.Transparent, Offset.Zero, Offset.Zero)
        }
    }
}

private fun DrawScope.drawBoardBackground(colors: BoardColors) {
    val pad = boardPad()
    val w = size.width - pad * 2f
    val h = size.height - pad * 2f
    val radius = CornerRadius(26.dp.toPx(), 26.dp.toPx())
    // 扁平：纯色底 + 细描边，不做渐变和内阴影
    drawRoundRect(
        color = colors.board,
        topLeft = Offset(pad, pad),
        size = Size(w, h),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = colors.boardEdge.copy(alpha = 0.7f),
        topLeft = Offset(pad, pad),
        size = Size(w, h),
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawGrid(colors: BoardColors) {
    val pad = boardPad()
    val cell = cellSize()
    val end = pad + cell * Rules.BOARD
    val thin = 1.dp.toPx()
    for (i in 1 until Rules.BOARD.toInt()) {
        drawLine(
            color = colors.grid,
            start = Offset(pad + i * cell, pad),
            end = Offset(pad + i * cell, end),
            strokeWidth = thin,
        )
        drawLine(
            color = colors.grid,
            start = Offset(pad, pad + i * cell),
            end = Offset(end, pad + i * cell),
            strokeWidth = thin,
        )
    }
    // 悬崖：棋子中心越过这条边就算掉下去
    drawRoundRect(
        color = colors.wall,
        topLeft = Offset(pad, pad),
        size = Size(cell * Rules.BOARD, cell * Rules.BOARD),
        cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx()),
        style = Stroke(width = 3.dp.toPx()),
    )
}

private fun DrawScope.drawPiece(
    piece: Piece,
    colors: BoardColors,
    textMeasurer: TextMeasurer,
    density: Float,
    selected: Boolean,
    pulse: Float,
    flip: Boolean = false,
) {
    val cell = cellSize()
    drawPieceAt(
        center = toPx(piece.x, piece.y, flip = flip),
        radius = piece.radius * cell,
        type = piece.type,
        side = piece.side,
        colors = colors,
        textMeasurer = textMeasurer,
        density = density,
        selected = selected,
        pulse = pulse,
        alpha = 1f,
    )
}

private fun DrawScope.drawPieceAt(
    center: Offset,
    radius: Float,
    type: PieceType,
    side: Side,
    colors: BoardColors,
    textMeasurer: TextMeasurer,
    density: Float,
    selected: Boolean,
    pulse: Float,
    alpha: Float,
) {
    val r = radius
    val paint = colors.paint(side)
    val ink = paint.ink

    // 扁平棋子：一块实心硬投影 + 实心圆面 + 一圈深色描边，没有渐变和高光
    drawCircle(
        color = Color.Black.copy(alpha = 0.13f * alpha),
        radius = r,
        center = center + Offset(0f, r * 0.11f),
    )
    drawCircle(
        color = paint.face.copy(alpha = alpha),
        radius = r,
        center = center,
    )
    val edge = (r * 0.09f).coerceAtLeast(1.2.dp.toPx())
    drawCircle(
        color = paint.rim.copy(alpha = alpha),
        radius = r - edge * 0.5f,
        center = center,
        style = Stroke(width = edge),
    )

    if (selected) {
        drawCircle(
            color = paint.face.copy(alpha = 0.45f + 0.4f * pulse),
            radius = r * (1.22f + 0.06f * pulse),
            center = center,
            style = Stroke(width = (r * 0.10f).coerceAtLeast(1.5.dp.toPx())),
        )
    }

    val layout = textMeasurer.measure(
        text = AnnotatedString(type.glyph),
        style = TextStyle(
            fontSize = (r * 1.5f / density).sp,
            fontWeight = FontWeight.Black,
            color = ink.copy(alpha = ink.alpha * alpha),
        ),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - layout.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawAim(start: Offset, end: Offset, colors: BoardColors) {
    val len = hypot(end.x - start.x, end.y - start.y)
    if (len < 2f) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
    drawLine(
        brush = Brush.linearGradient(listOf(colors.aim, colors.aim.copy(alpha = 0.35f))),
        start = start,
        end = end,
        strokeWidth = 3.dp.toPx(),
        pathEffect = dash,
        cap = StrokeCap.Round,
    )
    // 箭头
    val dx = (end.x - start.x) / len
    val dy = (end.y - start.y) / len
    val head = 14.dp.toPx()
    val left = Offset(end.x - dx * head - dy * head * 0.7f, end.y - dy * head + dx * head * 0.7f)
    val right = Offset(end.x - dx * head + dy * head * 0.7f, end.y - dy * head - dx * head * 0.7f)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(end.x, end.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path, colors.aim)
    drawCircle(color = colors.aim.copy(alpha = 0.25f), radius = min(head, 12.dp.toPx()), center = start)
}
