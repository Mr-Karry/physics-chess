package dev.whale.crazychess.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.PieceType
import dev.whale.crazychess.game.Side
import dev.whale.crazychess.game.WinCondition
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStart: (mode: GameMode, difficulty: Difficulty, win: WinCondition, bottomMovesFirst: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme.boardColors
    var mode by remember { mutableStateOf(GameMode.VS_ANDROID) }
    var difficulty by remember { mutableStateOf(Difficulty.NORMAL) }
    var win by remember { mutableStateOf(WinCondition.KILL_KING) }
    var bottomFirst by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 560.dp),
        ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "弹棋",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Physics Chess",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PieceHalo(colors)
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "按住自己的棋子朝目标方向拖，松手把它甩出去：撞开挡路的子，把对方的王挤出棋盘。\n棋子互相碰撞不会碎，整个滑出棋盘才算出局。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        ModeCard(
            title = "人机对战",
            subtitle = "AI 会挑最强的子去撞你的王",
            selected = mode == GameMode.VS_ANDROID,
            accent = colors.sideA,
            container = colors.sideAContainer,
            onColor = colors.sideAOn,
            onClick = { mode = GameMode.VS_ANDROID },
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            title = "双人对战",
            subtitle = "同一台手机轮流甩子",
            selected = mode == GameMode.VS_HUMAN,
            accent = colors.sideB,
            container = colors.sideBContainer,
            onColor = colors.sideBOn,
            onClick = { mode = GameMode.VS_HUMAN },
        )

        if (mode == GameMode.VS_ANDROID) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("AI 强度")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Difficulty.entries.forEachIndexed { index, d ->
                    SegmentedButton(
                        selected = difficulty == d,
                        onClick = { difficulty = d },
                        shape = SegmentedButtonDefaults.itemShape(index, Difficulty.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) { Text(d.label) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("胜利条件")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WinCondition.entries.forEachIndexed { index, w ->
                SegmentedButton(
                    selected = win == w,
                    onClick = { win = w },
                    shape = SegmentedButtonDefaults.itemShape(index, WinCondition.entries.size),
                ) { Text(w.label) }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("棋子颜色 · 决定先后手")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ColorChoiceCard(
                title = "我方先出",
                subtitle = "执此色",
                paint = colors.paintA,
                selected = bottomFirst,
                onClick = { bottomFirst = true },
                modifier = Modifier.weight(1f),
            )
            ColorChoiceCard(
                title = "对方先出",
                subtitle = "执此色",
                paint = colors.paintB,
                selected = !bottomFirst,
                onClick = { bottomFirst = false },
                modifier = Modifier.weight(1f),
            )
        }
        }

        // 主要按钮固定在底部，内容再长也不用滚才能点到「开始对局」
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { onStart(mode, difficulty, win, bottomFirst) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    "开始对局",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "配色随系统壁纸变化 · Material 3 Expressive",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    container: Color,
    onColor: Color,
    onClick: () -> Unit,
) {
    val elevation by animateDpAsState(if (selected) 8.dp else 1.dp, spring(), label = "elev")
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) container else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.45f), accent),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (title == "人机对战") "♚" else "♞",
                    fontSize = 22.sp,
                    color = onColor,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (selected) onColor else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) onColor.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Surface(color = accent, shape = CircleShape) {
                    Box(Modifier.size(10.dp))
                }
            }
        }
    }
}

/** 选颜色 = 选先后手：卡片上是这款棋子实际会用的颜色，文字直接说明谁先出。 */
@Composable
private fun ColorChoiceCard(
    title: String,
    subtitle: String,
    paint: PiecePaint,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(paint.face)
                    .border(2.dp, paint.rim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", fontSize = 13.sp, color = paint.ink)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 首页右上角的装饰：一枚棋子色块，顺便展示当前的动态配色。 */
@Composable
private fun PieceHalo(colors: BoardColors) {
    Box(
        Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(colors.sideAContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.55f), colors.sideAContainer),
                    ),
                )
                .border(3.dp, colors.sideA.copy(alpha = 0.45f), CircleShape),
        )
        Text("♛", fontSize = 32.sp, color = colors.sideAOn)
    }
}

/** 让 HomeScreen 的随机种子在下次开局时换一局。 */
fun newSeed(): Int = Random.nextInt()
