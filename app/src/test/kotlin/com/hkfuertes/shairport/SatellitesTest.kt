package com.hkfuertes.shairport

import com.hkfuertes.shairport.Satellites.Companion.BUFFER_MS
import com.hkfuertes.shairport.Satellites.Companion.CODEC_HEADER
import com.hkfuertes.shairport.Satellites.Companion.HEADER_BYTES
import com.hkfuertes.shairport.Satellites.Companion.HELLO
import com.hkfuertes.shairport.Satellites.Companion.SERVER_SETTINGS
import com.hkfuertes.shairport.Satellites.Companion.TIME
import com.hkfuertes.shairport.Satellites.Companion.WIRE_CHUNK
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake snapclient against [Satellites]: what a real one needs, in the order it needs it. */
class SatellitesTest {
    private class Message(val type: Int, val refersTo: Int, val sent: Long, val payload: ByteBuffer)

    @Test
    fun servesASnapclient() {
        val reports = LinkedBlockingQueue<List<Pair<String, String>>>()
        Satellites(port = 0, controlPort = 0, changed = { reports.put(it) }).use { satellites ->
            Socket("127.0.0.1", satellites.port).use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // Hello: server settings as its answer, then the codec header.
                output.write(message(HELLO, id = 7, sent = 0, payload = withLength("""{"HostName":"test"}""")))
                val settings = read(input)
                assertEquals(SERVER_SETTINGS, settings.type)
                assertEquals(7, settings.refersTo)
                assertTrue(text(settings.payload).contains("\"bufferMs\":$BUFFER_MS"))
                assertTrue(text(settings.payload).contains("\"volume\":100"))
                val codec = read(input)
                assertEquals(CODEC_HEADER, codec.type)
                assertEquals("pcm", String(ByteArray(codec.payload.getInt(0)).also { codec.payload.position(4); codec.payload.get(it) }))
                assertEquals(44100, codec.payload.getInt(4 + 3 + 4 + 24)) // RIFF header's sample rate
                assertEquals(listOf("127.0.0.1" to """{"HostName":"test"}"""), reports.poll(5, TimeUnit.SECONDS))

                // Time: latency = our receive time - the client's send time (its clock 5 s behind).
                val clientSent = System.nanoTime() / 1000 - 5_000_000
                output.write(message(TIME, id = 8, sent = clientSent, payload = ByteArray(8)))
                val time = read(input)
                assertEquals(TIME, time.type)
                assertEquals(8, time.refersTo)
                val latency = time.payload.getInt(0) * 1_000_000L + time.payload.getInt(4)
                assertTrue("latency $latency", abs(latency - 5_000_000) < 500_000)
                assertTrue(abs(time.sent - System.nanoTime() / 1000) < 500_000) // our clock

                // Buffers of Shairport's sizes, one stuffed (353): the first only sets the timeline,
                // the rest go out as 20 ms chunks heard at their buffer's time.
                var heardAt = System.nanoTime() + 400_000_000
                val firstSent = heardAt + 352 * 1_000_000_000L / 44100
                for ((index, frames) in listOf(352, 352, 353, 352, 352, 352, 352, 352).withIndex()) {
                    satellites.audio(ByteArray(frames * 4) { (index + 1).toByte() }, 44100, heardAt)
                    heardAt += 352 * 1_000_000_000L / 44100 // the stuffed frame doesn't move the timeline
                }
                val first = read(input)
                assertEquals(WIRE_CHUNK, first.type)
                assertEquals(firstSent / 1000 - BUFFER_MS * 1000, timestamp(first))
                assertEquals(882 * 4, first.payload.getInt(8))
                assertEquals(2, first.payload.get(12).toInt()) // the second buffer's frames...
                assertEquals(3, first.payload.get(12 + 352 * 4).toInt()) // ...then the third's
                assertNear(timestamp(first) + 20_000, timestamp(read(input)), 50) // 2 x 882 of 7 x 352

                // A jump (pause, skip): the partial chunk is dropped and the new timeline starts.
                heardAt += 1_000_000_000
                val resumed = heardAt + 352 * 1_000_000_000L / 44100
                repeat(4) {
                    satellites.audio(ByteArray(352 * 4), 44100, heardAt)
                    heardAt += 352 * 1_000_000_000L / 44100
                }
                assertEquals(resumed / 1000 - BUFFER_MS * 1000, timestamp(read(input)))

                // Flush also drops our partial chunk, even if the next timestamps are contiguous.
                satellites.audio(ByteArray(0), 0, 0)
                assertEquals(CODEC_HEADER, read(input).type)
                val afterFlush = heardAt + 352 * 1_000_000_000L / 44100
                repeat(4) {
                    satellites.audio(ByteArray(352 * 4) { 9 }, 44100, heardAt)
                    heardAt += 352 * 1_000_000_000L / 44100
                }
                val fresh = read(input)
                assertEquals(WIRE_CHUNK, fresh.type)
                assertTrue("pre-flush PCM survived", fresh.payload.array().drop(12).all { it == 9.toByte() })
                assertEquals(afterFlush / 1000 - BUFFER_MS * 1000, timestamp(fresh))

                // Linked volume: new settings, unasked, with the same buffer (no resync).
                satellites.volume = 40
                val pushed = read(input)
                assertEquals(SERVER_SETTINGS, pushed.type)
                assertTrue(text(pushed.payload).contains("\"volume\":40"))
                assertTrue(text(pushed.payload).contains("\"bufferMs\":$BUFFER_MS"))
            }
            assertEquals(emptyList<Pair<String, String>>(), reports.poll(5, TimeUnit.SECONDS)) // gone

            // Snapdroid's control connection stays open, unanswered.
            Socket("127.0.0.1", satellites.controlPort).use { control ->
                control.soTimeout = 300
                assertTrue(runCatching { control.getInputStream().read() }.exceptionOrNull() is SocketTimeoutException)
            }
        }
    }

    @Test
    fun reportsCannotOvertakeEachOther() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val reports = LinkedBlockingQueue<Int>()
        Satellites(port = 0, controlPort = 0, changed = { snapshot ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            reports.put(snapshot.size)
        }).use { satellites ->
            try {
                Socket("127.0.0.1", satellites.port).use { a ->
                    a.soTimeout = 5000
                    a.getOutputStream().write(message(HELLO, 1, 0, withLength("""{"HostName":"A"}""")))
                    val inputA = DataInputStream(a.getInputStream())
                    assertEquals(SERVER_SETTINGS, read(inputA).type)
                    assertEquals(CODEC_HEADER, read(inputA).type)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    Socket("127.0.0.1", satellites.port).use { b ->
                        b.soTimeout = 5000
                        b.getOutputStream().write(message(HELLO, 2, 0, withLength("""{"HostName":"B"}""")))
                        val first = reports.poll(250, TimeUnit.MILLISECONDS)
                        release.countDown()
                        val inputB = DataInputStream(b.getInputStream())
                        assertEquals(SERVER_SETTINGS, read(inputB).type)
                        assertEquals(CODEC_HEADER, read(inputB).type)
                        val delivered = mutableListOf<Int>()
                        first?.let { delivered += it }
                        while (delivered.size < 2) delivered += checkNotNull(reports.poll(5, TimeUnit.SECONDS))
                        assertEquals(listOf(1, 2), delivered)
                    }
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun failedControlBindReleasesAudioPort() {
        ServerSocket(0).use { occupied ->
            val port = ServerSocket(0).use { it.localPort }
            assertTrue(runCatching { Satellites(port, occupied.localPort).close() }.isFailure)
            ServerSocket(port).use { assertEquals(port, it.localPort) }
        }
    }

    @Test
    fun onlyConnectionsWithoutHelloTimeOut() {
        Satellites(port = 0, controlPort = 0).use { satellites ->
            Socket("127.0.0.1", satellites.port).use { ready ->
                ready.soTimeout = 8000
                val output = ready.getOutputStream()
                val input = DataInputStream(ready.getInputStream())
                output.write(message(HELLO, 1, 0, withLength("""{"HostName":"idle ESP32"}""")))
                assertEquals(SERVER_SETTINGS, read(input).type)
                assertEquals(CODEC_HEADER, read(input).type)
                Socket("127.0.0.1", satellites.port).use { pending ->
                    pending.soTimeout = 8000
                    assertEquals("unfinished handshake stays open", -1, pending.getInputStream().read())
                }
                // The handshaken client has also been idle beyond the timeout: it must still work.
                output.write(message(TIME, 2, 0, ByteArray(8)))
                assertEquals(TIME, read(input).type)
            }
        }
    }

    private fun assertNear(expected: Long, actual: Long, delta: Long) =
        assertTrue("$actual not within $delta of $expected", abs(actual - expected) <= delta)

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun withLength(json: String) = json.toByteArray().let { le(4 + it.size).putInt(it.size).put(it).array() }

    private fun text(payload: ByteBuffer) = String(payload.array(), 4, payload.getInt(0))

    private fun timestamp(chunk: Message) = chunk.payload.getInt(0) * 1_000_000L + chunk.payload.getInt(4)

    private fun message(type: Int, id: Int, sent: Long, payload: ByteArray): ByteArray =
        le(HEADER_BYTES + payload.size).putShort(type.toShort()).putShort(id.toShort()).putShort(0)
            .putInt((sent / 1_000_000).toInt()).putInt((sent % 1_000_000).toInt()).putInt(0).putInt(0)
            .putInt(payload.size).put(payload).array()

    private fun read(input: DataInputStream): Message {
        val header = le(HEADER_BYTES).also { input.readFully(it.array()) }
        val payload = le(header.getInt(22)).also { input.readFully(it.array()) }
        val sent = header.getInt(6) * 1_000_000L + header.getInt(10)
        return Message(header.getShort(0).toInt(), header.getShort(4).toInt(), sent, payload)
    }
}
