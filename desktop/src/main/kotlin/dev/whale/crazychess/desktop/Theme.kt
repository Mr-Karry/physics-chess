package dev.whale.crazychess.desktop

import dev.whale.crazychess.game.Side
import java.awt.Color
import java.awt.Font

/** 一枚棋子的三种颜色：面、边、字。和 Android 版同名同义。 */
data class PiecePaint(val face: Color, val rim: Color, val ink: Color)

/**
 * 桌面版的静态配色。
 *
 * Android 是从 M3 动态取色推出来的；桌面没有壁纸取色，直接钉一套低饱和青绿 + 暖砂，
 * 和 Android 的兜底配色一致，看起来是同一个游戏。
 */
object Palette {
    val background = Color(0xFFFBFDF9.toInt())
    val surface = Color(0xFFFFFFFF.toInt())
    val surfaceHigh = Color(0xFFF1F5F1.toInt())
    val surfaceHighest = Color(0xFFE4EAE5.toInt())
    val onSurface = Color(0xFF191C1B.toInt())
    val onSurfaceVariant = Color(0xFF3F4946.toInt())
    val primary = Color(0xFF27685A.toInt())
    val onPrimary = Color(0xFFFFFFFF.toInt())
    val primaryContainer = Color(0xFFA8F2DC.toInt())
    val onPrimaryContainer = Color(0xFF00201A.toInt())
    val tertiary = Color(0xFFB3562A.toInt())
    val tertiaryContainer = Color(0xFFFFDBCB.toInt())
    val onTertiaryContainer = Color(0xFF3A0B00.toInt())
    val secondaryContainer = Color(0xFFCCE8DF.toInt())
    val onSecondaryContainer = Color(0xFF06201B.toInt())
    val outline = Color(0xFF6F7975.toInt())
    val outlineVariant = Color(0xFFBEC9C4.toInt())
    val error = Color(0xFFBA1A1A.toInt())

    val board = surfaceHigh
    val boardEdge = outlineVariant
    val grid = Color(0xFFBEC9C4.toInt())
    val aim = primary

    val faceA = primary
    val faceB = tertiary

    val paintA = piecePaint(faceA)
    val paintB = piecePaint(faceB)

    val darkBackground = Color(0xFF191C1B.toInt())
    val darkSurface = Color(0xFF232725.toInt())
    val darkSurfaceHigh = Color(0xFF2C312E.toInt())
    val darkOnSurface = Color(0xFFE1E3E0.toInt())
    val darkOnSurfaceVariant = Color(0xFFBEC9C4.toInt())

    fun paint(side: Side): PiecePaint = if (side == Side.BOTTOM) paintA else paintB

    private fun piecePaint(face: Color): PiecePaint =
        PiecePaint(face, rimOf(face), inkOn(face))

    private fun rimOf(face: Color): Color = mix(face, Color.BLACK, 0.28f)

    private fun inkOn(face: Color): Color {
        val lum = luminance(face)
        val againstWhite = 1.05 / (lum + 0.05)
        val againstInk = (lum + 0.05) / 0.05
        return if (againstInk > againstWhite) Color(0xFF191A13.toInt()) else Color(0xFFFDFBF5.toInt())
    }

    fun mix(a: Color, b: Color, t: Float): Color = Color(
        (a.red * (1 - t) + b.red * t).toInt().coerceIn(0, 255),
        (a.green * (1 - t) + b.green * t).toInt().coerceIn(0, 255),
        (a.blue * (1 - t) + b.blue * t).toInt().coerceIn(0, 255),
        (a.alpha * (1 - t) + b.alpha * t).toInt().coerceIn(0, 255),
    )

    fun alpha(c: Color, a: Int): Color = Color(c.red, c.green, c.blue, a)

    /** WCAG 相对亮度，用来决定字色。 */
    private fun luminance(c: Color): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    /** 字形字体：优先 Segoe UI Symbol（Windows 自带棋子字形），没有就退回 Dialog。 */
    fun glyphFont(size: Int): Font {
        val preferred = Font("Segoe UI Symbol", Font.PLAIN, size)
        return if (preferred.canDisplay('♛')) preferred else Font(Font.SANS_SERIF, Font.PLAIN, size)
    }
}
