package dev.whale.crazychess.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 棋盘与棋子的规则常量。坐标系以"格"为单位：棋盘是 8x8，x/y 都落在 0f..8f，
 * y 轴向下（第 0 行在屏幕上方）。
 */
object Rules {
    const val BOARD = 8f

    /** 棋子半径（格）。原版贴图几乎填满一格，这里取 0.44 让棋子之间只留一条细缝。 */
    const val RADIUS = 0.44f

    /** 全场速度低于该值（格/秒）才算"棋盘静止"，静止后才可以出手。 */
    const val REST_SPEED = 0.05f

    /** 慢速临界值：低于它持续 [SETTLE_TIME] 秒就强制判停，避免棋子互相挤住导致永远轮不到下一手。 */
    const val SETTLE_SPEED = 0.35f
    const val SETTLE_TIME = 0.45f

    /** 松手时 速度 = 拖拽位移(格) x 该系数。原版像素偏移 /32 再按 32px=1m 换算，正好等于"格/秒 = 拖拽格数"。 */
    const val SHOT_POWER = 1.0f

    /** 拖拽位移小于该值（格）视为误触。 */
    const val MIN_DRAG = 0.22f

    /** 拖拽长度上限（格），避免无限蓄力。 */
    const val MAX_DRAG = 5.5f

    /** 碰撞恢复系数，原版 Box2D 夹具是 0.5。 */
    const val RESTITUTION = 0.5f

    /** 每帧最多推进的物理子步数，防止掉帧时穿模。 */
    const val MAX_SUBSTEPS = 6

    /** AI 思考时间（秒），原版是 1 秒 TimerHandler。 */
    const val AI_THINK_TIME = 0.85f

    /** 出局动效时长（毫秒）。 */
    const val FALL_FX_MS = 520L
}

enum class Side(val label: String) {
    BOTTOM("下方"),
    TOP("上方");

    val other: Side get() = if (this == BOTTOM) TOP else BOTTOM
}

/**
 * 棋子类型。质量 / 阻尼 / 分值都取自原版 BattleFieldBase.createFigure1：
 * 兵 800、车 2000、马 1800、象 1500、后 2200、王 3000；线阻尼统一 1.2，王是 6.0。
 * 这里把质量除以 1000，单位变成"千克"，相对关系不变。
 */
enum class PieceType(
    val glyph: String,
    val label: String,
    val mass: Float,
    val points: Int,
    val damping: Float,
) {
    PAWN("♟", "兵", 0.8f, 5, 1.2f),
    ROOK("♜", "车", 2.0f, 30, 1.2f),
    KNIGHT("♞", "马", 1.8f, 25, 1.2f),
    BISHOP("♝", "象", 1.5f, 20, 1.2f),
    QUEEN("♛", "后", 2.2f, 50, 1.2f),
    KING("♚", "王", 3.0f, 100, 6.0f);

    /** 原版 getStrongestFigure 的优先级：后 > 车 > 马 > 象 > 兵 > 王。 */
    val strength: Int
        get() = when (this) {
            QUEEN -> 5
            ROOK -> 4
            KNIGHT -> 3
            BISHOP -> 2
            PAWN -> 1
            KING -> 0
        }
}

enum class WinCondition(val label: String) {
    KILL_KING("擒王"),
    KILL_EM_ALL("全歼"),
}

enum class GameMode(val label: String) {
    VS_ANDROID("人机对战"),
    VS_HUMAN("双人对战"),
}

enum class Difficulty(val label: String, val aimNoiseDeg: Float, val power: Float) {
    EASY("轻松", 14f, 0.82f),
    NORMAL("标准", 5f, 0.95f),
    HARD("凶狠", 1.5f, 1.06f),
}

class Piece(
    val id: Int,
    val type: PieceType,
    val side: Side,
    var x: Float,
    var y: Float,
) {
    var vx = 0f
    var vy = 0f
    var alive = true
    var radius = Rules.RADIUS

    /** 己方每死一枚子，王的线阻尼会被削掉一点（原版 onDieFigure 的滚雪球机制）。 */
    var damping: Float = type.damping

    val mass: Float get() = type.mass
    val points: Int get() = type.points

    fun speed(): Float = hypot(vx, vy)
}

/** 一步棋的记录，只用于界面上的"最近一手"提示。 */
data class MoveRecord(val side: Side, val piece: PieceType, val targetX: Float, val targetY: Float)

/** 一次死亡事件，供界面播放破碎动效。 */
data class KillFx(val x: Float, val y: Float, val colorSide: Side, val startTimeMs: Long)

/** 一枚掉出棋盘的棋子，界面按这个数据把棋子"飞出去"的动画画完。 */
data class FallFx(
    val type: PieceType,
    val side: Side,
    val fromX: Float,
    val fromY: Float,
    val vx: Float,
    val vy: Float,
    val startTimeMs: Long,
)

class GameEngine(
    val mode: GameMode,
    val winCondition: WinCondition,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val seed: Int = Random.nextInt(),
    /** 底方是否执「先手色」。原版里先手由颜色决定，不看坐哪边；底方执后手色时由顶方开局。 */
    val bottomMovesFirst: Boolean = true,
) {
    val pieces = ArrayList<Piece>(32)
    val random = Random(seed)

    var activeSide = Side.BOTTOM
        private set
    var scoreP1 = 0
        private set
    var scoreP2 = 0
        private set
    var isOver = false
        private set
    var winner: Side? = null
        private set
    var isDraw = false

    val killFx = ArrayList<KillFx>()
    val falls = ArrayList<FallFx>()

    /** 每次有人出手就 +1，界面靠它触发甩子音效（玩家与 AI 一视同仁）。 */
    var shotStamp = 0
        private set

    /** 最近一次出手的一方，界面用来给音效微调音色。 */
    var lastShotBy: Side = Side.BOTTOM
        private set

    /** 全场低速持续了多久，用来在"卡住"时强制判停。 */
    private var stillTime = 0f

    /** 供界面显示的一行状态文案。 */
    var status: String = ""
        private set

    init {
        reset()
    }

    fun reset() {
        pieces.clear()
        killFx.clear()
        falls.clear()
        stillTime = 0f
        scoreP1 = 0
        scoreP2 = 0
        isOver = false
        winner = null
        isDraw = false
        activeSide = if (bottomMovesFirst) Side.BOTTOM else Side.TOP
        status = if (mode == GameMode.VS_ANDROID && activeSide == Side.TOP) {
            "AI 正在瞄准…"
        } else {
            "轮到${activeSide.label}出手"
        }
        var id = 0
        for (side in Side.entries) {
            val backRow = if (side == Side.BOTTOM) 7 else 0
            val pawnRow = if (side == Side.BOTTOM) 6 else 1
            val layout = listOf(
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK,
            )
            for (col in 0..7) {
                pieces.add(Piece(id++, PieceType.PAWN, side, col + 0.5f, pawnRow + 0.5f))
            }
            for (col in 0..7) {
                pieces.add(Piece(id++, layout[col], side, col + 0.5f, backRow + 0.5f))
            }
        }
    }

    fun piecesOf(side: Side): List<Piece> = pieces.filter { it.alive && it.side == side }

    fun kingOf(side: Side): Piece? = pieces.firstOrNull { it.alive && it.side == side && it.type == PieceType.KING }

    /** 所有存活棋子都停下来了吗？（原版 hasMovementStopped） */
    fun isSettled(): Boolean = pieces.none { it.alive && it.speed() > Rules.SETTLE_SPEED }

    /** 现在轮到 [side] 出手吗？ */
    fun canAct(side: Side): Boolean = !isOver && activeSide == side && isSettled()

    fun hitTest(x: Float, y: Float): Piece? {
        var best: Piece? = null
        var bestDist = Float.MAX_VALUE
        for (p in pieces) {
            if (!p.alive) continue
            val d = hypot(p.x - x, p.y - y)
            if (d <= p.radius * 1.05f && d < bestDist) {
                bestDist = d
                best = p
            }
        }
        return best
    }

    /**
     * 出手：把拖拽位移换算成速度（原版 body.setLinearVelocity(touch - center)），
     * 然后立刻换手 —— 和原版一样，不等棋子飞完。
     */
    fun shoot(piece: Piece, dx: Float, dy: Float): Boolean {
        if (!piece.alive || piece.side != activeSide || isOver) return false
        val len = hypot(dx, dy)
        if (len < Rules.MIN_DRAG) return false
        val scale = if (len > Rules.MAX_DRAG) Rules.MAX_DRAG / len else 1f
        piece.vx = dx * scale * Rules.SHOT_POWER
        piece.vy = dy * scale * Rules.SHOT_POWER
        lastShotBy = piece.side
        shotStamp++
        endTurn()
        return true
    }

    private fun endTurn() {
        activeSide = activeSide.other
        status = if (mode == GameMode.VS_ANDROID && activeSide == Side.TOP) {
            "对手正在瞄准…"
        } else {
            "轮到${activeSide.label}出手"
        }
    }

    fun tick(dtSeconds: Float, nowMs: Long) {
        if (isOver) {
            killFx.removeAll { nowMs - it.startTimeMs > 700 }
            falls.removeAll { nowMs - it.startTimeMs > Rules.FALL_FX_MS }
            return
        }
        var remaining = dtSeconds.coerceAtMost(0.05f)
        val step = 1f / 120f
        var guard = 0
        while (remaining > 0f && guard < Rules.MAX_SUBSTEPS) {
            val h = minOf(step, remaining)
            integrate(h)
            resolveCollisions()
            checkDeaths(nowMs)
            remaining -= h
            guard++
            if (isOver) break
        }
        settleIfStuck(dtSeconds)
        killFx.removeAll { nowMs - it.startTimeMs > 700 }
        falls.removeAll { nowMs - it.startTimeMs > Rules.FALL_FX_MS }
    }

    /**
     * 棋子被挤在一起时速度可能永远降不到 0，导致"等停稳"的出手条件永远不成立。
     * 这里给一个兜底：全场低速维持 [Rules.SETTLE_TIME] 秒就直接清零所有速度。
     */
    private fun settleIfStuck(dtSeconds: Float) {
        val speedy = pieces.any { it.alive && it.speed() > Rules.SETTLE_SPEED }
        if (speedy) {
            stillTime = 0f
            return
        }
        stillTime += dtSeconds
        if (stillTime >= Rules.SETTLE_TIME) {
            for (p in pieces) {
                p.vx = 0f
                p.vy = 0f
            }
        }
    }

    private fun integrate(h: Float) {
        for (p in pieces) {
            if (!p.alive) continue
            if (p.vx == 0f && p.vy == 0f) continue
            p.x += p.vx * h
            p.y += p.vy * h
            // 指数量级的线性阻尼；原版是 Box2D setLinearDamping，等价于 v *= e^(-damping*dt)
            val decay = (1f - p.damping * h).coerceIn(0f, 1f)
            p.vx *= decay
            p.vy *= decay
            if (p.speed() < Rules.REST_SPEED * 0.5f) {
                p.vx = 0f
                p.vy = 0f
            }
        }
    }

    private fun resolveCollisions() {
        val n = pieces.size
        for (i in 0 until n) {
            val a = pieces[i]
            if (!a.alive) continue
            for (j in i + 1 until n) {
                val b = pieces[j]
                if (!b.alive) continue
                resolvePair(a, b)
            }
        }
    }

    private fun resolvePair(a: Piece, b: Piece) {
        var dx = b.x - a.x
        var dy = b.y - a.y
        var dist = hypot(dx, dy)
        val minDist = a.radius + b.radius
        if (dist >= minDist) return
        if (dist < 1e-4f) {
            dx = 1f
            dy = 0f
            dist = 1f
        }
        val nx = dx / dist
        val ny = dy / dist
        val invA = 1f / a.mass
        val invB = 1f / b.mass
        val invSum = invA + invB

        // 位置修正，按质量反比分配
        val overlap = minDist - dist
        a.x -= nx * overlap * (invA / invSum)
        a.y -= ny * overlap * (invA / invSum)
        b.x += nx * overlap * (invB / invSum)
        b.y += ny * overlap * (invB / invSum)

        val rvn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        if (rvn > 0f) return
        val impulse = -(1f + Rules.RESTITUTION) * rvn / invSum
        a.vx -= impulse * invA * nx
        a.vy -= impulse * invA * ny
        b.vx += impulse * invB * nx
        b.vy += impulse * invB * ny
    }

    /**
     * 出局判定：棋子**中心**离开棋盘（原版是撞到棋盘外侧的围墙），
     * 所以棋子会先压到边线上、再整个滑出去，视觉上就像掉下棋盘。
     */
    private fun checkDeaths(nowMs: Long) {
        for (p in pieces) {
            if (!p.alive) continue
            val out = p.x < 0f || p.x > Rules.BOARD || p.y < 0f || p.y > Rules.BOARD
            if (out) kill(p, nowMs)
        }
    }

    private fun kill(p: Piece, nowMs: Long) {
        p.alive = false
        falls.add(FallFx(p.type, p.side, p.x, p.y, p.vx, p.vy, nowMs))
        p.vx = 0f
        p.vy = 0f
        killFx.add(KillFx(p.x.coerceIn(0f, Rules.BOARD), p.y.coerceIn(0f, Rules.BOARD), p.side, nowMs))
        when (p.side) {
            Side.BOTTOM -> scoreP2 += p.points
            Side.TOP -> scoreP1 += p.points
        }
        // 原版：己方损失一枚子，己方王的线阻尼下降 → 王越来越容易被推出去
        val ownKing = kingOf(p.side)
        if (ownKing != null && p.type != PieceType.KING) {
            val cut = when (p.type) {
                PieceType.QUEEN -> 1.2f
                PieceType.ROOK -> 0.8f
                PieceType.BISHOP, PieceType.KNIGHT -> 0.5f
                PieceType.PAWN -> 0.15f
                PieceType.KING -> 0f
            }
            ownKing.damping = (ownKing.damping - cut).coerceAtLeast(0.35f)
        }
        evaluateEnd()
    }

    private fun evaluateEnd() {
        if (isOver) return
        val bottomAlive = piecesOf(Side.BOTTOM)
        val topAlive = piecesOf(Side.TOP)
        if (bottomAlive.isEmpty() && topAlive.isEmpty()) {
            finishDraw()
            return
        }
        when (winCondition) {
            WinCondition.KILL_KING -> {
                val bottomKing = bottomAlive.any { it.type == PieceType.KING }
                val topKing = topAlive.any { it.type == PieceType.KING }
                if (!bottomKing && !topKing) finishDraw()
                else if (!bottomKing) finish(Side.TOP)
                else if (!topKing) finish(Side.BOTTOM)
            }

            WinCondition.KILL_EM_ALL -> {
                if (bottomAlive.isEmpty()) finish(Side.TOP)
                else if (topAlive.isEmpty()) finish(Side.BOTTOM)
            }
        }
    }

    private fun finish(side: Side) {
        isOver = true
        winner = side
        status = "${side.label}获胜"
    }

    private fun finishDraw() {
        isOver = true
        winner = null
        isDraw = true
        status = "平局"
    }

    // ---------------------------------------------------------------- AI

    /**
     * 复刻原版 BattleFieldBase.androidKick()：
     * 不搜索走法，只挑一枚"最强"的子，朝目标连一条线，把速度设成"朝该方向、大小按到墙的距离"。
     * 原版因为用了旧场地常量（384x400）导致力度偏大，这里改成按目标到墙的实际距离给力度。
     */
    fun aiShoot() {
        if (isOver || activeSide != Side.TOP) return
        val mine = piecesOf(Side.TOP)
        val enemy = piecesOf(Side.BOTTOM)
        if (mine.isEmpty() || enemy.isEmpty()) return

        var attacker = strongest(mine) ?: return
        var target = strongest(enemy) ?: return

        // 对手最强子贴着边（有掉出去的风险）→ 换一个离它最远的己方子，避免把自己人撞出去
        if (danger(target) < 0.1f) {
            attacker = farthestFrom(mine, target) ?: attacker
        }

        // 原版 18% 概率：换次强子去撞对方的王
        if (random.nextFloat() < 0.18f) {
            attacker = nextStrongest(mine, attacker) ?: attacker
            target = kingOf(Side.BOTTOM) ?: target
        }

        // 自家王危险 → 王亲自上（原版 kingArmyTop.getDanger() < 0.1）
        val myKing = kingOf(Side.TOP)
        if (myKing != null && danger(myKing) < 0.1f) {
            val enemyKing = kingOf(Side.BOTTOM)
            if (enemyKing != null) {
                attacker = myKing
                target = enemyKing
            }
        }

        val noise = Math.toRadians(difficulty.aimNoiseDeg.toDouble()).toFloat()
        val jitter = (random.nextFloat() * 2f - 1f) * noise
        var dx = target.x - attacker.x
        var dy = target.y - attacker.y
        val len = hypot(dx, dy).coerceAtLeast(0.001f)
        val cos = kotlin.math.cos(jitter)
        val sin = kotlin.math.sin(jitter)
        val rx = dx / len * cos - dy / len * sin
        val ry = dx / len * sin + dy / len * cos

        // 想把目标顶出棋盘：需要的位移 = 到目标的距离 + 目标身后到边线的距离。
        // 阻尼让速度按 v*e^(-k t) 衰减，行程 ≈ v/k，所以初速取 (行程 * k)。
        val behind = distanceToWall(target.x, target.y, rx, ry)
        val desired = (len + behind + 0.5f) * attacker.damping * difficulty.power
        val maxSpeed = Rules.MAX_DRAG * 1.02f * difficulty.power
        val speed = desired.coerceIn(1.2f, maxSpeed)

        attacker.vx = rx * speed
        attacker.vy = ry * speed
        lastShotBy = attacker.side
        shotStamp++
        endTurn()
    }

    private fun strongest(list: List<Piece>): Piece? =
        list.filter { it.alive }.maxByOrNull { it.type.strength }

    private fun nextStrongest(list: List<Piece>, exclude: Piece): Piece? =
        list.filter { it.alive && it !== exclude }
            .filter { it.type.strength <= exclude.type.strength }
            .maxByOrNull { it.type.strength }
            ?: list.filter { it.alive && it !== exclude }.maxByOrNull { it.type.strength }

    private fun farthestFrom(list: List<Piece>, from: Piece): Piece? =
        list.filter { it.alive }
            .maxByOrNull { (it.x - from.x) * (it.x - from.x) + (it.y - from.y) * (it.y - from.y) }

    /** 中心到最近边界的距离 / 棋盘半径，0 = 贴边，≈0.94 = 正中（原版 Figure.getDanger）。 */
    fun danger(p: Piece): Float {
        val d = minOf(p.x, p.y, Rules.BOARD - p.x, Rules.BOARD - p.y)
        return (d / (Rules.BOARD * 0.5f)).coerceIn(0f, 1f)
    }

    private fun distanceToWall(x: Float, y: Float, dx: Float, dy: Float): Float {
        var t = Float.MAX_VALUE
        if (dx > 1e-4f) t = minOf(t, (Rules.BOARD - x) / dx)
        if (dx < -1e-4f) t = minOf(t, -x / dx)
        if (dy > 1e-4f) t = minOf(t, (Rules.BOARD - y) / dy)
        if (dy < -1e-4f) t = minOf(t, -y / dy)
        return if (t == Float.MAX_VALUE) 0f else t
    }

    /** AI 选中的棋子与目标，供界面画"对手瞄准"提示。 */
    fun aiPreview(): Pair<Piece, Pair<Float, Float>>? {
        if (isOver) return null
        val mine = piecesOf(Side.TOP)
        val enemy = piecesOf(Side.BOTTOM)
        val attacker = strongest(mine) ?: return null
        val target = strongest(enemy) ?: return null
        return attacker to (target.x to target.y)
    }

    fun totalAlive(side: Side): Int = pieces.count { it.alive && it.side == side }

    fun absSpeed(p: Piece) = abs(p.speed())
}
