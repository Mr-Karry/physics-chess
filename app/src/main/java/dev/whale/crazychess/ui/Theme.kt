package dev.whale.crazychess.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whale.crazychess.game.Side

/** M3E 强调"大圆角 + 强对比色块 + 有弹性的动效"，这里把圆角整体放大一档。 */
private val ExpressiveShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

private val ExpressiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 52.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

/** 非 Android 12 的备用配色：低饱和的青绿 + 暖砂，作为动态取色不可用时的兜底。 */
private fun fallbackLight() = lightColorScheme(
    primary = Color(0xFF27685A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2DC),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFFB3562A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCB),
    onTertiaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private fun fallbackDark() = darkColorScheme(
    primary = Color(0xFF8CD5BF),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFA8F2DC),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFFFB694),
    onTertiary = Color(0xFF5B1B00),
    tertiaryContainer = Color(0xFF7F2C03),
    onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * 棋盘配色全部从 ColorScheme 推导，所以换壁纸 / 换主题色时棋子、棋盘会跟着变。
 *
 * 棋子不用 `primary` / `tertiary` 原色：动态取色在深色模式下拿到的是很淡的 tone-80，
 * 直接铺在深色棋盘上会像"反色"一样发灰。这里保留色相，重新压一遍饱和度与明度，
 * 让浅色 / 深色两种模式下棋子都是"实心、饱和、边界清楚"的扁平色块。
 */
@Immutable
data class PiecePaint(
    val face: Color,
    val rim: Color,
    val ink: Color,
)

@Immutable
data class BoardColors(
    val sideA: Color,
    val sideAContainer: Color,
    val sideAOn: Color,
    val sideB: Color,
    val sideBContainer: Color,
    val sideBOn: Color,
    val board: Color,
    val boardEdge: Color,
    val grid: Color,
    val wall: Color,
    val aim: Color,
    val aimSoft: Color,
    val selection: Color,
    val paintA: PiecePaint,
    val paintB: PiecePaint,
)

/** 取 HSV（h 0..360）。Compose 没有反查 API，这里自己算一遍。 */
private fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(if (h < 0f) h + 360f else h, s, max)
}

/**
 * 棋子面色：
 * - 浅色模式直接用动态取色的 `primary` / `tertiary`（原版观感，饱和但不过火）；
 * - 深色模式这两种角色是 tone-80 的淡色，直接铺在深色棋盘上会发灰像"反色"，
 *   所以往棋盘底色方向压深一档，变成同一色相的中间调 —— 深浅两套模式明显不同色，
 *   又不会像纯饱和色那样刺眼。
 */
private fun faceOf(base: Color, dark: Boolean, boardColor: Color): Color =
    if (dark) lerp(base, boardColor, 0.34f) else base

/**
 * 字形颜色按对比度选：亮面配深字、暗面配浅字。
 * 固定阈值在动态取色下会翻车（同一角色深浅两套模式亮度差很大），所以直接算对比度。
 */
private fun inkOn(face: Color): Color {
    val lum = face.luminance()
    val againstWhite = 1.05f / (lum + 0.05f)
    val againstInk = (lum + 0.05f) / 0.05f
    return if (againstInk > againstWhite) Color(0xFF191A13) else Color(0xFFFDFBF5)
}

private fun rimOf(face: Color, dark: Boolean): Color =
    if (dark) lerp(face, Color.White, 0.16f) else lerp(face, Color.Black, 0.28f)

val ColorScheme.boardColors: BoardColors
    get() {
        val dark = surface.luminance() < 0.5f
        val boardColor = if (dark) surfaceContainerHighest else surfaceContainerLow
        val faceA = faceOf(primary, dark, boardColor)
        val faceB = faceOf(tertiary, dark, boardColor)
        return BoardColors(
            sideA = faceA,
            sideAContainer = primaryContainer,
            sideAOn = onPrimaryContainer,
            sideB = faceB,
            sideBContainer = tertiaryContainer,
            sideBOn = onTertiaryContainer,
            board = boardColor,
            boardEdge = outlineVariant,
            grid = outlineVariant.copy(alpha = if (dark) 0.38f else 0.55f),
            wall = error.copy(alpha = 0.5f),
            aim = secondary,
            aimSoft = secondaryContainer,
            selection = faceA,
            paintA = PiecePaint(faceA, rimOf(faceA, dark), inkOn(faceA)),
            paintB = PiecePaint(faceB, rimOf(faceB, dark), inkOn(faceB)),
        )
    }

fun BoardColors.of(side: Side): Triple<Color, Color, Color> =
    if (side == Side.BOTTOM) Triple(sideA, sideAContainer, sideAOn)
    else Triple(sideB, sideBContainer, sideBOn)

fun BoardColors.paint(side: Side): PiecePaint =
    if (side == Side.BOTTOM) paintA else paintB

/**
 * 把两套颜色整体对调。
 *
 * 原版让玩家先选颜色再开局（颜色还决定先后手），棋盘上的方位是固定的，
 * 所以"玩家选了另一个颜色"在渲染层就是 A/B 互换。
 */
fun BoardColors.mirrored(): BoardColors = copy(
    sideA = sideB,
    sideAContainer = sideBContainer,
    sideAOn = sideBOn,
    sideB = sideA,
    sideBContainer = sideAContainer,
    sideBOn = sideAOn,
    paintA = paintB,
    paintB = paintA,
    selection = paintB.face,
)

@Composable
fun CrazyChessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> fallbackDark()
        else -> fallbackLight()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = ExpressiveTypography,
        content = content,
    )
}
