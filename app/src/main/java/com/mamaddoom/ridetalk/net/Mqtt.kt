package com.mamaddoom.ridetalk.net

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The few MQTT 3.1.1 packets we need (CONNECT, SUBSCRIBE, PUBLISH at QoS 0,
 * PINGREQ, DISCONNECT), built and parsed by hand: no library, no Android
 * dependency, unit-testable on the JVM.
 */
object MqttCodec {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    fun remainingLength(n: Int): ByteArray {
        require(n in 0..268_435_455)
        val out = ArrayList<Byte>(4)
        var x = n
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 0x80
            out += b.toByte()
        } while (x > 0)
        return out.toByteArray()
    }

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    private fun packet(type: Int, flags: Int, body: ByteArray): ByteArray =
        byteArrayOf(((type shl 4) or flags).toByte()) + remainingLength(body.size) + body

    fun connect(clientId: String, keepAliveS: Int, user: String?, password: String?): ByteArray {
        var flags = 0x02 // clean session
        if (user != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        var body = str("MQTT") + byteArrayOf(4, flags.toByte(), (keepAliveS shr 8).toByte(), keepAliveS.toByte()) +
            str(clientId)
        if (user != null) body += str(user)
        if (password != null) body += str(password)
        return packet(CONNECT, 0, body)
    }

    fun subscribe(packetId: Int, topic: String): ByteArray =
        packet(SUBSCRIBE, 0x02, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) + str(topic) + byteArrayOf(0))

    /** QoS 0 publish header for [payloadLen] bytes; the payload itself is written right after. */
    fun publishHeader(topic: String, payloadLen: Int): ByteArray {
        val t = str(topic)
        return byteArrayOf((PUBLISH shl 4).toByte()) + remainingLength(t.size + payloadLen) + t
    }

    val PING: ByteArray = byteArrayOf((PINGREQ shl 4).toByte(), 0)
    val BYE: ByteArray = byteArrayOf((DISCONNECT shl 4).toByte(), 0)

    /** One packet read from the stream: type, flags and body. */
    class Packet(val type: Int, val flags: Int, val body: ByteArray)

    fun read(input: DataInputStream, maxBody: Int = 64 * 1024): Packet {
        val h = input.readUnsignedByte()
        var len = 0
        var mul = 1
        for (i in 0 until 4) {
            val b = input.readUnsignedByte()
            len += (b and 0x7F) * mul
            if (b and 0x80 == 0) break
            mul *= 128
            if (i == 3) throw IOException("bad length")
        }
        if (len > maxBody) throw IOException("packet too large: $len")
        val body = ByteArray(len)
        input.readFully(body)
        return Packet(h shr 4, h and 0x0F, body)
    }

    /** Topic and payload of a QoS 0 publish body. */
    fun parsePublish(p: Packet): Pair<String, ByteArray>? {
        if (p.body.size < 2) return null
        val tl = ((p.body[0].toInt() and 0xFF) shl 8) or (p.body[1].toInt() and 0xFF)
        var off = 2 + tl
        if (off > p.body.size) return null
        val topic = String(p.body, 2, tl, Charsets.UTF_8)
        if ((p.flags shr 1) and 3 != 0) off += 2 // packet id (QoS 1/2; we only subscribe at 0)
        if (off > p.body.size) return null
        return topic to p.body.copyOfRange(off, p.body.size)
    }
}

/**
 * One TLS connection to a public MQTT broker. The certificate and host name
 * are checked like a browser does; everything we publish is additionally
 * end-to-end encrypted by the caller, so the broker only relays opaque bytes.
 */
class MqttConnection(
    val host: String,
    val port: Int,
    private val user: String? = null,
    private val password: String? = null,
) {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private var input: DataInputStream? = null
    private var nextId = 1
    private val writeLock = Any()

    /** Connects and logs in; throws on any failure (DNS, TLS, refused login, timeout). */
    fun connect(clientId: String, timeoutMs: Int = 6000, keepAliveS: Int = 30) {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.soTimeout = timeoutMs
        s.startHandshake()
        socket = s
        out = BufferedOutputStream(s.outputStream, 4096)
        input = DataInputStream(s.inputStream.buffered(16 * 1024))
        writeRaw(MqttCodec.connect(clientId, keepAliveS, user, password))
        val ack = MqttCodec.read(input!!)
        if (ack.type != MqttCodec.CONNACK || ack.body.size < 2 || ack.body[1].toInt() != 0) {
            close()
            throw IOException("broker refused the connection")
        }
        s.soTimeout = 0
    }

    fun subscribe(topic: String) = writeRaw(MqttCodec.subscribe(nextId++, topic))

    fun publish(topic: String, payload: ByteArray, len: Int = payload.size) {
        val o = out ?: throw IOException("not connected")
        synchronized(writeLock) {
            o.write(MqttCodec.publishHeader(topic, len))
            o.write(payload, 0, len)
            o.flush()
        }
    }

    fun ping() = writeRaw(MqttCodec.PING)

    /** Blocks for the next packet. */
    fun read(): MqttCodec.Packet = MqttCodec.read(input ?: throw IOException("not connected"))

    private fun writeRaw(b: ByteArray) {
        val o = out ?: throw IOException("not connected")
        synchronized(writeLock) {
            o.write(b)
            o.flush()
        }
    }

    fun close() {
        try {
            out?.let { synchronized(writeLock) { it.write(MqttCodec.BYE); it.flush() } }
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        input = null
    }
}
