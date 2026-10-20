package com.hkfuertes.shairport.satellites

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Chromecast satellites: every Google Cast device the phone finds is cast to ([cast], from
 * [Protocol]), each pulling Shairport's played audio as an endless WAV over HTTP — AirConnect's
 * aircast design, with the same caveat the cast devices bring: they follow the stream's arrival
 * and their own startup buffering, so they are neither sample-synchronized with it nor exactly
 * with each other. A rate change restarts the stream ([generation]): a device that keeps its old
 * one re-loads the new.
 */
class Satellites(
    /** The HTTP stream's port; the devices' own is 8009, theirs to choose. */
    port: Int = PORT,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(port)
    private val devices = ConcurrentHashMap<String, Device>()
    private val lock = Object()
    private var closed = false
    private var generation = 0
    private var rate = 44100
    private var write = 0L // this generation's bytes, its WAV header included
    private val ring = ByteArray(1 shl 21) // 2 MiB ≈ 12 s, the catch-up a listener may need

    init {
        thread(name = "cast-http") {
            while (!closed) runCatching { serve(server.accept()) }
                .onFailure { if (!closed) log("HTTP: $it") }
        }
    }

    val port: Int get() = server.localPort

    /** The audio Shairport played at [heardAt] (CLOCK_MONOTONIC ns): the cast follows its arrival. */
    fun audio(pcm: ByteArray, rate: Int, heardAt: Long) {
        if (pcm.isEmpty()) return // a flush: a cast device cannot drop what it buffered
        synchronized(lock) {
            if (rate != this.rate || generation == 0) {
                generation++
                this.rate = rate
                write = 0
                put(wav(rate))
                log("Stream $generation at $rate Hz")
            }
            put(pcm)
            lock.notifyAll()
        }
    }

    /** What the tap says Shairport's volume is (percent): the cast devices follow it. */
    var volume = 100
        set(value) {
            field = value
            devices.values.forEach { it.volume(value / 100.0) }
        }

    /** A Google Cast device found: cast to it until [forget] or [close]. */
    fun cast(id: String, name: String, address: String, port: Int, caps: Int) {
        devices.put(id, Device(name, address, port, caps))?.stop()
    }

    fun casting(id: String) = devices.containsKey(id)

    fun forget(id: String) {
        devices.remove(id)?.stop()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching { server.close() }
        devices.values.forEach(Device::stop)
        devices.clear()
    }

    /** One HTTP client, a cast device pulling the stream. */
    private fun serve(socket: Socket) = thread(name = "cast-http-${socket.port}") {
        runCatching { serveOne(socket) }.onFailure { if (!closed) log("HTTP: $it") }
    }

    private fun serveOne(socket: Socket) {
        socket.use {
            it.tcpNoDelay = true
            val request = StringBuilder()
            while (!request.contains("\r\n\r\n")) {
                val byte = it.getInputStream().read()
                if (byte < 0) return@use
                request.append(byte.toChar())
                if (request.length > 4096) return@use
            }
            if (!request.startsWith("GET")) return@use // its headers and body: not ours to answer
            log("HTTP: ${request.toString().substringBefore("\r")}")
            val mine: Int
            val headers: String
            var position: Long
            val head: ByteArray?
            synchronized(lock) {
                while (!closed && generation == 0) lock.wait(1000) // nothing played yet: hold it open
                if (closed) return@use
                mine = generation
                val oldest = (write - ring.size).coerceAtLeast(0)
                // 200, ranges ignored: the stream is endless, and the Chrome-stacked devices
                // (a Nest Audio) abort a 206 whose end the live stream then passes — AirConnect's
                // 206s are for the Cast-Lite ones. Each listener begins a quarter second behind
                // the live edge, whenever it comes: an own start, so a late one is not stuck at
                // this generation's beginning (seconds behind); its own buffering adds to that.
                position = (write - rate).coerceIn(oldest, write)
                headers = "HTTP/1.0 200 OK\r\nServer: Shairport\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n"
                head = wav(rate) // its stream begins with the header; [position] counts it too
            }
            val out = it.getOutputStream()
            out.write(headers.toByteArray())
            head?.let(out::write)
            out.flush()
            while (!closed && generation == mine) {
                val chunk = synchronized(lock) {
                    while (!closed && generation == mine && write == position) lock.wait(1000)
                    if (closed || generation != mine) null
                    else {
                        val oldest = (write - ring.size).coerceAtLeast(0)
                        if (position < oldest) {
                            log("A listener fell ${oldest - position} bytes behind: skipping ahead")
                            position = oldest
                        }
                        slice(position, minOf(write, position + (1 shl 16)))
                    }
                } ?: break
                out.write(chunk) // blocks on a slow device: its backpressure is its pace
                out.flush()
                position += chunk.size
            }
        }
    }

    private fun put(bytes: ByteArray) {
        var at = (write and ring.size.toLong() - 1).toInt()
        var left = bytes.size
        while (left > 0) {
            val n = minOf(left, ring.size - at)
            System.arraycopy(bytes, bytes.size - left, ring, at, n)
            at = (at + n) % ring.size
            left -= n
        }
        write += bytes.size
    }

    private fun slice(from: Long, to: Long): ByteArray {
        val bytes = ByteArray((to - from).toInt())
        var at = (from and ring.size.toLong() - 1).toInt()
        var done = 0
        while (done < bytes.size) {
            val n = minOf(bytes.size - done, ring.size - at)
            System.arraycopy(ring, at, bytes, done, n)
            at = (at + n) % ring.size
            done += n
        }
        return bytes
    }

    private fun wav(rate: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).run {
        put("RIFF".toByteArray()); putInt(-1) // endless
        put("WAVE".toByteArray()); put("fmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(-1)
        array()
    }

    /** A Google Cast device: its connection, kept to it. */
    private inner class Device(val name: String, private val address: String, private val port: Int, caps: Int) {
        /** The default media receiver plays our WAV; AirConnect's other app is for video-capable devices. */
        private val app = if (caps and 1 != 0) "46C1A819" else "CC1AD845"
        @Volatile private var stopped = false
        @Volatile private var level = volume / 100.0
        @Volatile private var volumeAt = 0 // the tap says the volume when it opens: no initial push
        private var requests = 0
        private var loaded = 0 // the generation loaded
        private var retryAt = 0L

        init {
            thread(name = "cast-$name") {
                while (!stopped) {
                    try {
                        Cast.socket(address, port).use { socket -> talk(socket) }
                    } catch (e: Exception) {
                        if (stopped) return@thread
                        log("Cast $name: $e")
                        runCatching { Thread.sleep(5000) } // then try again, like Sendspin's dialers
                    }
                }
            }
        }

        fun volume(level: Double) {
            this.level = level
            volumeAt++
        }

        fun stop() {
            stopped = true
        }

        private fun talk(socket: javax.net.ssl.SSLSocket) {
            socket.soTimeout = 10_000 // the heartbeat's own pace
            val out = socket.outputStream
            Cast.send(out, Cast.CONNECTION, null, CONNECT)
            Cast.send(out, Cast.RECEIVER, null, """{"type":"LAUNCH","requestId":${++requests},"appId":"$app"}""")
            val input = socket.inputStream
            var transport: String? = null
            var session: String? = null
            while (!stopped) {
                if (transport != null && generation != loaded && System.nanoTime() > retryAt) {
                    loaded = generation
                    val url = "http://${here()}:${this@Satellites.port}/stream.wav?g=$generation&n=$requests"
                    log("Cast $name → LOAD $url")
                    Cast.send(out, Cast.MEDIA, transport, """{"type":"LOAD","requestId":${++requests},"sessionId":${
                        session?.let { "\"$it\"" } ?: "null"
                    },"currentTime":0,"autoplay":true,"media":{"contentId":"$url","streamType":"LIVE","contentType":"audio/wav"}}""")
                }
                if (transport != null && volumeAt != sent) {
                    sent = volumeAt
                    log("Cast $name → VOLUME $level")
                    Cast.send(out, Cast.RECEIVER, null, """{"type":"SET_VOLUME","requestId":${++requests},"volume":{"level":${
                        String.format(Locale.ROOT, "%.4f", level)
                    },"muted":false}}""")
                }
                val (namespace, message) = try {
                    Cast.receive(input)
                } catch (e: SocketTimeoutException) {
                    Cast.send(out, Cast.HEARTBEAT, null, """{"type":"PING"}""")
                    continue
                }
                val type = JSONObject(message).optString("type")
                if (namespace != Cast.HEARTBEAT && type !in CHATTER) { // their periodic reports
                    val media = if (type == "MEDIA_STATUS") JSONObject(message).optJSONArray("status")?.optJSONObject(0) else null
                    log("Cast $name: $type${media?.let { " ${it.optString("playerState")} (${it.optString("idleReason")}) currentTime=${it.optDouble("currentTime")}" } ?: ""}")
                }
                if (type == "LOAD_FAILED") {
                    loaded = 0 // the device could not take it: try again, in a while
                    retryAt = System.nanoTime() + 5_000_000_000L
                }
                when {
                    namespace == Cast.HEARTBEAT && type == "PING" ->
                        Cast.send(out, Cast.HEARTBEAT, null, """{"type":"PONG"}""")
                    namespace == Cast.RECEIVER && type == "RECEIVER_STATUS" -> {
                        val applications = JSONObject(message).optJSONObject("status")?.optJSONArray("applications") ?: continue
                        for (i in 0 until applications.length()) {
                            val application = applications.getJSONObject(i)
                            if (application.optString("appId") != app || !application.has("transportId")) continue
                            if (transport == application.getString("transportId")) break
                            transport = application.getString("transportId")
                            session = application.getString("sessionId")
                            loaded = 0 // connected now: load
                            log("Cast $name: transport $transport")
                            Cast.send(out, Cast.CONNECTION, transport, CONNECT)
                        }
                    }
                }
            }
            runCatching { Cast.send(out, Cast.RECEIVER, null, """{"type":"STOP","requestId":${++requests}}""") }
            runCatching { Cast.send(out, Cast.CONNECTION, transport, """{"type":"CLOSE"}""") }
        }

        private var sent = 0

        /** Where the device reaches us: the address the route to it leaves from. */
        private fun here(): String = java.net.DatagramSocket().use { socket ->
            socket.connect(java.net.InetSocketAddress(address, port))
            socket.localAddress.hostAddress
        } ?: throw IOException("no address to stream from")
    }

    companion object {
        const val PORT = 8098

        /** Their own periodic reports, too many to log. */
        private val CHATTER = setOf("RECEIVER_STATUS", "MULTIZONE_STATUS", "DEVICE_UPDATED", "LAUNCH_STATUS")

        /** What the Cast SDK sends when it connects: the devices expect it of a sender. */
        private val CONNECT =
            """{"type":"CONNECT","origin":{},"userAgent":"Shairport","senderInfo":{"sdkType":2,"version":"15.605.1.3","browserVersion":"44.0.2403.30","platform":4,"systemVersion":"Macintosh; Intel Mac OS X10_10_3","connectionType":1}}"""
    }
}
