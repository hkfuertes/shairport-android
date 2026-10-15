package com.hkfuertes.shairport.satellites

import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Sendspin server (github.com/Sendspin/spec) for satellites: Sendspin players (the
 * sendspin CLI, ESPHome, Music Assistant's own players...) play what Shairport plays, when it
 * plays it. [audio] gets every buffer Shairport's AAudio is given (its audio tap, [Tap]) with the
 * time its first frame is heard (Shairport's should_be_time, on the sender's timeline, AirPlay 2's
 * PTP included) and sends it in 20 ms PCM chunks stamped with that time: players sound with the
 * Shairport device and the rest of an AirPlay group.
 *
 * Both ways in: players that connect to us (`_sendspin-server._tcp`, [port]) and players we
 * [connect] to (`_sendspin._tcp`, found by SatellitesService). Noise KKpsk2 with the Sentinel PSK, i.e.
 * unpaired access: ponytail: no pairing (CPace) and no per-player approval, turning Satellites on
 * approves every player that admits unpaired access. Pairing when a player insists on it.
 *
 * One clock for everything, System.nanoTime(): chunk times and the time replies players sync to.
 * No android.* here (org.json aside): the unit test runs it on the JVM.
 */
class Satellites(
    private val name: String,
    private val identity: ByteArray, // X25519 private key: our server_id, kept across runs
    port: Int = PORT,
    private val log: (String) -> Unit = {},
    /** Every connect and disconnect: each player's address and client/hello (JSON). */
    private val changed: (List<Pair<String, String>>) -> Unit = {},
) : AutoCloseable {
    private val serverId = Noise.base64url(Noise.public(identity))
    private val peers = CopyOnWriteArrayList<Peer>()
    private val dialers = ConcurrentHashMap<String, Dialer>()
    /** Everything: protocol state, chunking, and the order of encrypted messages (Noise nonces). */
    private val lock = Any()
    private val started = CountDownLatch(1)
    @Volatile private var bindError: Exception? = null
    private var closed = false // under dialers' lock

    private val server = object : WebSocketServer(InetSocketAddress(port)) {
        override fun onStart() = started.countDown()
        override fun onOpen(ws: WebSocket, handshake: ClientHandshake) = ws.setAttachment(Peer(ws, null))
        override fun onMessage(ws: WebSocket, text: String) = ws.peer()?.text(text) ?: Unit
        override fun onMessage(ws: WebSocket, bytes: ByteBuffer) = ws.peer()?.binary(bytes) ?: Unit
        override fun onClose(ws: WebSocket, code: Int, reason: String?, remote: Boolean) = ws.peer()?.closed() ?: Unit
        override fun onError(ws: WebSocket?, error: Exception) {
            if (ws != null) return log("Satellite ${ws.remoteSocketAddress}: $error")
            bindError = error
            started.countDown()
        }
    }

    /**
     * Every player's volume, in percent, for those that take it: what Shairport's tap says, 100
     * while the AirPlay volume is in the PCM, the device's music volume with Shairport's linked volume.
     */
    var volume = 100
        set(percent) = synchronized(lock) {
            if (percent == field) return
            field = percent
            peers.forEach { it.volume() }
        }

    private var rate = DEFAULT_RATE
    private var chunk = ByteArray(chunkBytes(rate))
    private var filled = 0
    private var chunkStart = 0L
    private var expected = 0L // when the next buffer should be heard; 0: no buffer yet
    /** Audio flows: players have a stream open and the group is "playing". */
    private var playing = false

    val port: Int get() = server.port

    init {
        server.isReuseAddr = true
        server.connectionLostTimeout = LOST_TIMEOUT_S // pings: a vanished player is dropped
        server.start()
        if (!started.await(5, TimeUnit.SECONDS) || bindError != null) {
            close()
            throw bindError ?: IllegalStateException("Sendspin server did not start")
        }
    }

    /** A player that waits for servers (`_sendspin._tcp`): keep a connection to it while [key] lasts. */
    fun connect(key: String, uri: URI) = synchronized(dialers) {
        if (closed) return // a discovery that outlived us
        dialers.remove(key)?.stop()
        dialers[key] = Dialer(uri).also { it.start() }
    }

    fun forget(key: String) {
        synchronized(dialers) { dialers.remove(key)?.stop() }
    }

    /**
     * The tap's reader: 16-bit stereo [pcm] whose first frame is heard at [heardAt]
     * (System.nanoTime()), about 1 s ahead (the lead SatellitesService asks Shairport for).
     * No frames: a flush (pause, skip, stop, the tap's end).
     */
    fun audio(pcm: ByteArray, rate: Int, heardAt: Long) = synchronized(lock) {
        if (pcm.isEmpty()) return@synchronized flush()
        if (rate <= 0) return@synchronized
        if (rate != this.rate) {
            flush() // streams restart in the new format
            this.rate = rate
            chunk = ByteArray(chunkBytes(rate))
        }
        val continues = expected != 0L && abs(heardAt - expected) < MAX_JUMP_NS
        expected = heardAt + nanos(pcm.size / FRAME_BYTES, rate)
        // ponytail: a buffer that doesn't continue the last one (start, pause, skip, a resync)
        // only sets the new timeline; its 10-20 ms are dropped. The first buffer of a stream may
        // carry a stale time (Shairport skipping frames at the start), the next ones don't.
        if (!continues) {
            filled = 0
            return@synchronized
        }
        var offset = 0
        while (offset < pcm.size) {
            if (filled == 0) chunkStart = heardAt + nanos(offset / FRAME_BYTES, rate)
            val count = minOf(chunk.size - filled, pcm.size - offset)
            System.arraycopy(pcm, offset, chunk, filled, count)
            filled += count
            offset += count
            if (filled == chunk.size) {
                if (!playing) {
                    playing = true
                    peers.forEach { it.group(); it.start() }
                }
                val duration = nanos(chunk.size / FRAME_BYTES, rate) / 1000
                peers.forEach { it.chunk(micros(chunkStart), chunk, duration) }
                filled = 0
            }
        }
    }

    /** Pause, skip or stop: players drop what they hold; the next audio starts a new stream. */
    private fun flush() = synchronized(lock) {
        filled = 0
        expected = 0L
        if (!playing) return
        playing = false
        peers.forEach { it.end(); it.group() }
    }

    override fun close() {
        synchronized(dialers) {
            closed = true
            dialers.values.forEach { it.stop() }
            dialers.clear()
        }
        runCatching { server.stop(1000) }
        peers.forEach { it.ws.close() }
    }

    private fun report() = synchronized(lock) {
        changed(peers.filter { it.player }.mapNotNull { peer -> peer.hello?.let { peer.address to it.toString() } })
    }

    private fun WebSocket.peer(): Peer? = getAttachment<Peer>()

    /** One player, whichever side opened the WebSocket: the server is always the Noise initiator. */
    private inner class Peer(val ws: WebSocket, private val dialer: Dialer?) {
        val address: String = ws.remoteSocketAddress?.address?.hostAddress.orEmpty()
        private var noise: Noise? = null
        private var send: Noise.Cipher? = null
        private var receive: Noise.Cipher? = null
        /** Its client/hello, once activated. */
        @Volatile var hello: JSONObject? = null
        /** player@v1 active: it gets audio once it reports state and is available. */
        @Volatile var player = false
        private var available = false
        private var state: JSONObject? = null // its latest client/state player object
        private var streaming = false
        private var unplayable = 0 // the rate we last told the log it can't play
        /** Chunks it may still hold: (when they finish playing, bytes), for its buffer_capacity. */
        private val held = ArrayDeque<LongArray>()

        init {
            peers += this
        }

        /** Cleartext: client/init and Noise message 2. Any failure closes, as the spec asks. */
        fun text(text: String) = synchronized(lock) {
            runCatching {
                val message = JSONObject(text)
                val payload = message.getJSONObject("payload")
                when (message.getString("type")) {
                    "client/init" -> if (noise == null) init(text, payload) else error("second client/init")
                    "noise/handshake" -> if (noise != null && send == null) handshake(payload) else error("stray handshake")
                    else -> error("cleartext ${message.getString("type")}")
                }
            }.onFailure { fail(it) }
            Unit
        }

        fun binary(bytes: ByteBuffer) = synchronized(lock) {
            runCatching {
                val receive = checkNotNull(receive) { "binary before the handshake" }
                val plain = receive.decrypt(ByteArray(bytes.remaining()).also { bytes.get(it) })
                // ponytail: JSON only; fragments (type 1) only carry what players send us in roles we don't activate.
                if (plain.isNotEmpty() && plain[0].toInt() == 0) {
                    val message = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
                    handle(message.getString("type"), message.getJSONObject("payload"))
                }
            }.onFailure { fail(it) }
            Unit
        }

        fun closed() = synchronized(lock) {
            if (!peers.remove(this)) return
            log("Satellite $address disconnected")
            if (player) report()
        }

        private fun fail(error: Throwable) {
            log("Satellite $address: ${error.message}")
            ws.close()
        }

        private fun init(text: String, payload: JSONObject) {
            val clientId = runCatching { Noise.unbase64url(payload.getString("client_id")) }.getOrNull()
            val reason = when {
                payload.opt("version") != 1 -> "unsupported_version"
                payload.optString("suite") !in Noise.SUITES -> "unsupported_suite"
                clientId?.size != 32 -> "malformed"
                else -> null
            }
            if (reason != null) {
                ws.send(json("server/error", JSONObject().put("reason", reason)))
                return ws.close()
            }
            val serverInit = json("server/init", JSONObject().put("server_id", serverId).put("version", 1))
            // The prologue: both init messages, exactly as they travel.
            val noise = Noise(payload.getString("suite"), true, identity, clientId!!, (text + serverInit).toByteArray(), Noise.SENTINEL_PSK)
            val message1 = noise.writeMessage1("""{"psk_id":"${Noise.SENTINEL_PSK_ID}","psk_category":"sn"}""".toByteArray())
            this.noise = noise
            ws.send(serverInit)
            ws.send(json("noise/handshake", JSONObject().put("data", Noise.base64url(message1))))
        }

        private fun handshake(payload: JSONObject) {
            val noise = checkNotNull(noise)
            noise.readMessage2(Noise.unbase64url(payload.getString("data"))) // throws on a bad tag
            val (send, receive) = noise.split()
            this.send = send
            this.receive = receive
            message("server/hello") { JSONObject().put("name", name) }
        }

        private fun handle(type: String, payload: JSONObject) {
            when (type) {
                "client/hello" -> hello(payload)
                "client/time" -> {
                    val received = now()
                    message("server/time") {
                        JSONObject().put("client_transmitted", payload.getLong("client_transmitted"))
                            .put("server_received", received).put("server_transmitted", now())
                    }
                }
                "client/state" -> state(payload)
                // ponytail: leaving our only group means no audio until it reconnects.
                "client/leave" -> {
                    available = false
                    end()
                }
                "client/goodbye" -> {
                    log("Satellite $address said goodbye: ${payload.optString("reason")}")
                    dialer?.goodbye(payload.optString("reason"))
                    ws.close()
                }
            }
        }

        private fun hello(payload: JSONObject) {
            val roles = payload.optJSONArray("supported_roles")
            val unpaired = payload.optJSONObject("unpaired_access")?.optBoolean("enabled") == true
            player = unpaired && payload.has("player@v1_support") &&
                (0 until (roles?.length() ?: 0)).any { roles?.optString(it) == "player@v1" }
            log("Satellite $address connected: ${payload.optString("name")}" + if (unpaired) "" else " (needs pairing: not supported)")
            hello = payload
            message("server/activate") {
                val active = if (player) listOf("player@v1") else emptyList()
                JSONObject().put("activities", JSONArray(if (player) listOf("playback") else emptyList<String>()))
                    .put("active_roles", JSONArray(active))
            }
            group()
            if (player) report()
        }

        private fun state(payload: JSONObject) {
            available = payload.optBoolean("available")
            payload.optJSONObject("player")?.let { player ->
                val first = state == null
                state = player
                if (first) volume()
            }
            if (!available) end() else if (playing) start()
        }

        fun group() {
            if (hello == null) return
            message("group/update") {
                JSONObject().put("playback_state", if (playing) "playing" else "stopped")
                    .put("group_id", serverId).put("group_name", name)
            }
        }

        fun volume() {
            val state = state ?: return
            val commands = state.optJSONArray("supported_commands")
            if ((0 until (commands?.length() ?: 0)).none { commands?.optString(it) == "volume" }) return
            if (state.optInt("volume", -1) == volume) return
            message("server/command") { JSONObject().put("player", JSONObject().put("command", "volume").put("volume", volume)) }
        }

        /** Opens its stream if it can play now; we only send 16-bit stereo PCM at Shairport's rate. */
        fun start() {
            if (streaming || !player || !available || state == null || !playing) return
            if (!plays(rate)) {
                if (unplayable != rate) log("Satellite $address can't play PCM, 16-bit stereo at $rate Hz")
                unplayable = rate
                return
            }
            message("stream/start") {
                JSONObject().put("server_transmitted", now()).put(
                    "player",
                    JSONObject().put("codec", "pcm").put("sample_rate", rate).put("channels", 2).put("bit_depth", 16),
                )
            }
            streaming = true
            held.clear()
        }

        fun end() {
            if (!streaming) return
            message("stream/end") { JSONObject().put("roles", JSONArray(listOf("player"))) }
            streaming = false
            held.clear()
        }

        /** Binary type 4: a chunk first heard at [timestamp] (µs), as far as its buffer allows. */
        fun chunk(timestamp: Long, pcm: ByteArray, duration: Long) {
            if (!streaming) return
            val now = now()
            if (timestamp <= now) return // late: it would drop it
            while (held.isNotEmpty() && held.first()[0] <= now) held.removeFirst()
            val size = 13 + pcm.size
            val capacity = hello?.optJSONObject("player@v1_support")?.optLong("buffer_capacity") ?: 0
            // ponytail: a full buffer drops the chunk (a gap); only a tiny buffer_capacity hits it.
            if (held.sumOf { it[1] } + size > capacity) return
            val delay = (state?.optLong("output_delay_ms") ?: 0) * 1000
            held.addLast(longArrayOf(timestamp + duration - delay, size.toLong()))
            send {
                ByteBuffer.allocate(size).put(4).putLong(timestamp)
                    .putInt((timestamp - now()).coerceIn(0, 0xFFFF_FFFFL).toInt()).put(pcm).array()
            }
        }

        private fun plays(rate: Int): Boolean {
            val formats = hello?.optJSONObject("player@v1_support")?.optJSONArray("supported_formats") ?: return false
            return (0 until formats.length()).any { i ->
                val format = formats.optJSONObject(i) ?: return@any false
                format.optString("codec") == "pcm" && format.optInt("channels") == 2 &&
                    format.optInt("sample_rate") == rate && format.optInt("bit_depth") == 16
            }
        }

        /** An encrypted JSON message; [payload] is built last, so its timestamps are send times. */
        private fun message(type: String, payload: () -> JSONObject) = send {
            byteArrayOf(0) + JSONObject().put("type", type).put("payload", payload()).toString().toByteArray()
        }

        private fun send(plain: () -> ByteArray) {
            val send = send ?: return
            if (!ws.isOpen) return
            runCatching { ws.send(send.encrypt(plain())) }
        }
    }

    /** Server-initiated: connects to a player, and again whenever it drops, until told otherwise. */
    private inner class Dialer(private val uri: URI) {
        @Volatile private var active = true
        @Volatile private var client: WebSocketClient? = null
        private val worker = thread(start = false, isDaemon = true, name = "satellite-dial") { run() }

        fun start() = worker.start()

        fun stop() {
            active = false
            client?.close()
            worker.interrupt()
        }

        /** Only a restarting player wants us back; the others (another server, shutdown...) don't. */
        fun goodbye(reason: String) {
            if (reason != "restart") active = false
        }

        private fun run() {
            while (active) {
                val closed = CountDownLatch(1)
                val client = object : WebSocketClient(uri) {
                    override fun onOpen(handshake: ServerHandshake) = setAttachment(Peer(this, this@Dialer))
                    override fun onMessage(text: String) = peer()?.text(text) ?: Unit
                    override fun onMessage(bytes: ByteBuffer) = peer()?.binary(bytes) ?: Unit
                    override fun onClose(code: Int, reason: String?, remote: Boolean) {
                        peer()?.closed()
                        closed.countDown()
                    }
                    override fun onError(error: Exception) = Unit // onClose follows
                }
                client.connectionLostTimeout = LOST_TIMEOUT_S
                this.client = client
                runCatching {
                    if (client.connectBlocking(5, TimeUnit.SECONDS)) closed.await() else client.close()
                }
                if (!active) break
                runCatching { Thread.sleep(RETRY_MS) }
            }
            client?.close()
        }
    }

    companion object {
        /** Sendspin's recommended port and path for servers. */
        const val PORT = 8927
        const val PATH = "/sendspin"
        private const val FRAME_BYTES = 4 // 16-bit stereo, all Shairport's AAudio backend plays
        private const val DEFAULT_RATE = 44100 // AirPlay's
        private const val MAX_JUMP_NS = 1_000_000L // stuffing moves a buffer by one frame, ~23 us
        private const val LOST_TIMEOUT_S = 10
        private const val RETRY_MS = 5000L

        private fun json(type: String, payload: JSONObject) = JSONObject().put("type", type).put("payload", payload).toString()

        /** 20 ms: within Sendspin's 15-150 ms per chunk. */
        private fun chunkBytes(rate: Int) = rate / 50 * FRAME_BYTES

        private fun nanos(frames: Int, rate: Int) = frames * 1_000_000_000L / rate

        private fun micros(nanos: Long) = Math.floorDiv(nanos, 1000L)

        /** The server clock players sync to, in µs. */
        private fun now() = micros(System.nanoTime())
    }
}
