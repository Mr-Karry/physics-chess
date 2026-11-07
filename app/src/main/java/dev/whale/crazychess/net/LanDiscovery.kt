package dev.whale.crazychess.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** 被自动发现到的一个房间。 */
data class LanRoom(
    /** 房主每次开房生成的短码，用来在列表里去重。 */
    val id: String,
    val name: String,
    /** 房主的 IPv4（取 UDP 回包的源地址，多网卡也不会错）。 */
    val host: String,
    /** 房主 TCP 监听端口。 */
    val port: Int,
    val killKing: Boolean,
    val bottomFirst: Boolean,
    val seenAtMs: Long,
)

/**
 * UDP 广播自动发现。
 *
 * 设计成「**谁在等，谁应答**」的探测/应答模型，不依赖被动监听广播：
 *
 * - 客人（[LanFinder]）每 1.2 秒往广播地址的 [DISCOVERY_PORT] 发一条 probe，probe 里带
 *   `reply=<自己的端口>`，然后在自己这个**临时端口**上收应答；
 * - 房主（[LanAdvertiser]）绑定 [DISCOVERY_PORT]，收到 probe 就按 probe 的来源地址 +
 *   `reply` 端口**单播**回一条 offer。
 *
 * 为什么不让客人绑固定端口：同一台机器上可能同时开着桌面版和另一个工具/第二个实例，
 * Windows 的 UDP `SO_REUSEADDR` 只会让**一个** socket 收到包，固定端口会互相抢。
 * 临时端口各收各的，天然不冲突。
 *
 * 这样即使路由器把广播回程吃掉，客人的 probe 只要到得了，就能拿到房间。
 *
 * 只依赖 `java.net` 和协程，Android / Windows 桌面共用。
 */
object LanDiscovery {
    /** 房主收 probe 的端口。 */
    const val DISCOVERY_PORT = 47801

    /**
     * 旧版固定监听端口，现在不再使用（客人改成临时端口收应答），
     * 保留常量只是为了不破坏已有引用。
     */
    @Deprecated("客人改为临时端口收应答，不再固定监听")
    const val WATCH_PORT = 47802

    const val PROTOCOL = 1

    /** 超过这个时间没再听到，就从列表里消失。 */
    const val ROOM_TTL_MS = 5000L

    const val PROBE_INTERVAL_MS = 1200L
    private const val OFFER_INTERVAL_MS = 1500L

    private const val CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

    fun newRoomCode(): String {
        val rnd = java.util.Random()
        val sb = StringBuilder(4)
        repeat(4) { sb.append(CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)]) }
        return sb.toString()
    }

    /** 本机所有可能的广播地址（按网卡算）+ 全局广播。 */
    fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.toList().asSequence() }
                .mapNotNull { it.broadcast }
                .filterIsInstance<Inet4Address>()
                .forEach { out.add(it) }
        }
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.toList()
    }

    fun udpSocket(port: Int): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(port))
        socket.soTimeout = 400
        return socket
    }
}

/** 房主侧：在自己的端口上应答 probe，并周期性广播。 */
class LanAdvertiser(
    private val meta: LanMeta,
    private val tcpPort: Int = LanSession.PORT,
    val code: String = LanDiscovery.newRoomCode(),
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun offerJson(): String = Json.obj(
        "t" to "offer",
        "v" to LanDiscovery.PROTOCOL,
        "id" to code,
        "name" to meta.hostName,
        "port" to tcpPort,
        "kk" to (if (meta.killKing) 1 else 0),
        "bf" to (if (meta.bottomFirst) 1 else 0),
    )

    private suspend fun loop() {
        val socket = runCatching { LanDiscovery.udpSocket(LanDiscovery.DISCOVERY_PORT) }.getOrNull() ?: return
        try {
            while (job?.isActive == true) {
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = Json.parse(text) ?: continue
                if (Json.str(json, "t") != "probe") continue
                // 单播回一条 offer：地址用 probe 的源地址（多网卡也不会给错 IP），
                // 端口用 probe 里指定的应答端口（老客户端没有就回它自己的源端口）
                val replyPort = Json.int(json, "reply", packet.port)
                val reply = offerJson().toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(DatagramPacket(reply, reply.size, packet.address, replyPort))
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }
}

/** 客人侧：广播 probe，收集 offer，维护一个会自己过期的房间列表。 */
class LanFinder {
    private val _rooms = MutableStateFlow<List<LanRoom>>(emptyList())
    val rooms: StateFlow<List<LanRoom>> = _rooms.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _rooms.value = emptyList()
    }

    /** 立刻再发一轮 probe（"刷新"按钮）。 */
    fun poke() {
        job?.let { if (it.isActive) pokeRequested = true }
    }

    @Volatile
    private var pokeRequested = false

    private suspend fun loop() {
        val socket = runCatching { LanDiscovery.udpSocket(0) }.getOrNull() ?: return
        val found = LinkedHashMap<String, LanRoom>()
        try {
            var lastProbe = 0L
            while (job?.isActive == true) {
                val now = System.currentTimeMillis()
                if (now - lastProbe >= LanDiscovery.PROBE_INTERVAL_MS || pokeRequested) {
                    pokeRequested = false
                    probe(socket)
                    lastProbe = now
                }
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    // 正常，继续下一轮判断有没有要重发 probe / 清理过期房间
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = Json.parse(text)
                if (json != null && Json.str(json, "t") == "offer") {
                    val host = packet.address?.hostAddress?.substringBefore('%') ?: ""
                    val port = Json.int(json, "port", LanSession.PORT)
                    val id = Json.str(json, "id").ifEmpty { "$host:$port" }
                    if (host.isNotEmpty()) {
                        found[id] = LanRoom(
                            id = id,
                            name = Json.str(json, "name", "房主"),
                            host = host,
                            port = port,
                            killKing = Json.int(json, "kk", 1) == 1,
                            bottomFirst = Json.int(json, "bf", 1) == 1,
                            seenAtMs = System.currentTimeMillis(),
                        )
                    }
                }
                val cutoff = System.currentTimeMillis() - LanDiscovery.ROOM_TTL_MS
                found.entries.removeAll { it.value.seenAtMs < cutoff }
                val list = found.values.sortedBy { it.name }
                if (list != _rooms.value) _rooms.value = list
                delay(20)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun probe(socket: DatagramSocket) {
        val payload = Json.obj(
            "t" to "probe",
            "v" to LanDiscovery.PROTOCOL,
            "reply" to socket.localPort,
        ).toByteArray(Charsets.UTF_8)
        for (target in LanDiscovery.broadcastTargets()) {
            runCatching {
                socket.send(
                    DatagramPacket(payload, payload.size, target, LanDiscovery.DISCOVERY_PORT),
                )
            }
        }
    }
}
