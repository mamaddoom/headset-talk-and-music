package com.mamaddoom.ridetalk.net

import android.os.Process
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Online mode: every rider connects to the same free public relay server
 * (an MQTT broker, over TLS) and joins one channel named after the group
 * code. All packets are sealed end-to-end with AES-GCM under a key derived
 * from the code before they leave the phone, so the relay only forwards
 * bytes it cannot read, and riders without the code can neither find the
 * channel nor understand it. Online mode therefore requires a group code.
 *
 * Server choice: automatic tries the servers best-first (the same order on
 * every phone, so a group meets on the same one) and falls back to the next
 * when one fails; or the user pins one server by hand.
 */
class OnlineTransport(
    private val myId: Int,
    private val listener: Transport.Listener,
    /** Pinned server id, or null for automatic. Read on every (re)connect. */
    private val pinned: () -> String?,
    private val onStatus: () -> Unit,
) : Link {

    enum class Status { NO_CODE, CONNECTING, CONNECTED, OFFLINE }

    @Volatile var status = Status.CONNECTING
        private set
    @Volatile var server: RelayServers.Server? = null
        private set
    /** Round trip to the relay (ms), from MQTT ping; -1 until measured. */
    @Volatile var rttMs = -1
        private set

    @Volatile override var crypto: PacketCrypto? = null
        set(v) {
            field = v
            conn?.close() // the channel follows the code: reconnect
        }
    override var peerAddresses: List<InetSocketAddress> = emptyList()
    override val boundToWifi = false
    override val wifiRssi: Int? = null
    override val wifiMhz: Int? = null

    @Volatile private var running = false
    @Volatile private var conn: MqttConnection? = null
    @Volatile private var topic = ""
    private var thread: Thread? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    private var byeSeq = 0

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "rt-online").also { it.start() }
    }

    override fun stop() {
        if (!running) return
        val buf = ByteBuffer.allocate(Protocol.HEADER)
        val len = Protocol.encodeBye(buf, myId, byeSeq++)
        sealed(buf.array(), len)?.let { pkt ->
            try {
                conn?.publish(topic, pkt)
            } catch (_: Exception) {
            }
        }
        running = false
        conn?.close()
        thread?.join(2000)
        thread = null
        queue.clear()
    }

    override fun sendToPeers(data: ByteArray, len: Int) {
        if (status != Status.CONNECTED) return
        val pkt = sealed(data, len) ?: return
        queue.offerLast(pkt)
        // A stalled connection must not turn into seconds of delay: keep only the newest.
        while (queue.size > MAX_QUEUE) queue.pollFirst()
    }

    override fun sendBeacon(data: ByteArray, len: Int) = sendToPeers(data, len)

    private fun sealed(data: ByteArray, len: Int): ByteArray? {
        val c = crypto ?: return null
        val out = ByteArray(len + PacketCrypto.OVERHEAD)
        val n = c.seal(data, len, out)
        return if (n == out.size) out else out.copyOf(n)
    }

    // ---------------------------------------------------------------- connection loop

    private fun run() {
        var backoff = 1000L
        while (running) {
            val c = crypto
            if (c == null) {
                setStatus(Status.NO_CODE)
                sleep(500)
                continue
            }
            val order = pinned()?.let { id -> listOfNotNull(RelayServers.byId(id)) }?.ifEmpty { null }
                ?: RelayServers.ALL
            var connected = false
            for (s in order) {
                if (!running || crypto !== c) break
                setStatus(Status.CONNECTING, s)
                val m = MqttConnection(s.host, s.port, s.user, s.password)
                try {
                    m.connect("hm-" + Random.nextLong().toULong().toString(16), CONNECT_TIMEOUT_MS, KEEPALIVE_S)
                } catch (e: Exception) {
                    Log.w(TAG, "${s.host}: ${e.message}")
                    continue
                }
                connected = true
                backoff = 1000L
                session(m, s, c)
                break // after a session ends, start again from the best server
            }
            if (!connected && running) {
                setStatus(Status.OFFLINE)
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
        }
        setStatus(Status.OFFLINE)
    }

    /** One connected period: subscribe, then read until the connection drops. */
    private fun session(m: MqttConnection, s: RelayServers.Server, c: PacketCrypto) {
        topic = "hm1/" + c.channelId
        queue.clear()
        try {
            m.subscribe(topic)
        } catch (_: Exception) {
            m.close()
            return
        }
        conn = m
        rttMs = -1
        setStatus(Status.CONNECTED, s)
        val writer = Thread({ writeLoop(m) }, "rt-online-tx").also { it.start() }
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(Protocol.MAX_PACKET + PacketCrypto.OVERHEAD + 64)
        val scratch = ByteArray(buf.size)
        val bb = ByteBuffer.wrap(buf)
        val from = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)
        try {
            while (running && crypto === c) {
                val p = m.read()
                when (p.type) {
                    MqttCodec.PUBLISH -> {
                        val payload = MqttCodec.parsePublish(p)?.second ?: continue
                        if (payload.size > buf.size) continue
                        System.arraycopy(payload, 0, buf, 0, payload.size)
                        val n = c.open(buf, payload.size, scratch)
                        if (n < 0) continue // not sealed with our code
                        bb.clear()
                        bb.limit(n)
                        val h = Protocol.readHeader(bb) ?: continue
                        if (h.sender == myId) continue // the relay echoes our own packets
                        try {
                            listener.onPacket(h, from, bb)
                        } catch (e: Exception) {
                            Log.w(TAG, "packet handler failed", e)
                        }
                    }
                    MqttCodec.PINGRESP -> if (pingSentAt > 0) {
                        rttMs = (System.currentTimeMillis() - pingSentAt).toInt()
                        pingSentAt = 0
                        onStatus()
                    }
                }
            }
        } catch (_: Exception) {
            // connection dropped
        } finally {
            conn = null
            m.close()
            writer.interrupt()
            writer.join(1000)
            if (running) setStatus(Status.CONNECTING, s)
        }
    }

    @Volatile private var pingSentAt = 0L

    private fun writeLoop(m: MqttConnection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var lastPing = System.currentTimeMillis()
        try {
            while (running && conn === m) {
                val pkt = queue.pollFirst(500, TimeUnit.MILLISECONDS)
                if (pkt != null) m.publish(topic, pkt)
                val now = System.currentTimeMillis()
                if (now - lastPing >= PING_EVERY_MS) {
                    lastPing = now
                    pingSentAt = now
                    m.ping()
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            m.close() // makes the reader end the session
        }
    }

    private fun setStatus(st: Status, s: RelayServers.Server? = server) {
        status = st
        server = s
        onStatus()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val TAG = "OnlineTransport"
        const val CONNECT_TIMEOUT_MS = 6000
        const val KEEPALIVE_S = 30
        const val PING_EVERY_MS = 5000L
        /** About 0.3 s of audio; anything older is dropped rather than delivered late. */
        const val MAX_QUEUE = 16
    }
}
