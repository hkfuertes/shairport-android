package com.hkfuertes.shairport.satellites

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream the cast devices pull, and the wire to them — no device needed for these. */
class SatellitesTest {
    private val log = mutableListOf<String>()
    private val logs: (String) -> Unit = { log.add(it) }

    /** A GET of [path], its status line and headers, then [bytes] of its body. */
    private fun get(satellites: Satellites, path: String, bytes: Int, range: String? = null): Pair<String, ByteArray> {
        Socket("127.0.0.1", satellites.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: shairport\r\n".toByteArray())
                range?.let { write("Range: bytes=$it-\r\n".toByteArray()) }
                write("\r\n".toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val head = ByteArrayOutputStream()
            while (!head.toString().endsWith("\r\n\r\n")) head.write(input.read())
            val body = ByteArray(bytes)
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                assertTrue("stream cut at $read of $bytes", n >= 0)
                read += n
            }
            return head.toString() to body
        }
    }

    private fun wav(pcm: ByteArray, rate: Int = 44100) = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).run {
        put("RIFF".toByteArray()); putInt(44 + pcm.size - 8)
        put("WAVE".toByteArray()); put("fmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(pcm.size)
        put(pcm)
        array()
    }

    @Test
    fun servesTheStreamToAPuller() {
        Satellites(port = 0, log = logs).use { satellites ->
            val pcm = ByteArray(44100 * 4) { (it * 31).toByte() } // one second
            satellites.audio(pcm, 44100, 0)
            val quarter = 44100 // a quarter second, in bytes
            val (head, body) = get(satellites, "/stream.wav?g=1", 44 + quarter)
            assertTrue(head, head.startsWith("HTTP/1.0 200 OK") && head.contains("audio/wav"))
            assertTrue(log.joinToString(), log.any { it.contains("Stream 1 at 44100") })
            assertEquals("RIFF", String(body, 0, 4))
            assertEquals(44100, ByteBuffer.wrap(body, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int) // the WAV's rate
            assertEquals(-1, ByteBuffer.wrap(body, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int) // endless
            assertArrayEquals(pcm.copyOfRange(pcm.size - quarter, pcm.size), body.copyOfRange(44, body.size))
            satellites.volume = 30 // no device yet: only that it holds
        }
    }

    @Test
    fun aRangeGetsTheWholeStream() { // ranges ignored: 200 always, the Chrome-stacked devices' way
        Satellites(port = 0, log = logs).use { satellites ->
            val pcm = ByteArray(44100 * 4) { (it * 7).toByte() }
            satellites.audio(pcm, 44100, 0)
            val quarter = 44100
            val (head, body) = get(satellites, "/stream.wav?g=1&n=1", quarter, range = "0-")
            assertTrue(head, head.startsWith("HTTP/1.0 200 OK"))
            assertEquals("RIFF", String(body, 0, 4)) // the stream, header first: not a partial
        }
    }

    @Test
    fun aRateChangeServesANewStream() {
        Satellites(port = 0, log = logs).use { satellites ->
            satellites.audio(ByteArray(88200), 44100, 0)
            satellites.audio(ByteArray(48000 * 4), 48000, 0)
            assertTrue(log.joinToString(), log.any { it.contains("Stream 2 at 48000") })
            val (head, body) = get(satellites, "/stream.wav?g=2", 44 + 48000) // a quarter second at 48 kHz
            assertTrue(head, head.startsWith("HTTP/1.0 200 OK"))
            assertEquals(48000, ByteBuffer.wrap(body, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int) // the WAV's rate
        }
    }

    @Test
    fun theWireRoundTrips() {
        val json = """{"type":"LOAD","requestId":1,"media":{"contentId":"http://192.168.77.237:8098/stream.wav?g=1","streamType":"LIVE","contentType":"audio/wav"}}"""
        val frame = Cast.frame(Cast.MEDIA, "transport-1", json)
        val (namespace, payload) = Cast.receive(ByteArrayInputStream(frame))
        assertEquals(Cast.MEDIA, namespace)
        assertEquals(json, payload)
        assertEquals("""{"type":"PING"}""", Cast.receive(ByteArrayInputStream(Cast.frame(Cast.HEARTBEAT, null, """{"type":"PING"}"""))).second)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("bodies differ: ${expected.size} vs ${actual.size} bytes", expected.contentEquals(actual))
    }
}
