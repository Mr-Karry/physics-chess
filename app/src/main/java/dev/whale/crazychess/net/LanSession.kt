package dev.whale.crazychess.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** 开局参数：房主定，客人照抄，保证两边规则一致。 */
data class LanMeta(
    val killKing: Boolean = true,
    /** 底方是否先手（= 底方执先手色）。 */
    val bottomFirst: Boolean = true,
    val hostName: String = "房主",
)

/** 局域网对局里会发生的几件事。 */
sealed interface LanEvent {
    /** 对手连上来了（只在房主侧收到客户端第一条 hello 时触发）。 */
    data class PeerJoined(val name: String) : LanEvent

    /** 对手甩了一子。 */
    data class RemoteShot(val pieceId: Int, val dx: Float, val dy: Float) : LanEvent

    /** 对手发来的权威局面，用来对齐。 */
    data class RemoteState(val state: String) : LanEvent

    /** 对手认输了。 */
    data object RemoteResigned : LanEvent

    /** 对手想再来一局。 */
    data object RemoteRematch : LanEvent

    /** 对手掉线 / 主动退出。 */
    data class PeerLeft(val reason: String) : LanEvent
}

/**
 * 一条局域网连接。协议是「一行一条 JSON」：
 *
 * ```
 * {"t":"hello","v":1,"name":"Pixel 7","kk":1,"bf":1}
 * {"t":"shot","id":12,"dx":1.4,"dy":-3.2}
 * {"t":"state","s":"1|0|0|b|0|-|..."}
 * {"t":"resign"}
 * {"t":"rematch"}
 * ```
 *
 * 不做帧同步：**谁出手谁权威** —— 出手方在棋子停稳后发一条 [LanEvent.RemoteState]，
 * 对端直接用它覆盖自己的局面，误差不会累积。
 *
 * 这个类只依赖 `java.net` + kotlinx-coroutines，不碰 Android API，
 * 所以 Android 和 Windows 桌面版编的是同一份实现，跨端可以直接联机。
 */
class LanSession private constructor(
    private val socket: Socket,
    val isHost: Boolean,
    existingReader: BufferedReader? = null,
) {
    private val _events = Channel<LanEvent>(Channel.BUFFERED)
    val events: Flow<LanEvent> = _events.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader: BufferedReader =
        existingReader ?: BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    @Volatile
    private var closed = false

    init {
        scope.launch { readLoop() }
    }

    fun send(name: String) {
        if (closed) return
        scope.launch { write(name) }
    }

    fun sendShot(pieceId: Int, dx: Float, dy: Float) {
        send(Json.obj("t" to "shot", "id" to pieceId, "dx" to dx, "dy" to dy))
    }

    fun sendState(state: String) {
        send(Json.obj("t" to "state", "s" to state))
    }

    fun sendResign() = send("""{"t":"resign"}""")

    fun sendRematch() = send("""{"t":"rematch"}""")

    fun close(reason: String = "已断开") {
        if (closed) return
        // bye 要在 closed 置位之前发出去，否则 write() 自己会提前 return
        runCatching { writeRaw("""{"t":"bye"}""") }
        closed = true
        runCatching { socket.close() }
        scope.cancel()
        _events.trySend(LanEvent.PeerLeft(reason))
    }

    private fun write(line: String) {
        if (closed) return
        try {
            writeRaw(line)
        } catch (e: Exception) {
            log("写入失败", e)
            close("连接已断开")
        }
    }

    private fun writeRaw(line: String) {
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private suspend fun readLoop() {
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handle(line)
            }
            if (!closed) close("对手断开了连接")
        } catch (e: Exception) {
            if (!closed) {
                log("读取失败", e)
                close("对手断开了连接")
            }
        }
    }

    private fun handle(line: String) {
        val json = Json.parse(line) ?: return
        when (Json.str(json, "t")) {
            "hello" -> _events.trySend(LanEvent.PeerJoined(Json.str(json, "name", "对手")))
            "shot" -> {
                val id = Json.int(json, "id", -1)
                if (id < 0) return
                _events.trySend(
                    LanEvent.RemoteShot(
                        pieceId = id,
                        dx = Json.dbl(json, "dx").toFloat(),
                        dy = Json.dbl(json, "dy").toFloat(),
                    ),
                )
            }

            "state" -> {
                val s = Json.str(json, "s")
                if (s.isNotEmpty()) _events.trySend(LanEvent.RemoteState(s))
            }

            "resign" -> _events.trySend(LanEvent.RemoteResigned)
            "rematch" -> _events.trySend(LanEvent.RemoteRematch)
            "bye" -> close("对手退出了对局")
        }
    }

    companion object {
        const val PORT = 47800
        private const val TAG = "LanSession"

        private fun log(msg: String, e: Throwable? = null) {
            // 不用 android.util.Log：桌面版也要编这份代码。Android 上 println 一样进 logcat。
            println("[$TAG] $msg${e?.let { ": ${it.message}" } ?: ""}")
        }

        private fun helloJson(meta: LanMeta): String = Json.obj(
            "t" to "hello",
            "v" to PROTOCOL,
            "name" to meta.hostName,
            "kk" to (if (meta.killKing) 1 else 0),
            "bf" to (if (meta.bottomFirst) 1 else 0),
        )

        private fun parseHello(line: String?): LanMeta? {
            if (line == null) return null
            val json = Json.parse(line) ?: return null
            if (Json.str(json, "t") != "hello") return null
            return LanMeta(
                killKing = Json.int(json, "kk", 1) == 1,
                bottomFirst = Json.int(json, "bf", 1) == 1,
                hostName = Json.str(json, "name", "房主"),
            )
        }

        /** 协议版本；桌面版与 Android 都是 1。 */
        const val PROTOCOL = 1

        /** 本机的局域网 IPv4，取不到就返回 null。 */
        fun localIpv4(): String? = runCatching {
            // 网卡可能有好几个（Wi-Fi、有线，还有 WSL / Hyper-V / VPN 之类的虚拟网卡），
            // 这里按「像不像真的局域网」排个序，给界面显示一个最可能能连的地址。
            val virtualHints = listOf(
                "vethernet", "wsl", "hyper-v", "vmware", "virtualbox", "vethernet",
                "radmin", "tailscale", "zerotier", "tap", "tun", "docker", "loopback",
            )
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    ni.inetAddresses.toList().asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
                        .map { addr -> Triple(scoreInterface(ni.name, addr, virtualHints), addr, ni.name) }
                }
                .sortedByDescending { it.first }
                .firstOrNull()
                ?.second
                ?.hostAddress
        }.getOrNull()

        /** 越大越像「玩家真正在用的那块网卡」。 */
        private fun scoreInterface(name: String, addr: Inet4Address, virtualHints: List<String>): Int {
            val n = name.lowercase()
            val virtual = virtualHints.any { n.contains(it) }
            val octets = addr.address.map { it.toInt() and 0xFF }
            // 192.168.x 最常见，其次是 10.x / 172.16-31.x
            val subnetScore = when {
                octets[0] == 192 && octets[1] == 168 -> 30
                octets[0] == 10 -> 20
                octets[0] == 172 && octets[1] in 16..31 -> 15
                else -> 0
            }
            return subnetScore + (if (virtual) -50 else 0)
        }

        /**
         * 房主：开监听端口等对手连进来。取消协程会关掉端口。
         * 连上之后先把开局参数发过去，客人照着建引擎。
         */
        suspend fun hostAndWait(meta: LanMeta, port: Int = PORT): LanSession = withContext(Dispatchers.IO) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                // 短的 accept 超时 + 轮询 isActive，取消按钮才能立刻把服务端关掉
                server.soTimeout = 400
                var socket: Socket? = null
                while (socket == null) {
                    ensureActive()
                    socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        null
                    }
                }
                socket.tcpNoDelay = true
                val session = LanSession(socket, isHost = true)
                session.send(helloJson(meta))
                session
            } finally {
                runCatching { server.close() }
            }
        }

        /**
         * 客人：连到房主，先读第一条 hello 拿到规则，再返回会话。
         * 失败抛异常，界面负责提示。
         */
        suspend fun join(
            host: String,
            port: Int = PORT,
            timeoutMs: Int = 6000,
            myName: String = "客人",
        ): Pair<LanSession, LanMeta> =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)
                    socket.tcpNoDelay = true
                    // 先自报家门，房主才知道对手叫什么
                    val hello = Json.obj(
                        "t" to "hello",
                        "v" to PROTOCOL,
                        "name" to myName,
                        "kk" to 1,
                        "bf" to 1,
                    )
                    val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    out.write(hello)
                    out.write("\n")
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val meta = parseHello(reader.readLine()) ?: LanMeta()
                    LanSession(socket, isHost = false, existingReader = reader) to meta
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
            }
    }
}
