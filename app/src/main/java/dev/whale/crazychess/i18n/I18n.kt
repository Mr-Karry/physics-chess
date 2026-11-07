package dev.whale.crazychess.i18n

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 界面语言。Android 与桌面版共用这一份定义。
 *
 * 翻译的做法很土但很稳：**用中文原文当 key**。源码里所有文案本来就写中文，
 * 查表时拿中文去换英文，查不到就原样返回 —— 这样新增文案永远不会炸，
 * 顶多是"这句还没翻译"。
 */
enum class Lang(val code: String, val nativeName: String) {
    ZH("zh", "中文"),
    EN("en", "English"),
    ;

    companion object {
        fun fromCode(code: String?): Lang =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ZH

        /** 系统语言是英文就用英文，其余一律中文。 */
        fun fromLocale(languageTag: String?): Lang {
            val l = languageTag?.lowercase().orEmpty()
            return if (l.startsWith("en")) EN else ZH
        }
    }
}

/**
 * 全局语言状态 + 词典。
 *
 * 纯 Kotlin（不依赖 Compose / Swing），所以 Android 和桌面版编同一份文件：
 * - Compose 侧在 `ui/Texts.kt` 里读一个 Compose 快照状态来触发重组；
 * - Swing 侧每次重绘时现算，切语言直接 repaint 即可。
 */
object I18n {
    @Volatile
    var lang: Lang = Lang.ZH
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isEn: Boolean get() = lang == Lang.EN

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun setLang(next: Lang) {
        if (next == lang) return
        lang = next
        listeners.forEach { runCatching { it() } }
    }

    fun toggle() = setLang(if (lang == Lang.EN) Lang.ZH else Lang.EN)

    /** 中文原文 → 当前语言。查不到就原样返回。 */
    fun t(zh: String): String = translate(zh, lang)

    fun translate(text: String, target: Lang): String =
        if (target == Lang.EN) EN[text] ?: text else text

    // ---------------- 需要拼参数的句子 ----------------

    /** 引擎状态：轮到某方出手。`side` 已经过翻译。 */
    fun turnOf(side: String): String =
        if (isEn) "$side to move" else "轮到${side}出手"

    fun turnIsNow(side: String): String =
        if (isEn) "It is $side's turn" else "现在轮到${side}"

    fun sideWins(side: String): String =
        if (isEn) "$side wins" else "${side}获胜"

    /** 计分板/信息条上的"还剩几子"。 */
    fun aliveLeft(n: Int): String = if (isEn) "$n left" else "$n 子"

    fun scoreLine(a: Int, b: Int): String = if (isEn) "Score  $a : $b" else "比分  $a : $b"

    /** 暂停弹窗里的三行统计。 */
    fun pauseInfo(score1: Int, score2: Int, winCondition: String, out: Int): String =
        if (isEn) {
            "Score  $score1 : $score2\n" +
                "Win condition  $winCondition\n" +
                "Pieces out  $out"
        } else {
            "本局比分  $score1 : $score2\n" +
                "胜利条件  $winCondition\n" +
                "已出局  $out 子"
        }

    fun peerGone(reason: String): String =
        if (isEn) "$reason\nThis match is over." else "$reason\n这局到此为止。"

    fun hostFailed(detail: String): String =
        if (isEn) "Could not host: $detail" else "开房间失败：$detail"

    fun joinFailed(ip: String, detail: String): String =
        if (isEn) "Cannot reach $ip: $detail" else "连不上 $ip：$detail"

    fun nickLabel(len: Int, max: Int): String =
        if (isEn) "Your name  $len/$max" else "你的昵称 ${len}/${max}"

    fun localIpText(ip: String?): String =
        if (isEn) "This device  ${ip ?: "no network"}" else "本机地址  ${ip ?: "没连上 Wi-Fi"}"

    /** 自动发现到的房间条目：`房主名 · 房间号`。 */
    fun roomTitle(name: String, code: String): String =
        if (isEn) "$name · $code" else "$name · $code"

    fun roomDetail(killKing: Boolean, ip: String): String {
        val cond = if (killKing) t("擒王") else t("全歼")
        return if (isEn) "$cond · $ip" else "$cond · $ip"
    }

    fun searchingHint(): String =
        if (isEn) "Searching for rooms on your network…" else "正在搜索同一网络里的房间…"

    fun noRoomHint(): String =
        if (isEn) {
            "No rooms found. Ask the other side to tap “Create room”, and make sure both devices are on the same network."
        } else {
            "没找到房间：让对面点「创建房间」，并确认两台设备在同一个 Wi-Fi。"
        }

    fun discoveredCount(n: Int): String =
        if (isEn) "$n room(s) found" else "找到 $n 个房间"

    // ---------------- 词典 ----------------

    /**
     * 中文原文是 key。
     *
     * 注意 `"Physics Chess"` 这一条：它本身就是英文，作为首页副标题在中文界面下
     * 不该再变，只在英文界面里换成一句说明，否则会和主标题重复。
     */
    private val EN: Map<String, String> = mapOf(
        // 阵营 / 棋子 / 规则
        "下方" to "Bottom",
        "上方" to "Top",
        "兵" to "Pawn",
        "车" to "Rook",
        "马" to "Knight",
        "象" to "Bishop",
        "后" to "Queen",
        "王" to "King",
        "擒王" to "Kill king",
        "全歼" to "Annihilate",
        "人机对战" to "vs AI",
        "双人对战" to "Two players",
        "局域网对战" to "LAN game",
        "轻松" to "Easy",
        "标准" to "Normal",
        "凶狠" to "Fierce",

        // 引擎状态
        "AI 正在瞄准…" to "AI is aiming…",
        "对手正在瞄准…" to "Opponent is aiming…",
        "平局" to "Draw",
        "本局结束" to "Game over",

        // 联机
        "房主" to "Host",
        "客人" to "Guest",
        "你" to "You",
        "对手" to "Opponent",
        "已断开" to "Disconnected",
        "连接已断开" to "Connection lost",
        "对手断开了连接" to "Opponent disconnected",
        "对手退出了对局" to "Opponent left the match",
        "写入失败" to "Write failed",
        "读取失败" to "Read failed",

        // 对局界面
        "对手认输了" to "Opponent resigned",
        "你认输了" to "You resigned",
        "本局已经结束" to "This game is over",
        "等棋子停稳再出手" to "Wait until the pieces settle",
        "按住自己的棋子拖动" to "Drag one of your own pieces",
        "你赢了" to "You win",
        "对手获胜" to "Opponent wins",
        "同步中…" to "Syncing…",
        "等对手出手…" to "Waiting for opponent…",
        "轮到你出手" to "Your move",
        "暂停" to "Paused",
        "认输" to "Resign",
        "继续" to "Resume",
        "退出对局" to "Quit game",
        "对手已离开" to "Opponent left",
        "返回菜单" to "Back to menu",
        "拖动己方棋子瞄准，松手甩出 · 拖横条可左右挪棋盘" to
            "Drag your piece to aim, release to shoot · drag the bar to pan the board",
        "出手" to "Move",
        "已碎" to "Out",
        "同归于尽" to "Mutual destruction",
        "AI 获胜" to "AI wins",
        "下方获胜" to "Bottom wins",
        "上方获胜" to "Top wins",
        "离开" to "left",
        "擒王模式：王被撞出棋盘即落败" to "Kill king: shove the enemy king off the board to win",
        "全歼模式：吃光对方所有棋子" to "Annihilate: knock every enemy piece out",
        "再来一局" to "Play again",

        // 首页
        "弹棋" to "Physics Chess",
        "Physics Chess" to "Kill the king, keep your own",
        "按住自己的棋子朝目标方向拖，松手把它甩出去：撞开挡路的子，把对方的王挤出棋盘。\n棋子互相碰撞不会碎，整个滑出棋盘才算出局。" to
            "Drag one of your pieces toward the target and let go: knock the blockers away and shove the enemy king off the board.\nPieces never break on impact — only sliding off the board takes one out.",
        "AI 会挑最强的子去撞你的王" to "The AI picks its strongest piece and charges your king",
        "同一台手机轮流甩子" to "Take turns on one device",
        "同一 Wi-Fi，两台手机各执一方" to "Two devices on the same network, one side each",
        "AI 强度" to "AI strength",
        "胜利条件" to "Win condition",
        "棋子颜色 · 决定先后手" to "Piece color · decides who moves first",
        "我方先出" to "I move first",
        "执此色" to "plays this color",
        "对方先出" to "Opponent first",
        "自动开局 · 房主执下方，客人执上方" to "Auto start · host is bottom, guest is top",
        "同一台电脑轮流甩子" to "Take turns on one device",
        "同一网络，两台设备各执一方" to "Two devices on the same network, one side each",
        "配色固定 · 与手机版同一套规则与物理" to
            "Fixed palette · same rules and physics as the phone version",
        "分值" to "Score",
        "点「创建房间」后，同一网络里的设备会自动看到它；找不到就展开「以 IP 直连」。" to
            "Tap “Create room”; other devices on this network will see it automatically. If not, open “Connect by IP”.",
        "房主点「创建房间」，对面在下面的列表里点一下就能加入；找不到再展开「以 IP 直连」。" to
            "Tap “Create room” here; the other device just taps your room in the list below. If it is missing, open “Connect by IP”.",
        "开始对局" to "Start game",
        "配色随系统壁纸变化 · Material 3 Expressive" to
            "Colors follow your wallpaper · Material 3 Expressive",
        "同一 Wi-Fi 下联机" to "LAN game on the same network",
        "房主点「创建房间」并把这串地址念给对面，对面填地址点「加入房间」。" to
            "Tap “Create room” on one device; the other device finds it automatically below.",
        "留空就是「房主 / 客人」" to "Leave empty for Host / Guest",
        "等待对手加入…" to "Waiting for a player…",
        "创建房间" to "Create room",
        "取消等待" to "Cancel wait",
        "房主地址，例如 192.168.1.12" to "Host address, e.g. 192.168.1.12",
        "正在连接…" to "Connecting…",
        "加入房间" to "Join",
        "取消连接" to "Cancel",

        // 自动发现（Android / 桌面共用）
        "自动发现" to "Auto-discover",
        "刷新" to "Refresh",
        "以 IP 直连" to "Connect by IP",
        "隐藏 IP 输入" to "Hide IP input",
        "正在搜索…" to "Searching…",
        "点一下直接加入" to "Tap to join",
        "没有发现在线房间" to "No rooms found",
        "两台设备都要在同一个局域网，且允许访问网络。" to
            "Both devices must be on the same network and allowed to use it.",
        "语言" to "Language",
    )
}
