package dev.whale.crazychess.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whale.crazychess.i18n.I18n
import dev.whale.crazychess.SoundFx
import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameEngine
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.Piece
import dev.whale.crazychess.game.PieceType
import dev.whale.crazychess.game.Rules
import dev.whale.crazychess.game.Side
import dev.whale.crazychess.game.WinCondition
import dev.whale.crazychess.net.LanEvent
import dev.whale.crazychess.net.LanSession
import dev.whale.crazychess.ui.PiecePaint
import kotlinx.coroutines.delay
/** 棋盘最多左右各平移 3.5 格（占棋盘宽度的比例），再往两边就没意义了。 */
private const val PAN_LIMIT = 0.4375f


/** 每帧从引擎里取一份快照。用基本类型 + 不可变列表，保证 Compose 一定能看出变化并重组。 */
private data class Snapshot(
    val scoreBottom: Int = 0,
    val scoreTop: Int = 0,
    val aliveBottom: Int = 16,
    val aliveTop: Int = 16,
    val active: Side = Side.BOTTOM,
    val over: Boolean = false,
    val draw: Boolean = false,
    val winner: Side? = null,
    val settled: Boolean = true,
    val fallen: List<Pair<Side, PieceType>> = emptyList(),
)

private fun GameEngine.snapshot(): Snapshot = Snapshot(
    scoreBottom = scoreP1,
    scoreTop = scoreP2,
    aliveBottom = totalAlive(Side.BOTTOM),
    aliveTop = totalAlive(Side.TOP),
    active = activeSide,
    over = isOver,
    draw = isDraw,
    winner = winner,
    settled = isSettled(),
    fallen = pieces.filter { !it.alive }.map { it.side to it.type },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    mode: GameMode,
    difficulty: Difficulty,
    winCondition: WinCondition,
    engineSeed: Int,
    bottomMovesFirst: Boolean = true,
    lan: LanSession? = null,
    mySide: Side = Side.BOTTOM,
    myName: String = "你",
    peerName: String = "对手",
    onExit: () -> Unit,
) {
    // 底方执「先手色」还是「后手色」：颜色整套对调，先手方永远是先手色那一方
    val colors = if (bottomMovesFirst) {
        MaterialTheme.colorScheme.boardColors
    } else {
        MaterialTheme.colorScheme.boardColors.mirrored()
    }
    val haptic = LocalHapticFeedback.current
    val engine = remember(engineSeed) {
        GameEngine(mode, winCondition, difficulty, engineSeed, bottomMovesFirst)
    }

    var tick by remember { mutableLongStateOf(0L) }
    var showPause by remember { mutableStateOf(false) }
    // showResult 一置位就开始播"胜利印章 + 欢呼"，结算弹窗等印章站稳了再出来
    var showResult by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var lastShot by remember { mutableLongStateOf(0L) }

    // 每帧把引擎状态写进 Compose state，界面各处都直接读它 —— 这样计分板、
    // 子数、战果条一定会跟着物理一起更新，不依赖子 composable 的参数是否"看起来变了"。
    val snapshotState = remember { mutableStateOf(engine.snapshot()) }

    var hint by remember { mutableStateOf<String?>(null) }
    var hintId by remember { mutableLongStateOf(0L) }

    // ---------------- 局域网 ----------------
    // 对手那一手我先本地推演一遍用于动画，但真正的结果以他后来那条 state 为准，
    // 在这之前先锁住输入，避免两边各算一套物理越走越远。
    var awaitingSync by remember { mutableStateOf(false) }
    var peerGone by remember { mutableStateOf<String?>(null) }
    var peerLabel by remember(lan) { mutableStateOf(peerName.ifBlank { "对手" }) }
    var myShotSeq by remember { mutableIntStateOf(0) }
    var sentSeq by remember { mutableIntStateOf(0) }

    // 棋盘横向平移：以棋盘宽度的比例表示，-PAN_LIMIT..PAN_LIMIT。
    // 由棋盘上/下方的拖动条驱动；人机模式下轮到 AI 时自动回中。
    var panFraction by remember { mutableStateOf(0f) }
    var boardWidthPx by remember { mutableStateOf(0f) }
    val panActive = snapshotState.value.active
    LaunchedEffect(panActive) {
        // 换手时棋盘回到正中（对齐原版的 returnCamera）：这一手打完，下一位玩家面对的是完整棋盘
        if (panFraction != 0f) {
            androidx.compose.animation.core.animate(
                initialValue = panFraction,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(320),
            ) { value, _ -> panFraction = value }
        }
    }

    // 游戏主循环：60fps 推进物理，并驱动 AI 的"思考"
    LaunchedEffect(engine) {
        var last = 0L
        var think = 0f
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                val nowMs = android.os.SystemClock.elapsedRealtime()
                engine.tick(dt.coerceAtMost(0.05f), nowMs)
                tick++

                // 甩子音效：玩家拖拽和 AI 出手都会推进 shotStamp，界面统一在这里播，
                // 这样人机对战里对手出手也有声音（此前 AI 出手是哑的）。
                if (engine.shotStamp > lastShot) {
                    lastShot = engine.shotStamp.toLong()
                    SoundFx.shot(byTop = engine.lastShotBy == Side.TOP)
                }

                if (mode == GameMode.VS_ANDROID && !engine.isOver) {
                    if (engine.activeSide == Side.TOP && engine.isSettled()) {
                        think += dt
                        if (think >= Rules.AI_THINK_TIME) {
                            engine.aiShoot()
                            think = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        think = 0f
                    }
                }

                if (engine.isOver && !showResult) {
                    showResult = true
                    // 原版：决定胜负的那枚棋子掉出棋盘，才有那声特别的响动 + 欢呼
                    SoundFx.decide()
                }

                // 局域网：我出手之后，等棋盘停稳就把权威局面发给对端（谁出手谁权威）
                if (lan != null && myShotSeq > sentSeq && (engine.isSettled() || engine.isOver)) {
                    lan.sendState(engine.exportState())
                    sentSeq = myShotSeq
                }

                snapshotState.value = engine.snapshot()
            }
        }
    }

    // 欢呼连着来两轮（原版 crowd 也是循环两次），印章在屏幕上停够 3 秒多再把结算弹窗推上来。
    // 平局没有印章可看，直接弹结算。
    LaunchedEffect(showResult) {
        if (!showResult) return@LaunchedEffect
        if (engine.isDraw) {
            SoundFx.cheer()
            showResultDialog = true
            return@LaunchedEffect
        }
        repeat(2) {
            SoundFx.cheer()
            delay(1600)
        }
        showResultDialog = true
    }

    LaunchedEffect(hintId) {
        if (hintId > 0L) {
            delay(1600)
            hint = null
        }
    }

    // 局域网事件：对手出手 / 对手同步局面 / 认输 / 掉线
    LaunchedEffect(lan) {
        val session = lan ?: return@LaunchedEffect
        session.events.collect { ev ->
            when (ev) {
                is LanEvent.RemoteShot -> {
                    val piece = engine.pieces.firstOrNull { it.id == ev.pieceId }
                    if (piece != null) {
                        // 先在本地推演一遍，动画才不会是"瞬移"；正解随后那条 state 再覆盖
                        awaitingSync = true
                        engine.shoot(piece, ev.dx, ev.dy)
                    }
                }

                is LanEvent.RemoteState -> {
                    engine.applyState(ev.state)
                    awaitingSync = false
                }

                LanEvent.RemoteResigned -> engine.forceEnd(mySide, "对手认输了")

                LanEvent.RemoteRematch -> {
                    engine.reset()
                    showResult = false
                    showResultDialog = false
                    awaitingSync = false
                    myShotSeq = 0
                    sentSeq = 0
                }

                is LanEvent.PeerLeft -> peerGone = ev.reason

                is LanEvent.PeerJoined -> peerLabel = ev.name
            }
        }
    }

    // 兜底：对端那条 state 万一没来（丢包 / 掉线），别把输入锁死
    LaunchedEffect(awaitingSync) {
        if (awaitingSync) {
            delay(6000)
            awaitingSync = false
        }
    }

    val controlSide = when {
        lan != null -> mySide
        mode == GameMode.VS_ANDROID -> Side.BOTTOM
        else -> engine.activeSide
    }

    // 局域网：本地出手后立刻把手发给对端，停稳后再补一条权威局面
    val onLocalShot: (Piece, Float, Float) -> Unit = { piece, dx, dy ->
        if (lan != null) {
            myShotSeq++
            lan.sendShot(piece.id, dx, dy)
        }
    }

    val invalidHint: () -> Unit = {
        hint = when {
            engine.isOver -> "本局已经结束"
            !engine.isSettled() -> "等棋子停稳再出手"
            engine.activeSide != controlSide -> I18n.turnIsNow(engine.activeSide.label)
            else -> "按住自己的棋子拖动"
        }
        hintId++
    }

    Scaffold { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 在这个组合作用域里读取状态，保证物理每推进一帧都会刷新整套界面
            val s = snapshotState.value
            val turnText = when {
                s.over -> when {
                    s.draw -> "平局"
                    lan != null -> if (s.winner == mySide) "你赢了" else "对手获胜"
                    s.winner != null -> I18n.sideWins(s.winner!!.label)
                    else -> I18n.t("本局结束")
                }

                lan != null && awaitingSync -> "同步中…"
                lan != null && s.active != mySide -> "等对手出手…"
                lan != null -> "轮到你出手"
                mode == GameMode.VS_ANDROID && s.active == Side.TOP -> "对手正在瞄准…"
                mode == GameMode.VS_ANDROID -> "轮到你出手"
                else -> I18n.turnOf(s.active.label)
            }
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 用显式 size 而不是 aspectRatio(fillMaxHeight)，避免横屏下棋盘比可视区域高
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // 双人模式：横屏时上面也放一条（用顶方的颜色），两边都能挪
                            if (mode == GameMode.VS_HUMAN) {
                                PanBar(panFraction, colors.paintB) { dx ->
                                    if (boardWidthPx > 0f) {
                                        panFraction = (panFraction + dx / boardWidthPx)
                                            .coerceIn(-PAN_LIMIT, PAN_LIMIT)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                val side = minOf(maxWidth, maxHeight) - 8.dp
                                BoardView(
                                    engine = engine,
                                    colors = colors,
                                    controlSide = controlSide,
                                    frameTick = tick,
                                    panFraction = panFraction,
                                    modifier = Modifier
                                        .size(side)
                                        .onSizeChanged { boardWidthPx = it.width.toFloat() },
                                    onShot = onLocalShot,
                                    onSelect = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    onInvalid = invalidHint,
                                    inputEnabled = !awaitingSync,
                                    // 联机里执上方的人：棋盘 180° 镜像，自己的子落在屏幕下方
                                    flipped = lan != null && mySide == Side.TOP,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            PanBar(
                                panFraction = panFraction,
                                paint = colors.paintA,
                            ) { dx ->
                                if (boardWidthPx > 0f) {
                                    panFraction = (panFraction + dx / boardWidthPx)
                                        .coerceIn(-PAN_LIMIT, PAN_LIMIT)
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (mode == GameMode.VS_HUMAN) {
                            // 双人：上半屏的信息旋转 180°，坐在对面的人也能正常读
                            HumanPlayerBar(Side.TOP, s, colors, mirrored = true)
                            Spacer(Modifier.height(10.dp))
                            HumanPlayerBar(
                                Side.BOTTOM, s, colors, mirrored = false,
                                onPause = { showPause = true },
                            )
                        } else {
                            ScoreHeader(
                                mode = mode,
                                snap = s,
                                colors = colors,
                                onPause = { showPause = true },
                                stacked = true,
                                myName = myName,
                                peerName = peerLabel,
                                mySide = mySide,
                            )
                            Spacer(Modifier.height(14.dp))
                            TurnBanner(turnText, s, colors)
                        }
                        Spacer(Modifier.height(10.dp))
                        FallenStrip(s, colors)
                        Spacer(Modifier.height(10.dp))
                        HintLine(hint)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (mode == GameMode.VS_HUMAN) {
                        HumanPlayerBar(Side.TOP, s, colors, mirrored = true)
                    } else {
                        ScoreHeader(
                            mode = mode,
                            snap = s,
                            colors = colors,
                            onPause = { showPause = true },
                            stacked = false,
                            myName = myName,
                            peerName = peerLabel,
                            mySide = mySide,
                        )
                    }
                    Spacer(Modifier.weight(if (mode == GameMode.VS_HUMAN) 1f else 0.45f))
                    // 双人模式：屏幕上方也有一条，坐在对面的人同样能挪棋盘
                    if (mode == GameMode.VS_HUMAN) {
                        PanBar(panFraction, colors.paintB) { dx ->
                            if (boardWidthPx > 0f) {
                                panFraction = (panFraction + dx / boardWidthPx).coerceIn(-PAN_LIMIT, PAN_LIMIT)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    BoardView(
                        engine = engine,
                        colors = colors,
                        controlSide = controlSide,
                        frameTick = tick,
                        panFraction = panFraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .aspectRatio(1f)
                            .onSizeChanged { boardWidthPx = it.width.toFloat() },
                        onShot = onLocalShot,
                        onSelect = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        onInvalid = invalidHint,
                        inputEnabled = !awaitingSync,
                        flipped = lan != null && mySide == Side.TOP,
                    )
                    Spacer(Modifier.height(8.dp))
                    PanBar(panFraction, colors.paintA) { dx ->
                        if (boardWidthPx > 0f) {
                            panFraction = (panFraction + dx / boardWidthPx).coerceIn(-PAN_LIMIT, PAN_LIMIT)
                        }
                    }
                    Spacer(Modifier.weight(if (mode == GameMode.VS_HUMAN) 1f else 0.12f))
                    if (mode == GameMode.VS_HUMAN) {
                        HumanPlayerBar(
                            Side.BOTTOM, s, colors, mirrored = false,
                            onPause = { showPause = true },
                        )
                    } else {
                        TurnBanner(turnText, s, colors)
                    }
                    Spacer(Modifier.height(10.dp))
                    FallenStrip(s, colors)
                    Spacer(Modifier.height(8.dp))
                    HintLine(hint)
                    if (mode != GameMode.VS_HUMAN) {
                        Spacer(Modifier.weight(0.28f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // 原版终局画面：屏幕正中出现胜利一方配色的大拇指，并且朝向胜利者
            // （顶方获胜时旋转 180°，坐在对面的人看到的是正的）。
            // 联机时每人一块屏，镜像后自己的半区总在下方，所以"我的下方"是 mySide。
            val myBottom = if (lan != null) mySide else Side.BOTTOM
            val winSide = s.winner
            if (showResult && !s.draw && winSide != null) {
                WinStamp(
                    side = winSide,
                    colors = colors,
                    // 印章朝向胜利方：赢家在我这块屏的上半区就转 180°，
                    // 这样它永远是"朝赢家那一侧"的（联机镜像后自己的半区总在下方）
                    facingAway = winSide != myBottom,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    if (showPause) {
        AlertDialog(
            onDismissRequest = { showPause = false },
            title = { Text("暂停") },
            text = {
                Column {
                    Text(
                        I18n.pauseInfo(
                            engine.scoreP1,
                            engine.scoreP2,
                            winCondition.label,
                            32 - engine.totalAlive(Side.BOTTOM) - engine.totalAlive(Side.TOP),
                        ),
                    )
                    if (lan != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                showPause = false
                                lan.sendResign()
                                engine.forceEnd(mySide.other, "你认输了")
                            },
                        ) { Text("认输", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPause = false }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = onExit) { Text("退出对局") }
            },
        )
    }

    if (showResultDialog) {
        ResultDialog(
            engine = engine,
            mySide = mySide,
            lan = lan != null,
            peerGone = peerGone != null,
            onReplay = {
                if (lan != null) {
                    // 联机时「再来一局」要通知对端一起重开
                    lan.sendRematch()
                    myShotSeq = 0
                    sentSeq = 0
                    awaitingSync = false
                }
                engine.reset()
                showResult = false
                showResultDialog = false
            },
            onExit = onExit,
        )
    }

    // 对手掉线：给一个明确出口，别让人对着停住的棋盘发呆
    val gone = peerGone
    if (gone != null && !showResultDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("对手已离开") },
            text = { Text(I18n.peerGone(gone)) },
            confirmButton = { Button(onClick = onExit) { Text("返回菜单") } },
        )
    }
}

/**
 * 终局印章：胜利一方配色的大拇指，圆底 + 图标，弹入时带一点回弹。
 * `side == TOP` 时整体旋转 180°，坐在屏幕另一端的人看到的是正的。
 */
@Composable
private fun WinStamp(
    side: Side,
    colors: BoardColors,
    facingAway: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val paint = colors.paint(side)
    val pop = remember { Animatable(0.35f) }
    LaunchedEffect(side) {
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
    }
    Box(
        modifier = modifier
            .rotate(if (facingAway) 180f else 0f)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .size(168.dp)
            .clip(CircleShape)
            .background(paint.face),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ThumbUp,
            contentDescription = null,
            tint = paint.ink,
            modifier = Modifier.size(96.dp),
        )
    }
}

@Composable
private fun HintLine(hint: String?) {
    Text(
        text = hint ?: "拖动己方棋子瞄准，松手甩出 · 拖横条可左右挪棋盘",
        style = MaterialTheme.typography.bodySmall,
        color = if (hint != null) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * 棋盘平移条：左右拖动让棋盘跟着平移，给靠边的棋子腾出甩子空间。
 * 用滑块而不是"拖棋盘空白处"，避免和甩子手势抢触摸。
 * 双人模式下上下各一条（上方那条用顶方的颜色），两边都显示、都能拖。
 */
@Composable
private fun PanBar(
    panFraction: Float,
    paint: PiecePaint,
    onDelta: (Float) -> Unit,
) {
    val limit = PAN_LIMIT
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    onDelta(dx)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .height(2.dp)
                .background(paint.rim.copy(alpha = 0.45f), RoundedCornerShape(1.dp)),
        )
        // 滑块
        val thumb = 66.dp
        val travel = ((maxWidth - thumb) / 2f).coerceAtLeast(0.dp)
        val offset = travel * (panFraction / limit).coerceIn(-1f, 1f)
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - thumb) / 2f + offset)
                .size(width = thumb, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(paint.face),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⇆",
                style = MaterialTheme.typography.labelMedium,
                color = paint.ink,
            )
        }
    }
}

/**
 * 双人对战用的单边信息条：一局里出现两条，上方那条整体旋转 180°，
 * 这样坐在手机对面的玩家看到的就是正着的字。
 */
@Composable
private fun HumanPlayerBar(
    side: Side,
    snap: Snapshot,
    colors: BoardColors,
    mirrored: Boolean,
    onPause: (() -> Unit)? = null,
) {
    val c = colors.of(side)
    val paint = colors.paint(side)
    val active = snap.active == side && !snap.over
    val score = if (side == Side.BOTTOM) snap.scoreBottom else snap.scoreTop
    val alive = if (side == Side.BOTTOM) snap.aliveBottom else snap.aliveTop
    val label = if (side == Side.BOTTOM) "下方" else "上方"
    val contentColor = if (active) c.third else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rotate(if (mirrored) 180f else 0f)
            .clip(MaterialTheme.shapes.large)
            .background(if (active) c.second else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = if (onPause != null) 6.dp else 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(paint.face),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "$score",
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
            ),
            color = if (active) c.third else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$alive 子",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.75f),
        )
        if (active) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = paint.face,
                contentColor = paint.ink,
            ) {
                Text(
                    text = "出手",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (onPause != null) {
            TextButton(onClick = onPause, modifier = Modifier.height(36.dp)) {
                Text("II", fontWeight = FontWeight.Black, color = contentColor)
            }
        }
    }
}

@Composable
private fun ScoreHeader(
    mode: GameMode,
    snap: Snapshot,
    colors: BoardColors,
    onPause: () -> Unit,
    stacked: Boolean,
    myName: String = "你",
    peerName: String = "对手",
    mySide: Side = Side.BOTTOM,
) {
    // 联机时显示双方昵称；昵称跟着"人"走，卡片跟着"边"走，所以按 mySide 换一下
    val bottomName = if (mySide == Side.BOTTOM) myName else peerName
    val topName = if (mySide == Side.BOTTOM) peerName else myName
    val names = if (mode == GameMode.LAN) bottomName to topName else null
    val bottom = @Composable { modifier: Modifier ->
        ScoreCard(
            label = names?.first ?: when (mode) {
                GameMode.VS_ANDROID -> "你"
                GameMode.LAN -> "你"
                else -> "下方"
            },
            score = snap.scoreBottom,
            alive = snap.aliveBottom,
            accent = colors.sideA,
            container = colors.sideAContainer,
            onColor = colors.sideAOn,
            active = snap.active == Side.BOTTOM && !snap.over,
            modifier = modifier,
        )
    }
    val top = @Composable { modifier: Modifier ->
        ScoreCard(
            label = names?.second ?: when (mode) {
                GameMode.VS_ANDROID -> "AI"
                GameMode.LAN -> "对手"
                else -> "上方"
            },
            score = snap.scoreTop,
            alive = snap.aliveTop,
            accent = colors.sideB,
            container = colors.sideBContainer,
            onColor = colors.sideBOn,
            active = snap.active == Side.TOP && !snap.over,
            modifier = modifier,
        )
    }

    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            bottom(Modifier.fillMaxWidth())
            top(Modifier.fillMaxWidth())
            FilledTonalButton(onClick = onPause) { Text("暂停", fontWeight = FontWeight.Black) }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bottom(Modifier.weight(1f))
            top(Modifier.weight(1f))
            FilledTonalButton(onClick = onPause, modifier = Modifier.height(48.dp)) {
                Text("II", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ScoreCard(
    label: String,
    score: Int,
    alive: Int,
    accent: Color,
    container: Color,
    onColor: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (active) container else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent, shape = CircleShape, modifier = Modifier.size(12.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$alive 子",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) onColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$score",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                ),
                color = if (active) onColor else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 棋盘下方的"战果"条：把已经掉出棋盘的棋子按阵营列出来（还没有子出局时整条留空）。 */
@Composable
private fun FallenStrip(snap: Snapshot, colors: BoardColors) {
    val fallen = snap.fallen
    if (fallen.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "已碎",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fallen.take(12).forEach { (side, type) ->
            val paint = colors.paint(side)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(paint.face),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = type.glyph,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = paint.ink,
                )
            }
        }
        if (fallen.size > 12) {
            Text(
                text = "+${fallen.size - 12}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TurnBanner(text: String, snap: Snapshot, colors: BoardColors) {
    val c = colors.of(snap.active)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (snap.over) MaterialTheme.colorScheme.errorContainer else c.second,
        contentColor = if (snap.over) MaterialTheme.colorScheme.onErrorContainer else c.third,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ResultDialog(
    engine: GameEngine,
    mySide: Side = Side.BOTTOM,
    lan: Boolean = false,
    peerGone: Boolean = false,
    onReplay: () -> Unit,
    onExit: () -> Unit,
) {
    val title = when {
        engine.isDraw -> "同归于尽"
        lan -> if (engine.winner == mySide) "你赢了" else "对手获胜"
        engine.winner == Side.TOP && engine.mode == GameMode.VS_ANDROID -> "AI 获胜"
        engine.winner == Side.BOTTOM -> if (engine.mode == GameMode.VS_ANDROID) "你赢了" else "下方获胜"
        else -> "上方获胜"
    }
    // 认输 / 对手掉线这类非物理结局记在 endNote 上，优先把它显示出来。
    // 这里不能用 status.contains("认输") 判断，切到英文以后那句就不含中文了。
    val endReason = engine.endNote ?: when {
        engine.winCondition == WinCondition.KILL_KING -> "擒王模式：王被撞出棋盘即落败"
        else -> "全歼模式：吃光对方所有棋子"
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, style = MaterialTheme.typography.headlineLarge) },
        text = {
            Column {
                Text(I18n.scoreLine(engine.scoreP1, engine.scoreP2))
                Spacer(Modifier.height(6.dp))
                Text(
                    endReason,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        // 对手已经掉线就没法再来一局了，只留返回
        confirmButton = {
            if (!peerGone) {
                Button(onClick = onReplay) { Text("再来一局") }
            }
        },
        dismissButton = { TextButton(onClick = onExit) { Text("返回菜单") } },
    )
}
