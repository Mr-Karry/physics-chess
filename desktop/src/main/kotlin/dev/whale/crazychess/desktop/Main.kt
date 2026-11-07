package dev.whale.crazychess.desktop

import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameEngine
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.Rules
import dev.whale.crazychess.game.Side
import dev.whale.crazychess.game.WinCondition
import dev.whale.crazychess.i18n.I18n
import dev.whale.crazychess.i18n.Lang
import dev.whale.crazychess.net.LanAdvertiser
import dev.whale.crazychess.net.LanEvent
import dev.whale.crazychess.net.LanFinder
import dev.whale.crazychess.net.LanMeta
import dev.whale.crazychess.net.LanRoom
import dev.whale.crazychess.net.LanSession
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Windows 桌面版入口。
 *
 * 和 Android 版共用 `game/GameEngine.kt`、`net/LanSession.kt`、`net/LanDiscovery.kt`、
 * `i18n/I18n.kt` 四个文件 —— 玩法和联机协议是同一份实现，所以两边可以互相对战。
 * 界面没走 Compose（这台机器离线，Compose Desktop 拉不到依赖），用 JDK 自带的 Swing 画。
 */
fun main() {
    I18n.setLang(DesktopPrefs.loadLang() ?: Lang.fromLocale(Locale.getDefault().toLanguageTag()))
    SwingUtilities.invokeLater {
        MainFrame().isVisible = true
    }
}

/** 桌面版的设置文件，只存语言。 */
object DesktopPrefs {
    private val file: File
        get() = File(System.getProperty("user.home"), ".physics-chess.prefs")

    fun loadLang(): Lang? = runCatching {
        if (!file.exists()) return null
        file.readLines().firstOrNull { it.startsWith("lang=") }
            ?.substringAfter('=')
            ?.let { Lang.fromCode(it) }
    }.getOrNull()

    fun saveLang(lang: Lang) {
        runCatching { file.writeText("lang=${lang.code}\n") }
    }
}

/** 一局游戏需要的全部上下文。 */
private data class Match(
    val mode: GameMode,
    val difficulty: Difficulty,
    val win: WinCondition,
    val seed: Int,
    val bottomMovesFirst: Boolean,
    val lan: LanSession?,
    val mySide: Side,
    val myName: String,
    val peerName: String,
)

class MainFrame : JFrame("弹棋 · Physics Chess") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cards = CardLayout()
    private val root = JPanel(cards)

    // 首页状态（切语言会重建首页，所以状态放在 frame 上）
    private var mode = GameMode.VS_ANDROID
    private var difficulty = Difficulty.NORMAL
    private var win = WinCondition.KILL_KING
    private var bottomFirst = true
    private var nick = ""

    // 自动发现
    private val finder = LanFinder()
    private val roomsModel = DefaultListModel<LanRoom>()
    private var advertiser: LanAdvertiser? = null
    private var hostJob: Job? = null
    private var lanBusy = false
    private var lanStatus: String = ""
    private var lanStatusError = false

    private var match: Match? = null
    private var onExit: (() -> Unit)? = null

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(760, 560)
        // Windows 150% 缩放下，Java 的「逻辑坐标」和屏幕物理像素是 1.5 倍关系：
        // 窗口会按 1.5 倍画出去，如果直接 setSize(1000) 就会跑到屏幕右侧外面。
        // 所以先按 defaultTransform 的缩放比把可用区域换算成逻辑尺寸，再定窗口大小和位置。
        val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration
        val scale = gc.defaultTransform.scaleX.toDouble().coerceAtLeast(1.0)
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val usableW = (bounds.width / scale).toInt()
        val usableH = (bounds.height / scale).toInt()
        setSize(minOf(980, usableW - 24), minOf(720, usableH - 24))
        setLocation(
            bounds.x + (usableW - width) / 2,
            bounds.y + (usableH - height) / 2,
        )
        contentPane = root
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                match?.lan?.close()
                finder.stop()
                advertiser?.stop()
                scope.cancel()
            }
        })
        root.add(buildHome(), "home")
        cards.show(root, "home")
        startFinderCollect()
    }

    // ================================================================ 首页

    private fun rebuildHome() {
        root.removeAll()
        root.add(buildHome(), "home")
        root.revalidate()
        root.repaint()
        cards.show(root, "home")
    }

    private fun startFinderCollect() {
        scope.launch {
            finder.rooms.collect { rooms ->
                SwingUtilities.invokeLater {
                    val changed = rooms.size != roomsModel.size ||
                        rooms.indices.any { roomsModel.get(it).id != rooms[it].id }
                    roomsModel.clear()
                    rooms.forEach { roomsModel.addElement(it) }
                    // 列表在「空态提示 / 房间列表」之间切换是靠重建面板实现的，
                    // 所以房间集合变化时重排一次首页（同集合的重复推送不会触发）。
                    if (changed && mode == GameMode.LAN && match == null) rebuildHome()
                }
            }
        }
    }

    private fun buildHome(): JPanel {
        val page = JPanel(BorderLayout())
        page.background = Palette.background

        // 一行一个控件：GridBagLayout 负责「占满列宽 + 左对齐」，列宽钉死 720，
        // 外面再套一层 GridBagLayout 把它居中（宽屏下不会被拉成一条）。
        val rows = JPanel(GridBagLayout())
        rows.background = Palette.background
        rows.border = EmptyBorder(24, 0, 24, 0)
        var rowIndex = 0
        fun addRow(comp: Component, gap: Int = 0) {
            val c = GridBagConstraints()
            c.gridx = 0
            c.gridy = rowIndex++
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            c.anchor = GridBagConstraints.WEST
            c.insets = Insets(gap, 0, 0, 0)
            rows.add(comp, c)
        }

        // 标题 + 语言开关
        val head = JPanel(BorderLayout())
        head.isOpaque = false
        val titleCol = JPanel()
        titleCol.isOpaque = false
        titleCol.layout = BoxLayout(titleCol, BoxLayout.Y_AXIS)
        val title = JLabel(I18n.t("弹棋"))
        title.font = Font(Font.SANS_SERIF, Font.BOLD, 46)
        title.foreground = Palette.onSurface
        val subtitle = JLabel(I18n.t("Physics Chess"))
        subtitle.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        subtitle.foreground = Palette.onSurfaceVariant
        titleCol.add(title)
        titleCol.add(subtitle)
        head.add(titleCol, BorderLayout.WEST)

        val langButton = pillButton(if (I18n.lang == Lang.EN) "EN" else "中") {
            I18n.toggle()
            DesktopPrefs.saveLang(I18n.lang)
            rebuildHome()
        }
        val langWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        langWrap.isOpaque = false
        langWrap.add(langButton)
        head.add(langWrap, BorderLayout.EAST)
        addRow(head)

        // 模式卡片
        addRow(modeCard(GameMode.VS_ANDROID, "人机对战", "AI 会挑最强的子去撞你的王", "♚", Palette.faceA) {
            selectMode(GameMode.VS_ANDROID)
        }, 18)
        addRow(modeCard(GameMode.VS_HUMAN, "双人对战", "同一台电脑轮流甩子", "♞", Palette.faceB) {
            selectMode(GameMode.VS_HUMAN)
        }, 10)
        addRow(modeCard(GameMode.LAN, "局域网对战", "同一网络，两台设备各执一方", "♜", Palette.faceA) {
            selectMode(GameMode.LAN)
        }, 10)

        if (mode == GameMode.VS_ANDROID) {
            addRow(sectionLabel("AI 强度"), 20)
            addRow(
                segmented(Difficulty.entries.map { it.label to it }, difficulty) {
                    difficulty = it
                    rebuildHome()
                },
                8,
            )
        }

        if (mode == GameMode.LAN) {
            addRow(buildLanPanel(), 20)
        }

        addRow(sectionLabel("胜利条件"), 20)
        addRow(segmented(WinCondition.entries.map { it.label to it }, win) {
            win = it
            rebuildHome()
        }, 8)

        addRow(sectionLabel("棋子颜色 · 决定先后手"), 20)
        val colorsRow = JPanel(GridLayout(1, 2, 12, 0))
        colorsRow.isOpaque = false
        colorsRow.add(
            colorCard("我方先出", "执此色", Palette.paintA, bottomFirst) {
                bottomFirst = true
                rebuildHome()
            },
        )
        colorsRow.add(
            colorCard("对方先出", "执此色", Palette.paintB, !bottomFirst) {
                bottomFirst = false
                rebuildHome()
            },
        )
        addRow(colorsRow, 8)

        // 列宽定死，再居中
        rows.preferredSize = Dimension(720, rows.preferredSize.height)
        val holder = JPanel(GridBagLayout())
        holder.background = Palette.background
        holder.add(rows)

        val scroll = JScrollPane(holder)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = Palette.background
        scroll.verticalScrollBar.unitIncrement = 24
        // 窗口显示后布局/焦点还可能变化一次，把视图滚回顶部（否则一打开就停在中间）
        SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }
        Timer(250) { scroll.verticalScrollBar.value = 0 }.apply { isRepeats = false; start() }
        page.add(scroll, BorderLayout.CENTER)

        // 底部固定条
        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.background = Palette.background
        footer.border = EmptyBorder(10, 24, 18, 24)
        if (mode == GameMode.LAN) {
            val tip = JLabel(I18n.t("自动开局 · 房主执下方，客人执上方"))
            tip.alignmentX = Component.CENTER_ALIGNMENT
            tip.foreground = Palette.onSurfaceVariant
            footer.add(tip)
        } else {
            val start = bigButton(I18n.t("开始对局")) {
                startMatch(
                    Match(
                        mode = mode,
                        difficulty = difficulty,
                        win = win,
                        seed = (Math.random() * Int.MAX_VALUE).toInt(),
                        bottomMovesFirst = bottomFirst,
                        lan = null,
                        mySide = Side.BOTTOM,
                        myName = "你",
                        peerName = if (mode == GameMode.VS_ANDROID) "AI" else "对手",
                    ),
                )
            }
            start.alignmentX = Component.CENTER_ALIGNMENT
            footer.add(start)
        }
        val foot = JLabel(I18n.t("配色固定 · 与手机版同一套规则与物理"))
        foot.alignmentX = Component.CENTER_ALIGNMENT
        foot.font = foot.font.deriveFont(11f)
        foot.foreground = Palette.onSurfaceVariant
        footer.add(Box.createVerticalStrut(8))
        footer.add(foot)
        page.add(footer, BorderLayout.SOUTH)
        return page
    }

    private fun selectMode(next: GameMode) {
        mode = next
        if (mode == GameMode.LAN) finder.start(scope) else finder.stop()
        rebuildHome()
    }

    private fun buildLanPanel(): JPanel {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.background = Palette.surfaceHigh
        card.border = EmptyBorder(14, 16, 14, 16)

        val title = JLabel(I18n.t("同一 Wi-Fi 下联机"))
        title.font = title.font.deriveFont(Font.BOLD, 17f)
        title.alignmentX = Component.LEFT_ALIGNMENT
        card.add(title)
        val hint = JLabel(
            "<html>" + I18n.t("点「创建房间」后，同一网络里的设备会自动看到它；找不到就展开「以 IP 直连」。") + "</html>",
        )
        hint.font = hint.font.deriveFont(12f)
        hint.foreground = Palette.onSurfaceVariant
        hint.alignmentX = Component.LEFT_ALIGNMENT
        card.add(Box.createVerticalStrut(4))
        card.add(hint)

        val ip = LanSession.localIpv4()
        val ipLabel = JLabel(I18n.localIpText(ip))
        ipLabel.font = ipLabel.font.deriveFont(Font.BOLD, 14f)
        ipLabel.foreground = Palette.faceA
        ipLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(Box.createVerticalStrut(8))
        card.add(ipLabel)

        // 昵称
        val nickRow = JPanel(BorderLayout(8, 0))
        nickRow.isOpaque = false
        nickRow.alignmentX = Component.LEFT_ALIGNMENT
        val nickField = JTextField(nick)
        nickField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.outlineVariant),
            EmptyBorder(6, 8, 6, 8),
        )
        nickField.addActionListener { nick = nickField.text.take(10) }
        nickField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = run { nick = nickField.text.take(10) }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = run { nick = nickField.text.take(10) }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = Unit
        })
        nickRow.add(JLabel(I18n.nickLabel(nick.length, 10)).apply {
            font = font.deriveFont(12f)
            foreground = Palette.onSurfaceVariant
            preferredSize = Dimension(130, 28)
        }, BorderLayout.WEST)
        nickRow.add(nickField, BorderLayout.CENTER)
        card.add(Box.createVerticalStrut(8))
        card.add(nickRow)

        // 建房
        val hostRow = JPanel(BorderLayout(8, 0))
        hostRow.isOpaque = false
        hostRow.alignmentX = Component.LEFT_ALIGNMENT
        val create = pillButton(if (lanBusy) I18n.t("等待对手加入…") else I18n.t("创建房间")) { startHosting() }
        create.isEnabled = !lanBusy
        hostRow.add(create)
        if (lanBusy) {
            val cancel = pillButton(I18n.t("取消等待")) { cancelHosting() }
            hostRow.add(cancel, BorderLayout.EAST)
        }
        card.add(Box.createVerticalStrut(10))
        card.add(hostRow)

        // 自动发现
        card.add(Box.createVerticalStrut(14))
        val sectionRow = JPanel(BorderLayout())
        sectionRow.isOpaque = false
        sectionRow.alignmentX = Component.LEFT_ALIGNMENT
        val auto = JLabel(I18n.t("自动发现"))
        auto.font = auto.font.deriveFont(Font.BOLD, 13f)
        auto.foreground = Palette.onSurfaceVariant
        sectionRow.add(auto, BorderLayout.WEST)
        val refresh = pillButton(I18n.t("刷新")) { finder.poke() }
        sectionRow.add(refresh, BorderLayout.EAST)
        sectionRow.maximumSize = Dimension(Int.MAX_VALUE, 40)
        card.add(sectionRow)

        if (roomsModel.isEmpty) {
            card.add(Box.createVerticalStrut(6))
            val none = JLabel(
                "<html>" + I18n.t("没有发现在线房间") + "<br/>" +
                    I18n.t("两台设备都要在同一个局域网，且允许访问网络。") + "</html>",
            )
            none.font = none.font.deriveFont(12f)
            none.foreground = Palette.onSurfaceVariant
            none.alignmentX = Component.LEFT_ALIGNMENT
            card.add(none)
        } else {
            val list = JList(roomsModel)
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.cellRenderer = RoomRenderer()
            list.background = Palette.surfaceHigh
            list.fixedCellHeight = 46
            list.addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    val room = list.selectedValue ?: return@addListSelectionListener
                    joinRoom(room)
                }
            }
            val scroll = JScrollPane(list)
            scroll.alignmentX = Component.LEFT_ALIGNMENT
            scroll.preferredSize = Dimension(100, 92)
            scroll.maximumSize = Dimension(Int.MAX_VALUE, 110)
            card.add(Box.createVerticalStrut(6))
            card.add(scroll)
        }

        // IP 兜底
        card.add(Box.createVerticalStrut(10))
        val ipRow = JPanel(BorderLayout(8, 0))
        ipRow.isOpaque = false
        ipRow.alignmentX = Component.LEFT_ALIGNMENT
        val ipField = JTextField()
        ipField.border = nickField.border
        val joinBtn = pillButton(I18n.t("加入房间")) { joinByIp(ipField.text.trim()) }
        ipRow.add(JLabel(I18n.t("房主地址，例如 192.168.1.12")).apply {
            font = font.deriveFont(12f)
            foreground = Palette.onSurfaceVariant
            preferredSize = Dimension(190, 28)
        }, BorderLayout.WEST)
        ipRow.add(ipField, BorderLayout.CENTER)
        ipRow.add(joinBtn, BorderLayout.EAST)
        card.add(ipRow)

        if (lanStatus.isNotEmpty()) {
            card.add(Box.createVerticalStrut(8))
            val status = JLabel("<html>" + lanStatus + "</html>")
            status.font = status.font.deriveFont(12f)
            status.foreground = if (lanStatusError) Palette.error else Palette.faceA
            status.alignmentX = Component.LEFT_ALIGNMENT
            card.add(status)
        }
        card.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        return card
    }

    // ---------------------------------------------------------------- 建房 / 加入

    private fun startHosting() {
        if (lanBusy) return
        val meta = LanMeta(
            killKing = win == WinCondition.KILL_KING,
            bottomFirst = bottomFirst,
            hostName = nick.trim().ifBlank { I18n.t("房主") },
        )
        lanBusy = true
        lanStatus = I18n.searchingHint()
        lanStatusError = false
        val adv = LanAdvertiser(meta, LanSession.PORT)
        advertiser = adv
        adv.start(scope)
        rebuildHome()
        hostJob = scope.launch {
            try {
                val session = LanSession.hostAndWait(meta)
                SwingUtilities.invokeLater {
                    adv.stop()
                    advertiser = null
                    lanBusy = false
                    lanStatus = ""
                    startMatch(
                        Match(
                            mode = GameMode.LAN,
                            difficulty = Difficulty.NORMAL,
                            win = if (meta.killKing) WinCondition.KILL_KING else WinCondition.KILL_EM_ALL,
                            seed = (Math.random() * Int.MAX_VALUE).toInt(),
                            bottomMovesFirst = meta.bottomFirst,
                            lan = session,
                            mySide = Side.BOTTOM,
                            myName = nick.trim().ifBlank { I18n.t("房主") },
                            peerName = "",
                        ),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    adv.stop()
                    advertiser = null
                    lanBusy = false
                    lanStatus = I18n.hostFailed(e.message ?: "未知错误")
                    lanStatusError = true
                    rebuildHome()
                }
            }
        }
    }

    private fun cancelHosting() {
        hostJob?.cancel()
        hostJob = null
        advertiser?.stop()
        advertiser = null
        lanBusy = false
        lanStatus = ""
        rebuildHome()
    }

    private fun joinRoom(room: LanRoom) {
        if (lanBusy) return
        connect(room.host, room.port)
    }

    private fun joinByIp(ip: String) {
        if (lanBusy || ip.isBlank()) return
        connect(ip, LanSession.PORT)
    }

    private fun connect(host: String, port: Int) {
        lanBusy = true
        lanStatus = I18n.t("正在连接…")
        lanStatusError = false
        rebuildHome()
        scope.launch {
            try {
                val (session, meta) = LanSession.join(
                    host,
                    port,
                    myName = nick.trim().ifBlank { I18n.t("客人") },
                )
                SwingUtilities.invokeLater {
                    lanBusy = false
                    lanStatus = ""
                    startMatch(
                        Match(
                            mode = GameMode.LAN,
                            difficulty = Difficulty.NORMAL,
                            win = if (meta.killKing) WinCondition.KILL_KING else WinCondition.KILL_EM_ALL,
                            seed = (Math.random() * Int.MAX_VALUE).toInt(),
                            bottomMovesFirst = meta.bottomFirst,
                            lan = session,
                            mySide = Side.TOP,
                            myName = nick.trim().ifBlank { I18n.t("客人") },
                            peerName = meta.hostName,
                        ),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lanBusy = false
                    lanStatus = I18n.joinFailed(host, e.message ?: "确认两台设备在同一个网络、且房主已开房间")
                    lanStatusError = true
                    rebuildHome()
                }
            }
        }
    }

    // ================================================================ 对局

    private fun startMatch(m: Match) {
        myShotSeq = 0
        sentSeq = 0
        hintMessage = null
        peerGone = null
        match?.lan?.close()
        match = m
        root.removeAll()
        root.add(buildGame(m), "game")
        root.revalidate()
        root.repaint()
        cards.show(root, "game")
    }

    private fun exitMatch() {
        onExit?.invoke()
        onExit = null
        match?.lan?.close()
        match = null
        finder.stop()
        if (mode == GameMode.LAN) finder.start(scope)
        rebuildHome()
    }

    private fun buildGame(m: Match): JPanel {
        val engine = GameEngine(m.mode, m.win, m.difficulty, m.seed, m.bottomMovesFirst)
        val boardColorsSwapped = !m.bottomMovesFirst
        val myBottom = if (m.lan != null) m.mySide else Side.BOTTOM
        val controlSide = when (m.mode) {
            GameMode.VS_ANDROID -> Side.BOTTOM
            GameMode.VS_HUMAN -> engine.activeSide
            GameMode.LAN -> m.mySide
        }
        val board = BoardPanel(
            engine = engine,
            controlSide = controlSide,
            flipped = m.lan != null && m.mySide == Side.TOP,
            onShot = { piece, dx, dy ->
                if (m.lan != null) {
                    m.lan.sendShot(piece.id, dx, dy)
                    myShotSeq++
                }
            },
            onInvalid = { hintMessage = invalidHint(engine, m); hintUntil = System.currentTimeMillis() + 2200 },
        )

        val page = JPanel(BorderLayout())
        page.background = Palette.background

        // 顶部：两边的计分卡 + 暂停
        val header = JPanel(BorderLayout(12, 0))
        header.background = Palette.background
        header.border = EmptyBorder(14, 20, 6, 20)

        val bottomName = if (m.lan != null) {
            if (m.mySide == Side.BOTTOM) m.myName else m.peerName.ifBlank { "对手" }
        } else if (m.mode == GameMode.VS_ANDROID) m.myName else Side.BOTTOM.label
        val topName = if (m.lan != null) {
            if (m.mySide == Side.TOP) m.myName else m.peerName.ifBlank { "对手" }
        } else if (m.mode == GameMode.VS_ANDROID) "AI" else Side.TOP.label

        val bottomCard = ScoreCard(bottomName, engine, Side.BOTTOM, swapped = boardColorsSwapped, accentTop = false)
        val topCard = ScoreCard(topName, engine, Side.TOP, swapped = boardColorsSwapped, accentTop = true)
        val cardsRow = JPanel(GridLayout(1, 2, 12, 0))
        cardsRow.isOpaque = false
        cardsRow.add(bottomCard)
        cardsRow.add(topCard)
        header.add(cardsRow, BorderLayout.CENTER)

        val pause = pillButton(I18n.t("暂停")) { showPause(engine, m, board) }
        header.add(pause, BorderLayout.EAST)
        page.add(header, BorderLayout.NORTH)

        val boardWrap = JPanel(BorderLayout())
        boardWrap.background = Palette.background
        boardWrap.border = EmptyBorder(6, 16, 6, 16)
        boardWrap.add(board, BorderLayout.CENTER)
        page.add(boardWrap, BorderLayout.CENTER)

        val hint = JLabel(" ")
        hint.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        hint.foreground = Palette.onSurfaceVariant
        hint.horizontalAlignment = JLabel.CENTER
        hint.border = EmptyBorder(4, 16, 16, 16)
        page.add(hint, BorderLayout.SOUTH)

        // ---- 主循环
        var lastNanos = System.nanoTime()
        var aiThink = 0f
        var shownResult = false
        var awaitingSync = false
        var syncDeadline = 0L
        lateinit var tick: Timer
        tick = Timer(16) {
            val nowNanos = System.nanoTime()
            val dt = ((nowNanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            lastNanos = nowNanos
            val nowMs = System.currentTimeMillis()
            engine.tick(dt, nowMs)

            // AI
            if (m.mode == GameMode.VS_ANDROID && !engine.isOver && engine.activeSide == Side.TOP) {
                aiThink += dt
                if (aiThink >= Rules.AI_THINK_TIME && engine.isSettled()) {
                    aiThink = 0f
                    engine.aiShoot()
                }
            } else {
                aiThink = 0f
            }

            // 联机：本地停稳后补一条权威局面
            if (m.lan != null && myShotSeq > sentSeq && engine.isSettled()) {
                m.lan.sendState(engine.exportState())
                sentSeq = myShotSeq
            }

            if (awaitingSync && nowMs > syncDeadline) awaitingSync = false

            if (!engine.isOver) {
                hint.text = hintMessage ?: turnText(engine, m, awaitingSync)
                hint.foreground = if (hintMessage != null) Palette.faceB else Palette.onSurfaceVariant
                if (hintMessage != null && nowMs > hintUntil) hintMessage = null
            } else {
                hint.text = " "
            }
            if (m.mode == GameMode.VS_HUMAN) board.controlSide = engine.activeSide
            bottomCard.refresh()
            topCard.refresh()
            board.repaint()

            if (engine.isOver && !shownResult) {
                shownResult = true
                tick.stop()
                SwingUtilities.invokeLater { showResult(engine, m) }
            }
        }

        // ---- 联机事件
        val lan = m.lan
        if (lan != null) {
            onExit = { }
            scope.launch {
                lan.events.collect { ev ->
                    SwingUtilities.invokeLater {
                        when (ev) {
                            is LanEvent.PeerJoined -> {
                                peerNameVar = ev.name
                            }

                            is LanEvent.RemoteShot -> {
                                val piece = engine.pieces.firstOrNull { it.id == ev.pieceId }
                                if (piece != null) {
                                    // 本地先推演一遍，动画才不是瞬移；随后那条 state 会覆盖成正解
                                    engine.shoot(piece, ev.dx, ev.dy)
                                }
                                awaitingSync = true
                                syncDeadline = System.currentTimeMillis() + 6000
                            }

                            is LanEvent.RemoteState -> {
                                engine.applyState(ev.state)
                                awaitingSync = false
                            }

                            is LanEvent.RemoteResigned -> engine.forceEnd(m.mySide, "对手认输了")
                            is LanEvent.RemoteRematch -> {
                                engine.reset()
                                myShotSeq = 0
                                sentSeq = 0
                                awaitingSync = false
                                shownResult = false
                                tick.restart()
                            }

                            is LanEvent.PeerLeft -> {
                                peerGone = ev.reason
                                if (!engine.isOver) engine.forceEnd(m.mySide, ev.reason)
                            }
                        }
                    }
                }
            }
        }

        tick.start()
        return page
    }

    private var myShotSeq = 0
    private var sentSeq = 0
    private var hintMessage: String? = null
    private var hintUntil = 0L
    private var peerNameVar: String = ""
    private var peerGone: String? = null

    private fun turnText(engine: GameEngine, m: Match, awaitingSync: Boolean): String = when {
        m.lan != null && awaitingSync -> I18n.t("同步中…")
        m.lan != null && engine.activeSide != m.mySide -> I18n.t("等对手出手…")
        m.lan != null -> I18n.t("轮到你出手")
        m.mode == GameMode.VS_ANDROID && engine.activeSide == Side.TOP -> I18n.t("对手正在瞄准…")
        m.mode == GameMode.VS_ANDROID -> I18n.t("轮到你出手")
        else -> I18n.turnOf(engine.activeSide.label)
    }

    private fun invalidHint(engine: GameEngine, m: Match): String = when {
        engine.isOver -> "本局已经结束"
        !engine.isSettled() -> "等棋子停稳再出手"
        m.lan != null && engine.activeSide != m.mySide -> I18n.t("等对手出手…")
        engine.activeSide != myControlSide(m) -> I18n.turnIsNow(engine.activeSide.label)
        else -> "按住自己的棋子拖动"
    }

    private fun myControlSide(m: Match): Side = when (m.mode) {
        GameMode.VS_ANDROID, GameMode.VS_HUMAN -> Side.BOTTOM
        GameMode.LAN -> m.mySide
    }

    // ---------------------------------------------------------------- 暂停 / 结算

    private fun showPause(engine: GameEngine, m: Match, board: BoardPanel) {
        val stats = I18n.pauseInfo(
            engine.scoreP1,
            engine.scoreP2,
            engine.winCondition.label,
            32 - engine.totalAlive(Side.BOTTOM) - engine.totalAlive(Side.TOP),
        ) + "\n" + I18n.scoreLine(engine.scoreP1, engine.scoreP2)

        val options = if (m.lan != null) {
            arrayOf(I18n.t("继续"), I18n.t("认输"), I18n.t("退出对局"))
        } else {
            arrayOf(I18n.t("继续"), I18n.t("退出对局"))
        }
        val choice = JOptionPane.showOptionDialog(
            this,
            stats,
            I18n.t("暂停"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options.first(),
        )
        when {
            choice == 1 && m.lan != null -> {
                m.lan.sendResign()
                engine.forceEnd(m.mySide.other, "你认输了")
                showResult(engine, m)
            }

            choice == options.lastIndex -> exitMatch()
            else -> board.requestFocusInWindow()
        }
    }

    private fun showResult(engine: GameEngine, m: Match) {
        val title = when {
            engine.isDraw -> I18n.t("同归于尽")
            m.lan != null -> if (engine.winner == m.mySide) I18n.t("你赢了") else I18n.t("对手获胜")
            engine.winner == Side.TOP && m.mode == GameMode.VS_ANDROID -> I18n.t("AI 获胜")
            engine.winner == Side.BOTTOM -> if (m.mode == GameMode.VS_ANDROID) I18n.t("你赢了") else I18n.t("下方获胜")
            else -> I18n.t("上方获胜")
        }
        val reason = engine.endNote ?: if (engine.winCondition == WinCondition.KILL_KING) {
            I18n.t("擒王模式：王被撞出棋盘即落败")
        } else {
            I18n.t("全歼模式：吃光对方所有棋子")
        }
        val msg = I18n.scoreLine(engine.scoreP1, engine.scoreP2) + "\n" + I18n.t(reason)

        val canReplay = !(m.lan != null && peerGone != null)
        val options = if (canReplay) {
            arrayOf(I18n.t("再来一局"), I18n.t("返回菜单"))
        } else {
            arrayOf(I18n.t("返回菜单"))
        }
        val choice = JOptionPane.showOptionDialog(
            this,
            msg,
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options.first(),
        )
        if (choice == 0 && canReplay) {
            if (m.lan != null) {
                m.lan.sendRematch()
                myShotSeq = 0
                sentSeq = 0
            }
            engine.reset()
            peerGone = null
            startMatch(m.copy(seed = (Math.random() * Int.MAX_VALUE).toInt()))
        } else {
            exitMatch()
        }
    }

    // ================================================================ 小部件

    private fun sectionLabel(text: String): JLabel {
        val label = JLabel(I18n.t(text))
        label.font = label.font.deriveFont(Font.BOLD, 13f)
        label.foreground = Palette.onSurfaceVariant
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun modeCard(
        target: GameMode,
        title: String,
        subtitle: String,
        glyph: String,
        accent: Color,
        onClick: () -> Unit,
    ): JPanel {
        val selected = mode == target
        val card = RoundPanel(if (selected) Palette.primaryContainer else Palette.surfaceHigh, 22)
        card.layout = BorderLayout(14, 0)
        card.border = EmptyBorder(16, 18, 16, 18)
        card.preferredSize = Dimension(480, 88)
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        val badge = GlyphBadge(glyph, accent)
        card.add(badge, BorderLayout.WEST)

        val col = JPanel()
        col.isOpaque = false
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
        val t = JLabel(I18n.t(title))
        t.font = t.font.deriveFont(Font.BOLD, 19f)
        t.foreground = if (selected) Palette.onPrimaryContainer else Palette.onSurface
        val s = JLabel(I18n.t(subtitle))
        s.font = s.font.deriveFont(12f)
        s.foreground = if (selected) Palette.onPrimaryContainer else Palette.onSurfaceVariant
        col.add(t)
        col.add(s)
        card.add(col, BorderLayout.CENTER)

        card.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
        })
        return card
    }

    private fun colorCard(
        title: String,
        subtitle: String,
        paint: PiecePaint,
        selected: Boolean,
        onClick: () -> Unit,
    ): JPanel {
        val card = RoundPanel(if (selected) Palette.secondaryContainer else Palette.surfaceHigh, 18)
        card.layout = BorderLayout(10, 0)
        card.border = EmptyBorder(10, 12, 10, 12)
        card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        card.add(ChipPaint(paint, selected), BorderLayout.WEST)
        val col = JPanel()
        col.isOpaque = false
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
        val t = JLabel(I18n.t(title))
        t.font = t.font.deriveFont(Font.BOLD, 14f)
        t.foreground = if (selected) Palette.onSecondaryContainer else Palette.onSurface
        val s = JLabel(I18n.t(subtitle))
        s.font = s.font.deriveFont(11f)
        s.foreground = if (selected) Palette.onSecondaryContainer else Palette.onSurfaceVariant
        col.add(t)
        col.add(s)
        card.add(col, BorderLayout.CENTER)
        card.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
        })
        return card
    }

    private fun <T> segmented(items: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit): JPanel {
        val row = JPanel(GridLayout(1, items.size, 0, 0))
        row.isOpaque = false
        row.preferredSize = Dimension(480, 42)
        row.alignmentX = Component.LEFT_ALIGNMENT
        items.forEach { (label, value) ->
            val on = value == selected
            val b = JButton(I18n.t(label))
            b.isFocusPainted = false
            b.isFocusable = false
            b.font = b.font.deriveFont(Font.BOLD, 13f)
            b.background = if (on) Palette.secondaryContainer else Palette.surface
            b.foreground = if (on) Palette.onSecondaryContainer else Palette.onSurface
            b.border = BorderFactory.createLineBorder(if (on) Palette.primary else Palette.outlineVariant)
            b.isOpaque = true
            b.addActionListener { onPick(value) }
            row.add(b)
        }
        return row
    }

    private fun pillButton(text: String, onClick: () -> Unit): JButton {
        val b = JButton(text)
        b.isFocusPainted = false
        b.font = b.font.deriveFont(Font.BOLD, 13f)
        b.background = Palette.surfaceHigh
        b.foreground = Palette.onSurface
        b.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.outlineVariant, 1, true),
            EmptyBorder(6, 14, 6, 14),
        )
        b.addActionListener { onClick() }
        return b
    }

    private fun bigButton(text: String, onClick: () -> Unit): JButton {
        val b = object : JButton(text) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (model.isPressed) Palette.mix(Palette.primary, Color.BLACK, 0.15f) else Palette.primary
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 34.0, 34.0))
                g2.color = Palette.onPrimary
                g2.font = font
                val fm = g2.fontMetrics
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
            }
        }
        b.isContentAreaFilled = false
        b.isBorderPainted = false
        b.isFocusPainted = false
        b.font = b.font.deriveFont(Font.BOLD, 20f)
        b.preferredSize = Dimension(320, 56)
        b.maximumSize = Dimension(320, 56)
        b.addActionListener { onClick() }
        return b
    }

    private companion object {
        // 语言切换后重建首页时，会重画这些颜色卡与选项，所以这里不做缓存。
    }
}

/** 圆角面板：Swing 默认 JPanel 是直角的，M3 风格需要自己画底。 */
private class RoundPanel(private val fill: Color, private val radius: Int) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fill(
            RoundRectangle2D.Double(
                0.0, 0.0, width.toDouble(), height.toDouble(),
                radius.toDouble(), radius.toDouble(),
            ),
        )
        super.paintComponent(g)
    }
}

/** 模式卡左边的棋子徽章。 */
private class GlyphBadge(private val glyph: String, private val accent: Color) : JPanel() {
    init {
        isOpaque = false
        preferredSize = Dimension(49, 49)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = accent
        g2.fillOval(0, 0, 48, 48)
        g2.color = Palette.mix(accent, Color.WHITE, 0.45f)
        g2.fillOval(6, 6, 36, 36)
        g2.font = Palette.glyphFont(24)
        val fm = g2.fontMetrics
        g2.color = Palette.mix(accent, Color.BLACK, 0.35f)
        g2.drawString(
            glyph,
            (48 - fm.stringWidth(glyph)) / 2,
            (48 + fm.ascent - fm.descent) / 2,
        )
    }
}

/** 颜色卡左边那枚棋子色块。 */
private class ChipPaint(private val paint: PiecePaint, private val checked: Boolean) : JPanel() {
    init {
        isOpaque = false
        preferredSize = Dimension(28, 28)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = paint.face
        g2.fillOval(1, 1, 26, 26)
        g2.color = paint.rim
        g2.stroke = BasicStroke(2f)
        g2.drawOval(1, 1, 26, 26)
        if (checked) {
            g2.font = g2.font.deriveFont(Font.BOLD, 13f)
            val fm = g2.fontMetrics
            g2.color = paint.ink
            g2.drawString("✓", (28 - fm.stringWidth("✓")) / 2, (28 + fm.ascent - fm.descent) / 2)
        }
    }
}

/** 顶部计分卡。名字、分数、剩几子，轮到谁就高亮谁。 */
private class ScoreCard(
    private val name: String,
    private val engine: GameEngine,
    private val side: Side,
    private val swapped: Boolean,
    private val accentTop: Boolean,
) : JPanel() {
    init {
        isOpaque = false
        preferredSize = Dimension(220, 74)
    }

    private fun paint(): PiecePaint = Palette.paint(if (swapped) side.other else side)

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val active = !engine.isOver && engine.activeSide == side
        g2.color = if (active) Palette.secondaryContainer else Palette.surfaceHigh
        g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 20.0, 20.0))

        val shown = I18n.t(name.ifBlank { "对手" })
        g2.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g2.color = if (active) Palette.onSecondaryContainer else Palette.onSurface
        g2.drawString(shown, 16, 30)

        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g2.color = if (active) Palette.onSecondaryContainer else Palette.onSurfaceVariant
        val score = if (side == Side.BOTTOM) engine.scoreP1 else engine.scoreP2
        val alive = engine.totalAlive(side)
        g2.drawString("${I18n.t("分值")} $score · ${I18n.aliveLeft(alive)}", 16, 52)

        // 左边的色点
        val paint = paint()
        g2.color = paint.face
        g2.fillOval(width - 44, 22, 26, 26)
        g2.color = paint.rim
        g2.stroke = BasicStroke(2f)
        g2.drawOval(width - 44, 22, 26, 26)
    }

    fun refresh() = repaint()
}

/** 房间列表的一行。 */
private class RoomRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val panel = JPanel(BorderLayout(10, 0))
        panel.isOpaque = false
        val room = value as? LanRoom
        if (room != null) {
            val dot = JPanel()
            dot.isOpaque = false
            dot.preferredSize = Dimension(12, 12)
            val title = JLabel(I18n.roomTitle(room.name, room.id))
            title.font = title.font.deriveFont(Font.BOLD, 13f)
            val detail = JLabel(I18n.roomDetail(room.killKing, room.host))
            detail.font = detail.font.deriveFont(11f)
            detail.foreground = Palette.onSurfaceVariant
            val col = JPanel()
            col.isOpaque = false
            col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
            col.add(title)
            col.add(detail)
            panel.background = Palette.surface
            panel.isOpaque = true
            panel.border = EmptyBorder(6, 10, 6, 10)
            panel.add(col, BorderLayout.CENTER)
            panel.add(JLabel("»"), BorderLayout.EAST)
            panel.preferredSize = Dimension(panel.preferredSize.width, 46)
        }
        return panel
    }
}
