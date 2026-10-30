package com.mamaddoom.ridetalk.net

/**
 * How packets reach the other riders: the hotspot LAN ([Transport]) or a
 * relay on the internet ([OnlineTransport]). The engine sees the same thing
 * either way.
 */
interface Link {
    /** Group code encryption; null = open group (hotspot only). */
    var crypto: PacketCrypto?
    /** Unicast destinations of live peers (hotspot); ignored online. */
    var peerAddresses: List<java.net.InetSocketAddress>
    val boundToWifi: Boolean
    val wifiRssi: Int?
    val wifiMhz: Int?

    fun start()
    fun stop()
    /** Sends to all riders. Safe from any thread, never blocks long. */
    fun sendToPeers(data: ByteArray, len: Int)
    /** Sends the once-a-second "I'm here" beacon. */
    fun sendBeacon(data: ByteArray, len: Int)
}
