package com.hkfuertes.shairport.satellites

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The Google Cast wire, as AirConnect's aircast speaks it: TLS to the device's port 8009 (no
 * client certificate; the devices' own certificates are self-signed), then CastMessage protobufs,
 * each a 4-byte big-endian length then the message: 1 protocol version (0), 2 source, 3
 * destination, 4 namespace, 5 payload type (0: string), 6 the payload, JSON.
 */
internal object Cast {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"

    private val factory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
    }.socketFactory

    fun socket(address: String, port: Int, timeout: Int = 5000): SSLSocket =
        (factory.createSocket() as SSLSocket).apply {
            connect(InetSocketAddress(address, port), timeout)
            startHandshake()
        }

    fun send(out: OutputStream, namespace: String, destination: String?, json: String) {
        out.write(frame(namespace, destination, json))
        out.flush()
    }

    /** One CastMessage: its namespace and JSON payload. */
    fun receive(input: InputStream): Pair<String, String> {
        val size = ByteArray(4)
        readFully(input, size)
        val body = ByteArray((size[0].toInt() and 0xff shl 24) or (size[1].toInt() and 0xff shl 16) or
            (size[2].toInt() and 0xff shl 8) or (size[3].toInt() and 0xff))
        readFully(input, body)
        var namespace = ""
        var payload = ""
        var i = 0
        while (i < body.size) {
            val tag = body[i++].toInt() and 0xff
            val field = tag ushr 3
            when (tag and 7) {
                0 -> while (body[i++].toInt() and 0x80 != 0) Unit // a varint: the version, the type
                2 -> {
                    var length = 0
                    var shift = 0
                    while (true) {
                        val byte = body[i++].toInt()
                        length = length or ((byte and 0x7f) shl shift)
                        if (byte and 0x80 == 0) break
                        shift += 7
                    }
                    when (field) {
                        4 -> namespace = String(body, i, length)
                        6 -> payload = String(body, i, length)
                    }
                    i += length
                }
                else -> throw IOException("CastMessage: wire type ${tag and 7}")
            }
        }
        return namespace to payload
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("CastMessage: cut short")
            read += n
        }
    }

    internal fun frame(namespace: String, destination: String?, json: String): ByteArray {
        val body = ByteArrayOutputStream()
        fun string(field: Int, value: String) {
            val bytes = value.toByteArray()
            body.write(field shl 3 or 2)
            var length = bytes.size
            while (length >= 0x80) { // protobuf varint
                body.write(length and 0x7f or 0x80)
                length = length ushr 7
            }
            body.write(length)
            body.write(bytes)
        }
        body.write(0x08); body.write(0) // protocol_version: CASTV2_1_0
        string(2, "sender-0")
        string(3, destination ?: "receiver-0") // always: the devices close what skips it
        string(4, namespace)
        body.write(0x28); body.write(0) // payload_type: STRING
        string(6, json)
        return ByteBuffer.allocate(4 + body.size()).putInt(body.size()).put(body.toByteArray()).array()
    }
}
