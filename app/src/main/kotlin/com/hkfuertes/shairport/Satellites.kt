package com.hkfuertes.shairport

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Minimal Snapcast server (the binary protocol of snapcast's doc/binary_protocol.md) for
 * satellites: snapclient on Linux, an ESP32 (CarlosDerSeher/snapclient) or Snapdroid play what this
 * device plays, when it plays it. [audio] gets every buffer AAudio is given with the time its first
 * frame is heard (Shairport's should_be_time, on the sender's timeline, AirPlay 2's PTP included)
 * and sends it in 20 ms chunks stamped with that time minus [BUFFER_MS]: clients play a chunk at
 * its timestamp plus bufferMs, so they sound with this device and the rest of an AirPlay group.
 *
 * One clock for everything, System.nanoTime(): chunk times and the Time replies clients sync to.
 * No android.* here: the unit test runs it on the JVM.
 */
class Satellites(
    port: Int = PORT,
    controlPort: Int = CONTROL_PORT,
    private val log: (String) -> Unit = {},
    /** Every connect and disconnect: each client's address and Hello (JSON: HostName, Version...). */
    private val changed: (List<Pair<String, String>>) -> Unit = {},
) : AutoCloseable {
    private val server = listen(port)
    private val control = listen(controlPort)
    private val clients = CopyOnWriteArrayList<Client>()
    /** Keeps each client's messages in protocol order: settings, codec header, then chunks. */
    private val lock = Any()
    @Volatile private var codecHeader = codecHeader(DEFAULT_RATE)

    /**
     * Every client's volume, in percent: Snapcast's own, which each client applies itself. 100 while
     * the AirPlay volume is in the PCM; with linked volume, this device's music volume (EngineService).
     */
    var volume = 100
        set(percent) = synchronized(lock) {
            if (percent == field) return
            field = percent
            clients.forEach { if (it.ready) it.send(SERVER_SETTINGS, settings(percent)) }
        }

    // Chunking: only the player thread (audio) touches these.
    private var rate = DEFAULT_RATE
    private var chunk = ByteArray(chunkBytes(rate))
    private var filled = 0
    private var chunkStart = 0L
    private var expected = 0L // when the next buffer should be heard; 0: no buffer yet

    val port: Int get() = server.localPort
    val controlPort: Int get() = control.localPort

    init {
        thread(name = "satellites", isDaemon = true) { accept(server) { Client(it).start() } }
        // ponytail: Snapdroid shows Play only while connected to the JSON-RPC port, so it gets a
        // connection and no answers (an empty group list). A real control API if one is needed.
        thread(name = "satellites-control", isDaemon = true) {
            accept(control) { socket ->
                thread(name = "satellites-control-client", isDaemon = true) {
                    runCatching { socket.use { val input = it.getInputStream(); while (input.read() >= 0) Unit } }
                }
            }
        }
    }

    /**
     * Shairport's player thread: 16-bit stereo [pcm] whose first frame is heard at [heardAt]
     * (System.nanoTime()), about 1 s ahead (the AAudio buffer ReceiverService asks for).
     * No frames: a flush (pause, skip, stop), maybe from another thread.
     */
    fun audio(pcm: ByteArray, rate: Int, heardAt: Long) {
        if (pcm.isEmpty()) return flush()
        if (rate <= 0) return
        if (rate != this.rate) {
            this.rate = rate
            chunk = ByteArray(chunkBytes(rate))
            filled = 0
            expected = 0L
            synchronized(lock) {
                codecHeader = codecHeader(rate)
                clients.forEach { if (it.ready) it.send(CODEC_HEADER, codecHeader) }
            }
        }
        val continues = expected != 0L && abs(heardAt - expected) < MAX_JUMP_NS
        expected = heardAt + nanos(pcm.size / FRAME_BYTES, rate)
        // ponytail: a buffer that doesn't continue the last one (start, pause, skip, a resync)
        // only sets the new timeline; its 10-20 ms are dropped. The first buffer of a stream may
        // carry a stale time (Shairport skipping frames at the start), the next ones don't.
        if (!continues) {
            filled = 0
            return
        }
        var offset = 0
        while (offset < pcm.size) {
            if (filled == 0) chunkStart = heardAt + nanos(offset / FRAME_BYTES, rate)
            val count = minOf(chunk.size - filled, pcm.size - offset)
            System.arraycopy(pcm, offset, chunk, filled, count)
            filled += count
            offset += count
            if (filled == chunk.size) {
                val message = ByteBuffer.allocate(12 + chunk.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putTime(micros(chunkStart) - BUFFER_MS * 1000).putInt(chunk.size).put(chunk).array()
                synchronized(lock) { clients.forEach { if (it.ready) it.send(WIRE_CHUNK, message) } }
                filled = 0
            }
        }
    }

    /**
     * Clients drop what they hold: snapclient restarts its stream on a codec header. The chunker
     * needs nothing: the next buffer starts a new timeline anyway.
     */
    private fun flush() = synchronized(lock) {
        clients.forEach { if (it.ready) it.send(CODEC_HEADER, codecHeader) }
    }

    override fun close() {
        server.close()
        control.close()
        clients.forEach { it.close() }
    }

    private inner class Client(private val socket: Socket) {
        private val queue = ArrayBlockingQueue<Message>(QUEUE_LENGTH)
        private val name = socket.remoteSocketAddress.toString()
        val address: String = socket.inetAddress.hostAddress.orEmpty()
        private var writer: Thread? = null
        /** Its Hello, once answered: chunks follow. */
        @Volatile var hello: String? = null
        val ready get() = hello != null

        // ponytail: no read timeout. An ESP32 only sends Time while it receives audio, and a
        // vanished client is found once audio flows (its queue fills).
        fun start() {
            socket.tcpNoDelay = true
            clients += this
            writer = thread(name = "satellite-write", isDaemon = true) { runCatching { write() }; close() }
            thread(name = "satellite-read", isDaemon = true) { runCatching { read() }; close() }
        }

        fun send(type: Int, payload: ByteArray, refersTo: Int = 0) {
            // ponytail: a client a whole queue behind has missed its play times anyway; it reconnects.
            if (!queue.offer(Message(type, refersTo, payload))) close()
        }

        fun close() {
            if (!clients.remove(this)) return
            runCatching { socket.close() }
            writer?.interrupt()
            log("Satellite $name disconnected")
            report()
        }

        private fun read() {
            val input = DataInputStream(socket.getInputStream().buffered())
            val header = ByteArray(HEADER_BYTES)
            while (true) {
                input.readFully(header)
                val received = micros(System.nanoTime())
                val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val type = fields.getShort(0).toInt() and 0xFFFF
                val id = fields.getShort(2).toInt() and 0xFFFF
                val sent = fields.getInt(6) * 1_000_000L + fields.getInt(10)
                val size = fields.getInt(22)
                if (size !in 0..MAX_PAYLOAD) throw IOException("bad message size $size")
                val payload = ByteArray(size).also { input.readFully(it) }
                when (type) {
                    // Server settings (snapclient waits for them as the Hello's answer), then the
                    // codec header: from then on, chunks.
                    HELLO -> {
                        val json = String(payload, Charsets.UTF_8).dropWhile { it != '{' }
                        log("Satellite $name connected: $json")
                        synchronized(lock) {
                            send(SERVER_SETTINGS, settings(volume), refersTo = id)
                            send(CODEC_HEADER, codecHeader)
                            hello = json
                        }
                        report()
                    }
                    // latency = server receive time - client send time; the client works out the
                    // clock difference from it and our send time in the reply's header.
                    TIME -> send(TIME, le(8).putTime(received - sent).array(), refersTo = id)
                }
            }
        }

        private fun write() {
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = le(HEADER_BYTES)
            while (true) {
                val message = queue.take()
                header.clear()
                header.putShort(message.type.toShort()).putShort(0).putShort(message.refersTo.toShort())
                    .putTime(micros(System.nanoTime())).putTime(0).putInt(message.payload.size)
                output.write(header.array())
                output.write(message.payload)
                output.flush() // now, so a Time reply's send time is when it leaves
            }
        }
    }

    private fun report() = changed(clients.mapNotNull { client -> client.hello?.let { client.address to it } })

    private class Message(val type: Int, val refersTo: Int, val payload: ByteArray)

    companion object {
        const val PORT = 1704
        const val CONTROL_PORT = 1705
        /**
         * Clients play a chunk this long after its timestamp. ponytail: it only has to exceed the
         * real lead (~1 s, the AAudio buffer); an ESP32 sizes its queue from it, so it needs
         * PSRAM (a WROOM module holds ~758 ms).
         */
        const val BUFFER_MS = 1500L
        const val CODEC_HEADER = 1
        const val WIRE_CHUNK = 2
        const val SERVER_SETTINGS = 3
        const val TIME = 4
        const val HELLO = 5
        const val HEADER_BYTES = 26
        private const val FRAME_BYTES = 4 // 16-bit stereo, all the AAudio backend plays
        private const val DEFAULT_RATE = 44100 // AirPlay's; another rate sends a new codec header
        private const val MAX_JUMP_NS = 1_000_000L // stuffing moves a buffer by one frame, ~23 us
        private const val QUEUE_LENGTH = 50 // ~1 s of chunks; snapclient reconnects after 2 s without a Time reply
        private const val MAX_PAYLOAD = 1 shl 20

        // Same bufferMs and latency every time: a change would make clients resync.
        private fun settings(volume: Int) = """{"bufferMs":$BUFFER_MS,"latency":0,"muted":false,"volume":$volume}"""
            .toByteArray().let { le(4 + it.size).putInt(it.size).put(it).array() }

        private fun listen(port: Int) = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }

        private fun accept(server: ServerSocket, handle: (Socket) -> Unit) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrElse { return } // closed
                runCatching { handle(socket) }.onFailure { socket.close() }
            }
        }

        /** 20 ms, snapserver's default chunk: an ESP32 reconfigures whenever the size changes. */
        private fun chunkBytes(rate: Int) = rate / 50 * FRAME_BYTES

        private fun nanos(frames: Int, rate: Int) = frames * 1_000_000_000L / rate

        private fun micros(nanos: Long) = Math.floorDiv(nanos, 1000L)

        private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        /** Snapcast's tv: seconds and microseconds, microseconds always positive. */
        private fun ByteBuffer.putTime(micros: Long): ByteBuffer =
            putInt(Math.floorDiv(micros, 1_000_000L).toInt()).putInt(Math.floorMod(micros, 1_000_000L).toInt())

        /** "pcm" and a RIFF WAVE header: rate, 2 channels, 16 bits. */
        private fun codecHeader(rate: Int): ByteArray {
            val wave = le(44).put("RIFF".toByteArray()).putInt(36).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * FRAME_BYTES)
                .putShort(FRAME_BYTES.toShort()).putShort(16).put("data".toByteArray()).putInt(0)
            return le(4 + 3 + 4 + 44).putInt(3).put("pcm".toByteArray()).putInt(44).put(wave.array()).array()
        }
    }
}
