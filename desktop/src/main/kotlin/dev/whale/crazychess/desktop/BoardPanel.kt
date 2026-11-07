package dev.whale.crazychess.desktop

import dev.whale.crazychess.game.GameEngine
import dev.whale.crazychess.game.Piece
import dev.whale.crazychess.game.Rules
import dev.whale.crazychess.game.Side
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import kotlin.math.hypot
import kotlin.math.min

/**
 * 棋盘。绘制 + 拖拽瞄准，和 Android 的 `BoardView` 同一套坐标系与手感：
 * 坐标系是"格"，原点在棋盘左上角，y 向下。
 *
 * 按住自己的子拖动时棋盘上的子**不动**，只画瞄准线和力度环；松手才真正给速度。
 */
class BoardPanel(
    val engine: GameEngine,
    var controlSide: Side = Side.BOTTOM,
    var flipped: Boolean = false,
    private val onShot: (Piece, Float, Float) -> Unit = { _, _, _ -> },
    private val onInvalid: () -> Unit = {},
) : JPanel() {

    private var dragPiece: Piece? = null
    private var dragX = 0f
    private var dragY = 0f
    private var hovering: Piece? = null

    init {
        isOpaque = true
        background = Palette.background
        preferredSize = Dimension(560, 560)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = onPress(Point2D.Double(e.x.toDouble(), e.y.toDouble()))
            override fun mouseReleased(e: MouseEvent) = onRelease(Point2D.Double(e.x.toDouble(), e.y.toDouble()))
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) = onDrag(Point2D.Double(e.x.toDouble(), e.y.toDouble()))
            override fun mouseMoved(e: MouseEvent) = onHover(Point2D.Double(e.x.toDouble(), e.y.toDouble()))
        })
    }

    // ---------------------------------------------------------------- 坐标换算

    private fun boardSide(): Double = min(width.toDouble(), height.toDouble()) - 16.0

    private fun originX(): Double = (width - boardSide()) / 2.0

    private fun originY(): Double = (height - boardSide()) / 2.0

    private fun cell(): Double = boardSide() / Rules.BOARD

    /** 格坐标 → 屏幕像素。镜像时整体转 180°（和 Android 的 proj() 一致：只翻坐标不翻画布）。 */
    private fun toScreen(x: Float, y: Float): Point2D.Double {
        val bx = if (flipped) Rules.BOARD - x else x
        val by = if (flipped) Rules.BOARD - y else y
        return Point2D.Double(originX() + bx * cell(), originY() + by * cell())
    }

    /** 屏幕像素 → 格坐标。 */
    private fun toBoard(p: Point2D): Point2D.Double {
        var bx = (p.x - originX()) / cell()
        var by = (p.y - originY()) / cell()
        if (flipped) {
            bx = Rules.BOARD - bx
            by = Rules.BOARD - by
        }
        return Point2D.Double(bx, by)
    }

    // ---------------------------------------------------------------- 输入

    private fun onPress(p: Point2D) {
        val b = toBoard(p)
        val bx = b.x
        val by = b.y
        val hit = engine.hitTest(bx.toFloat(), by.toFloat())
        if (hit == null) {
            onInvalid()
            return
        }
        if (hit.side != controlSide || !engine.canAct(hit.side)) {
            onInvalid()
            return
        }
        dragPiece = hit
        dragX = bx.toFloat()
        dragY = by.toFloat()
        repaint()
    }

    private fun onDrag(p: Point2D) {
        val piece = dragPiece ?: return
        val b = toBoard(p)
        val bx = b.x
        val by = b.y
        dragX = bx.toFloat()
        dragY = by.toFloat()
        // 拖太远就夹住，避免瞄准线跑出棋盘看着别扭
        val dx = dragX - piece.x
        val dy = dragY - piece.y
        val len = hypot(dx, dy)
        if (len > Rules.MAX_DRAG) {
            dragX = piece.x + dx / len * Rules.MAX_DRAG
            dragY = piece.y + dy / len * Rules.MAX_DRAG
        }
        repaint()
    }

    private fun onRelease(p: Point2D) {
        val piece = dragPiece ?: return
        dragPiece = null
        val b = toBoard(p)
        val bx = b.x
        val by = b.y
        var dx = bx.toFloat() - piece.x
        var dy = by.toFloat() - piece.y
        var len = hypot(dx, dy)
        if (len < Rules.MIN_DRAG) {
            onInvalid()
            repaint()
            return
        }
        if (len > Rules.MAX_DRAG) {
            dx = dx / len * Rules.MAX_DRAG
            dy = dy / len * Rules.MAX_DRAG
            len = Rules.MAX_DRAG
        }
        if (engine.shoot(piece, dx, dy)) {
            onShot(piece, dx, dy)
        } else {
            onInvalid()
        }
        repaint()
    }

    private fun onHover(p: Point2D) {
        val b = toBoard(p)
        val bx = b.x
        val by = b.y
        val hit = engine.hitTest(bx.toFloat(), by.toFloat())
        val mine = hit != null && hit.side == controlSide
        val next = if (mine) hit else null
        if (next !== hovering) {
            hovering = next
            cursor = Cursor.getPredefinedCursor(
                if (mine && engine.canAct(controlSide)) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR,
            )
            repaint()
        }
    }

    // ---------------------------------------------------------------- 绘制

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g2.color = Palette.background
        g2.fillRect(0, 0, width, height)

        val side = boardSide()
        val ox = originX()
        val oy = originY()
        val c = cell()

        // 棋盘底 + 描边
        val boardShape = RoundRectangle2D.Double(ox, oy, side, side, 28.0, 28.0)
        g2.color = Palette.board
        g2.fill(boardShape)
        g2.color = Palette.boardEdge
        g2.stroke = BasicStroke(2f)
        g2.draw(boardShape)

        // 网格
        g2.color = Palette.alpha(Palette.grid, 150)
        g2.stroke = BasicStroke(1f)
        for (i in 1 until Rules.BOARD.toInt()) {
            val v = ox + i * c
            val h = oy + i * c
            g2.draw(Line2D.Double(v, oy + 2, v, oy + side - 2))
            g2.draw(Line2D.Double(ox + 2, h, ox + side - 2, h))
        }

        // 出局动效（先画，棋子压在它上面）
        drawFalls(g2, c)
        drawKills(g2, c)

        // 棋子
        for (piece in engine.pieces) {
            if (!piece.alive) continue
            drawPiece(g2, piece, c)
        }

        // 选中的子 + 瞄准线
        val piece = dragPiece
        if (piece != null) {
            val from = toScreen(piece.x, piece.y)
            val to = toScreen(dragX, dragY)
            val len = hypot(dragX - piece.x, dragY - piece.y)
            drawAim(g2, from, to, len, c)
        }
    }

    private fun drawPiece(g2: Graphics2D, piece: Piece, c: Double) {
        val center = toScreen(piece.x, piece.y)
        val r = piece.radius * c
        val paint = Palette.paint(piece.side)

        // 硬投影（原来那版是软阴影，扁平风改成偏移一点的实心影）
        g2.color = Palette.alpha(Color.BLACK, 38)
        g2.fill(Ellipse2D.Double(center.x - r + 1.5, center.y - r + 3.0, r * 2, r * 2))

        g2.color = paint.face
        g2.fill(Ellipse2D.Double(center.x - r, center.y - r, r * 2, r * 2))
        g2.color = paint.rim
        g2.stroke = BasicStroke((c * 0.045).toFloat().coerceAtLeast(1.4f))
        g2.draw(Ellipse2D.Double(center.x - r, center.y - r, r * 2, r * 2))

        // 字形：正立，不跟着棋盘转
        val glyph = piece.type.glyph
        g2.font = Palette.glyphFont((r * 1.42).toInt().coerceAtLeast(10))
        val fm = g2.fontMetrics
        val tw = fm.stringWidth(glyph).toDouble()
        val th = fm.ascent.toDouble() - fm.descent.toDouble()
        g2.color = paint.ink
        g2.drawString(glyph, (center.x - tw / 2).toFloat(), (center.y + th / 2).toFloat())
    }

    private fun drawAim(g2: Graphics2D, from: Point2D, to: Point2D, len: Float, c: Double) {
        val paint = Palette.paint(controlSide)
        // 选中环
        g2.color = Palette.alpha(paint.face, 90)
        g2.stroke = BasicStroke((c * 0.1).toFloat())
        val d = c * Rules.RADIUS * 2.5
        g2.draw(Ellipse2D.Double(from.x - d / 2, from.y - d / 2, d, d))

        // 虚线瞄准
        g2.color = Palette.alpha(Palette.aim, 200)
        g2.stroke = BasicStroke(
            (c * 0.06).toFloat().coerceAtLeast(2f),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
            (c * 0.1).toFloat().coerceAtLeast(2f),
            floatArrayOf((c * 0.22).toFloat(), (c * 0.18).toFloat()),
            0f,
        )
        g2.draw(Line2D.Double(from, to))

        // 箭头
        val ang = Math.atan2(to.y - from.y, to.x - from.x)
        val head = (c * 0.34)
        g2.stroke = BasicStroke((c * 0.06).toFloat().coerceAtLeast(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (sign in intArrayOf(-1, 1)) {
            val a = ang + sign * 0.5
            g2.draw(
                Line2D.Double(
                    to.x,
                    to.y,
                    to.x - head * Math.cos(a),
                    to.y - head * Math.sin(a),
                ),
            )
        }

        // 力度环：按拖拽长度给个同心弧，直观看到力度
        val power = (len / Rules.MAX_DRAG).coerceIn(0f, 1f)
        if (power > 0.02f) {
            g2.color = Palette.alpha(Palette.aim, 150)
            g2.stroke = BasicStroke((c * 0.07).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawArc(
                (from.x - d * 0.7).toInt(),
                (from.y - d * 0.7).toInt(),
                (d * 1.4).toInt(),
                (d * 1.4).toInt(),
                90,
                -(power * 360).toInt(),
            )
        }
    }

    private fun drawFalls(g2: Graphics2D, c: Double) {
        val now = System.currentTimeMillis()
        for (fx in engine.falls) {
            val t = ((now - fx.startTimeMs).toDouble() / Rules.FALL_FX_MS).coerceIn(0.0, 1.0)
            val from = toScreen(fx.fromX, fx.fromY)
            val travel = t * 0.9
            val cx = from.x + fx.vx * c * travel * 0.35
            val cy = from.y + fx.vy * c * travel * 0.35
            val r = c * Rules.RADIUS * (1.0 - t * 0.75)
            if (r <= 0.5) continue
            val paint = Palette.paint(fx.side)
            g2.color = Palette.alpha(paint.face, ((1.0 - t) * 220).toInt().coerceIn(0, 255))
            g2.fill(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
            g2.font = Palette.glyphFont((r * 1.42).toInt().coerceAtLeast(8))
            val fm = g2.fontMetrics
            val g = fx.type.glyph
            g2.color = Palette.alpha(paint.ink, ((1.0 - t) * 220).toInt().coerceIn(0, 255))
            g2.drawString(
                g,
                (cx - fm.stringWidth(g) / 2).toFloat(),
                (cy + (fm.ascent - fm.descent) / 2).toFloat(),
            )
        }
    }

    private fun drawKills(g2: Graphics2D, c: Double) {
        val now = System.currentTimeMillis()
        for (kf in engine.killFx) {
            val t = ((now - kf.startTimeMs).toDouble() / 700.0).coerceIn(0.0, 1.0)
            val p = toScreen(kf.x, kf.y)
            val r = c * (0.3 + t * 0.9)
            g2.color = Palette.alpha(Palette.paint(kf.colorSide).face, ((1.0 - t) * 120).toInt().coerceIn(0, 255))
            g2.stroke = BasicStroke((c * 0.08 * (1.0 - t)).toFloat().coerceAtLeast(1f))
            g2.draw(Ellipse2D.Double(p.x - r, p.y - r, r * 2, r * 2))
        }
    }
}
