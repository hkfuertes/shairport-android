package com.hkfuertes.shairport

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake Sendspin player against [Satellites]: what a real one needs, in the order it needs it. */
class SatellitesTest {
    /** The player's side of one connection, whoever opened it: Noise responder, then plaintexts. */
    private class Player(private val suite: String = "25519_ChaChaPoly_SHA256") {
        private val key = Noise.privateKey()
        private val texts = LinkedBlockingQueue<String>()
        private val plain = LinkedBlockingQueue<ByteArray>()
        val closed = LinkedBlockingQueue<Boolean>()
        lateinit var ws: WebSocket
        private lateinit var send: Noise.Cipher
        private lateinit var receive: Noise.Cipher
        private var clientInit = ""

        fun opened(ws: WebSocket) {
            this.ws = ws
            clientInit = JSONObject().put("type", "client/init").put(
                "payload",
                JSONObject().put("client_id", Noise.base64url(Noise.public(key))).put("version", 1).put("suite", suite),
            ).toString()
            ws.send(clientInit)
        }

        fun text(text: String) = texts.put(text)
        fun binary(bytes: ByteBuffer) = plain.put(receive.decrypt(ByteArray(bytes.remaining()).also { bytes.get(it) }))

        fun nextText(): JSONObject = JSONObject(checkNotNull(texts.poll(5, TimeUnit.SECONDS)) { "no text message" })

        /** server/init and Noise message 1 in, message 2 out: the Sentinel PSK, unpaired. */
        fun handshake() {
            val serverInit = checkNotNull(texts.poll(5, TimeUnit.SECONDS))
            val serverId = JSONObject(serverInit).getJSONObject("payload").getString("server_id")
            val message1 = Noise.unbase64url(nextText().getJSONObject("payload").getString("data"))
            val noise = Noise(suite, false, key, Noise.unbase64url(serverId), (clientInit + serverInit).toByteArray(), Noise.SENTINEL_PSK)
            val payload = JSONObject(String(noise.readMessage1(message1)))
            assertEquals(Noise.SENTINEL_PSK_ID, payload.getString("psk_id"))
            assertEquals("sn", payload.getString("psk_category"))
            val message2 = noise.writeMessage2("{}".toByteArray())
            ws.send(JSONObject().put("type", "noise/handshake").put("payload", JSONObject().put("data", Noise.base64url(message2))).toString())
            val (send, receive) = noise.split()
            this.send = send
            this.receive = receive
        }

        fun message(type: String, payload: JSONObject) =
            ws.send(send.encrypt(byteArrayOf(0) + JSONObject().put("type", type).put("payload", payload).toString().toByteArray()))

        fun next(): ByteArray = checkNotNull(plain.poll(5, TimeUnit.SECONDS)) { "nothing received" }

        /** The next JSON message, which must be [type]: its payload. */
        fun expect(type: String): JSONObject {
            val message = next()
            assertEquals("binary type", 0, message[0].toInt())
            val json = JSONObject(String(message, 1, message.size - 1))
            assertEquals(type, json.getString("type"))
            return json.getJSONObject("payload")
        }

        fun hello(name: String, unpaired: Boolean = true, rate: Int = 44100) = message(
            "client/hello",
            JSONObject().put("name", name)
                .put("device_info", JSONObject().put("product_name", "ESP32").put("software_version", "1.0"))
                .put("supported_roles", JSONArray(listOf("player@v1", "metadata@v1")))
                .put(
                    "player@v1_support",
                    JSONObject().put("buffer_capacity", 1_000_000).put(
                        "supported_formats",
                        JSONArray().put(JSONObject().put("codec", "flac").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 24))
                            .put(JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", rate).put("bit_depth", 16)),
                    ),
                )
                .put("supported_pair_methods", JSONObject())
                .put("unpaired_access", JSONObject().put("enabled", unpaired)),
        )
    }

    private fun connect(port: Int, player: Player) = object : WebSocketClient(URI("ws://127.0.0.1:$port${Satellites.PATH}")) {
        override fun onOpen(handshake: ServerHandshake) = player.opened(this)
        override fun onMessage(text: String) = player.text(text)
        override fun onMessage(bytes: ByteBuffer) = player.binary(bytes)
        override fun onClose(code: Int, reason: String?, remote: Boolean) = player.closed.put(true)
        override fun onError(error: Exception) = Unit
    }.also { assertTrue(it.connectBlocking(5, TimeUnit.SECONDS)) }

    @Test
    fun servesAPlayer() {
        val reports = LinkedBlockingQueue<List<Pair<String, String>>>()
        Satellites("Living room", Noise.privateKey(), port = 0, changed = { reports.put(it) }).use { satellites ->
            val player = Player()
            val client = connect(satellites.port, player)
            player.handshake()
            assertEquals("Living room", player.expect("server/hello").getString("name"))

            player.hello("kitchen")
            val activate = player.expect("server/activate")
            assertEquals("[\"playback\"]", activate.getJSONArray("activities").toString())
            assertEquals("[\"player@v1\"]", activate.getJSONArray("active_roles").toString())
            val group = player.expect("group/update")
            assertEquals("stopped", group.getString("playback_state"))
            assertEquals("Living room", group.getString("group_name"))
            val report = checkNotNull(reports.poll(5, TimeUnit.SECONDS)).single()
            assertEquals("127.0.0.1", report.first)
            assertEquals("kitchen", JSONObject(report.second).getString("name"))

            // Time: the server clock is System.nanoTime() in µs.
            player.message("client/time", JSONObject().put("client_transmitted", 42))
            val time = player.expect("server/time")
            assertEquals(42, time.getLong("client_transmitted"))
            assertNear(System.nanoTime() / 1000, time.getLong("server_received"), 500_000)
            assertTrue(time.getLong("server_transmitted") >= time.getLong("server_received"))

            // Its first state: it gets our volume (100 while the AirPlay volume is in the PCM).
            val state = JSONObject().put("volume", 30).put("muted", false).put("output_delay_ms", 0)
                .put("required_lead_time_ms", 0).put("min_buffer_ms", 0).put("supported_commands", JSONArray(listOf("volume")))
            player.message("client/state", JSONObject().put("available", true).put("player", state))
            val command = player.expect("server/command").getJSONObject("player")
            assertEquals("volume", command.getString("command"))
            assertEquals(100, command.getInt("volume"))

            // Buffers of Shairport's sizes, one stuffed (353): the first only sets the timeline,
            // the rest open the stream and go out as 20 ms chunks heard at their buffer's time.
            var heardAt = System.nanoTime() + 400_000_000
            val firstSent = heardAt + 352 * 1_000_000_000L / 44100
            for ((index, frames) in listOf(352, 352, 353, 352, 352, 352, 352, 352).withIndex()) {
                satellites.audio(ByteArray(frames * 4) { (index + 1).toByte() }, 44100, heardAt)
                heardAt += 352 * 1_000_000_000L / 44100 // the stuffed frame doesn't move the timeline
            }
            assertEquals("playing", player.expect("group/update").getString("playback_state"))
            val format = player.expect("stream/start").getJSONObject("player")
            assertEquals("pcm", format.getString("codec"))
            assertEquals(44100, format.getInt("sample_rate"))
            assertEquals(2, format.getInt("channels"))
            assertEquals(16, format.getInt("bit_depth"))
            val first = ByteBuffer.wrap(player.next())
            assertEquals(4, first.get(0).toInt())
            assertEquals(firstSent / 1000, first.getLong(1))
            assertNear(first.getLong(1) - System.nanoTime() / 1000, first.getInt(9).toLong(), 500_000) // send_ahead
            assertEquals(13 + 882 * 4, first.limit())
            assertEquals(2, first.get(13).toInt()) // the second buffer's frames...
            assertEquals(3, first.get(13 + 352 * 4).toInt()) // ...then the third's
            assertNear(first.getLong(1) + 20_000, ByteBuffer.wrap(player.next()).getLong(1), 50) // 2 x 882 of 7 x 352

            // Flush (pause, skip): the stream ends; the next audio opens a fresh one.
            satellites.audio(ByteArray(0), 0, 0)
            player.expect("stream/end")
            assertEquals("stopped", player.expect("group/update").getString("playback_state"))
            val afterFlush = heardAt + 352 * 1_000_000_000L / 44100
            repeat(4) {
                satellites.audio(ByteArray(352 * 4) { 9 }, 44100, heardAt)
                heardAt += 352 * 1_000_000_000L / 44100
            }
            player.expect("group/update")
            player.expect("stream/start")
            val fresh = player.next()
            assertTrue("pre-flush PCM survived", fresh.drop(13).all { it == 9.toByte() })
            assertEquals(afterFlush / 1000, ByteBuffer.wrap(fresh).getLong(1))

            // Linked volume: pushed to the players that take it.
            satellites.volume = 40
            assertEquals(40, player.expect("server/command").getJSONObject("player").getInt("volume"))

            client.closeBlocking()
            assertEquals(emptyList<Pair<String, String>>(), reports.poll(5, TimeUnit.SECONDS)) // gone
        }
    }

    @Test
    fun dialsAPlayerThatWaits() {
        val player = Player("25519_AESGCM_SHA256")
        val listening = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
            override fun onOpen(ws: WebSocket, handshake: ClientHandshake) = player.opened(ws)
            override fun onMessage(ws: WebSocket, text: String) = player.text(text)
            override fun onMessage(ws: WebSocket, bytes: ByteBuffer) = player.binary(bytes)
            override fun onClose(ws: WebSocket, code: Int, reason: String?, remote: Boolean) = player.closed.put(true)
            override fun onError(ws: WebSocket?, error: Exception) = Unit
            override fun onStart() = Unit
        }
        listening.start()
        try {
            Thread.sleep(200) // bound
            Satellites("Living room", Noise.privateKey(), port = 0).use { satellites ->
                satellites.connect("player", URI("ws://127.0.0.1:${listening.port}${Satellites.PATH}"))
                player.handshake()
                player.expect("server/hello")
                // No unpaired access: held idle, never activated (pairing isn't supported).
                player.hello("locked", unpaired = false)
                val activate = player.expect("server/activate")
                assertEquals(0, activate.getJSONArray("activities").length())
                assertEquals(0, activate.getJSONArray("active_roles").length())
                player.expect("group/update")
                player.message("client/goodbye", JSONObject().put("reason", "shutdown"))
                assertEquals(true, player.closed.poll(5, TimeUnit.SECONDS))
            }
        } finally {
            listening.stop(1000)
        }
    }

    @Test
    fun playerWithoutOurFormatGetsNoStream() {
        Satellites("Living room", Noise.privateKey(), port = 0).use { satellites ->
            val player = Player()
            connect(satellites.port, player)
            player.handshake()
            player.expect("server/hello")
            player.hello("48k only", rate = 48000)
            player.expect("server/activate")
            player.expect("group/update")
            player.message("client/state", JSONObject().put("available", true).put("player", JSONObject().put("output_delay_ms", 0)))
            var heardAt = System.nanoTime() + 400_000_000
            repeat(4) {
                satellites.audio(ByteArray(352 * 4), 44100, heardAt)
                heardAt += 352 * 1_000_000_000L / 44100
            }
            player.expect("group/update") // playing, but nothing for it to play
            player.message("client/time", JSONObject().put("client_transmitted", 1))
            player.expect("server/time")
        }
    }

    @Test
    fun badInitGetsAnError() {
        Satellites("Living room", Noise.privateKey(), port = 0).use { satellites ->
            val player = Player()
            val client = object : WebSocketClient(URI("ws://127.0.0.1:${satellites.port}${Satellites.PATH}")) {
                override fun onOpen(handshake: ServerHandshake) =
                    send("""{"type":"client/init","payload":{"client_id":"x","version":2,"suite":"25519_ChaChaPoly_SHA256"}}""")
                override fun onMessage(text: String) = player.text(text)
                override fun onClose(code: Int, reason: String?, remote: Boolean) = player.closed.put(true)
                override fun onError(error: Exception) = Unit
            }
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS))
            val error = player.nextText()
            assertEquals("server/error", error.getString("type"))
            assertEquals("unsupported_version", error.getJSONObject("payload").getString("reason"))
            assertEquals(true, player.closed.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun busyPortFailsTheConstructor() {
        ServerSocket(0).use { occupied ->
            assertTrue(runCatching { Satellites("x", Noise.privateKey(), port = occupied.localPort) }.isFailure)
        }
    }

    private fun assertNear(expected: Long, actual: Long, delta: Long) =
        assertTrue("$actual not within $delta of $expected", abs(actual - expected) <= delta)
}
