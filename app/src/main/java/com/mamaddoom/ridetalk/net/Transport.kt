package com.mamaddoom.ridetalk.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * UDP transport on the hotspot LAN.
 *
 * Discovery is symmetric: every phone broadcasts a small beacon once a second
 * and anyone that hears it becomes a peer, so it doesn't matter which phone
 * runs the hotspot and no IP has to be typed.
 *
 * Routing detail that matters in practice: a phone joined to a hotspot that
 * has no internet keeps mobile data as its default network, and unbound
 * sockets would then try to reach the other phone over LTE. We therefore
 * request the Wi-Fi network and bind the socket to it. The hotspot phone
 * itself has no Wi-Fi client network; its tethering subnet is reachable from
 * unbound sockets, so it stays unbound.
 */
class Transport(
    context: Context,
    val myId: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the receive thread. [payload] is positioned after the header. */
        fun onPacket(header: Protocol.Header, from: InetSocketAddress, payload: ByteBuffer)
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var needReopen = false
    private var rxThread: Thread? = null

    /** Unicast destinations, maintained by the engine from live peers. */
    @Volatile var peerAddresses: List<InetSocketAddress> = emptyList()

    /** Group code encryption; null = open group. Packets of the other kind are dropped. */
    @Volatile var crypto: PacketCrypto? = null
    private var byeSeq = 0
    private val sealBuf = object : ThreadLocal<ByteArray>() {
        override fun initialValue() = ByteArray(Protocol.MAX_PACKET + PacketCrypto.OVERHEAD + 64)
    }

    val boundToWifi get() = wifiNetwork != null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wifiNetwork != network) {
                wifiNetwork = network
                reopen()
            }
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                reopen()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            // requestNetwork (not just listen) also stops the system from dropping
            // a Wi-Fi network that has no internet access.
            cm.requestNetwork(request, wifiCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestNetwork failed", e)
        }
        rxThread = Thread(::receiveLoop, "rt-net-rx").also { it.start() }
    }

    fun stop() {
        if (!running) return
        // Best effort goodbye so the other side drops us immediately.
        val buf = ByteBuffer.allocate(Protocol.HEADER)
        val len = Protocol.encodeBye(buf, myId, byeSeq++)
        sendToPeers(buf.array(), len)
        running = false
        try {
            cm.unregisterNetworkCallback(wifiCallback)
        } catch (_: RuntimeException) {
        }
        socket?.close()
        rxThread?.join(1000)
        rxThread = null
        socket = null
        wifiNetwork = null
    }

    private fun reopen() {
        needReopen = true
        socket?.close() // unblocks receive(); the loop opens a fresh socket
    }

    private fun openSocket(): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.receiveBufferSize = 256 * 1024
        s.sendBufferSize = 256 * 1024
        s.soTimeout = 1000
        // Expedited forwarding: some Wi-Fi drivers map this to the voice queue (WMM AC_VO).
        try {
            s.trafficClass = 0xB8
        } catch (_: SocketException) {
        }
        wifiNetwork?.let {
            try {
                it.bindSocket(s)
            } catch (e: Exception) {
                Log.w(TAG, "bindSocket failed", e)
            }
        }
        s.bind(InetSocketAddress(Protocol.PORT))
        return s
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val data = ByteArray(Protocol.MAX_PACKET + PacketCrypto.OVERHEAD + 64)
        val scratch = ByteArray(data.size)
        val packet = DatagramPacket(data, data.size)
        val buf = ByteBuffer.wrap(data)
        while (running) {
            val s = socket?.takeUnless { it.isClosed || needReopen } ?: try {
                needReopen = false
                socket?.close()
                openSocket().also { socket = it }
            } catch (e: Exception) {
                Log.w(TAG, "open socket failed", e)
                sleepQuietly(500)
                continue
            }
            try {
                packet.setLength(data.size)
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running && !needReopen) sleepQuietly(100)
                continue
            }
            var length = packet.length
            val c = crypto
            if (c != null) {
                length = c.open(data, length, scratch)
                if (length < 0) continue // other group, or no code
            } else if (PacketCrypto.isSealed(data, length)) {
                continue // a group with a code we don't have
            }
            buf.clear()
            buf.limit(length)
            val header = Protocol.readHeader(buf) ?: continue
            if (header.sender == myId) continue // our own broadcast echo
            val from = packet.socketAddress as? InetSocketAddress ?: continue
            try {
                listener.onPacket(header, from, buf)
            } catch (e: Exception) {
                Log.w(TAG, "packet handler failed", e)
            }
        }
        socket?.close()
    }

    /** Sends to all known peers. Safe to call from any thread. */
    fun sendToPeers(data: ByteArray, len: Int) {
        val s = socket ?: return
        val peers = peerAddresses
        if (peers.isEmpty()) return
        val (out, n) = seal(data, len)
        for (addr in peers) {
            try {
                s.send(DatagramPacket(out, n, addr))
            } catch (_: Exception) {
                // Peer unreachable or socket being swapped; the next frame retries.
            }
        }
    }

    /** Sends a beacon to every reachable broadcast address, the Wi-Fi gateway and known peers. */
    fun sendBeacon(data: ByteArray, len: Int) {
        val s = socket ?: return
        val (out, n) = seal(data, len)
        val targets = LinkedHashSet<InetAddress>()
        targets.addAll(broadcastAddresses())
        gateway()?.let { targets.add(it) }
        try {
            targets.add(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
        } catch (_: Exception) {
        }
        for (addr in targets) {
            try {
                s.send(DatagramPacket(out, n, addr, Protocol.PORT))
            } catch (_: Exception) {
            }
        }
        sendToPeers(data, len)
    }

    /** Applies the group code, if any. Returns the bytes to put on the wire. */
    private fun seal(data: ByteArray, len: Int): Pair<ByteArray, Int> {
        val c = crypto ?: return data to len
        val out = sealBuf.get()!!
        return out to c.seal(data, len, out)
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces() ?: return out) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun gateway(): InetAddress? {
        val net = wifiNetwork ?: return null
        return try {
            cm.getLinkProperties(net)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "Transport"

        /** IPv4 addresses of this phone, for display. */
        fun localAddresses(): List<String> {
            val out = ArrayList<String>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces() ?: return out) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (a in nif.inetAddresses) {
                        if (a is Inet4Address && a.isSiteLocalAddress) out.add(a.hostAddress ?: continue)
                    }
                }
            } catch (_: Exception) {
            }
            return out
        }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
            }
        }
    }
}
