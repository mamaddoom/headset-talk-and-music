package com.mamaddoom.ridetalk.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional group code: every packet is sealed with AES-128-GCM under a key
 * derived from the code, so only riders with the same code can hear each
 * other (and nobody else on the hotspot can listen in).
 *
 * The 12-byte header stays readable but is authenticated; the type byte gets
 * [ENCRYPTED_FLAG]. The nonce is sender id + type + sequence number, unique
 * per packet because every stream counts its own sequence.
 */
class PacketCrypto(
    code: String,
    /** Hotspot: light KDF (the radio range is the first barrier). Online: much stronger. */
    iterations: Int = 4000,
    salt: String = "RideTalk-group-v1",
) {
    private val raw = deriveKey(code, iterations, salt)
    private val key = SecretKeySpec(raw, "AES")

    /**
     * A name for the group's relay channel that reveals nothing about the code
     * (one-way from the derived key), so strangers on a public broker can't
     * find the channel, and finding it gives no shortcut to the code.
     */
    val channelId: String by lazy {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(raw, "HmacSHA256"))
        mac.doFinal("relay-channel".toByteArray()).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(32)
    }

    // Cipher objects are not thread-safe; every sending/receiving thread gets its own.
    private val ciphers = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    }
    private val nonces = object : ThreadLocal<ByteArray>() {
        override fun initialValue() = ByteArray(NONCE)
    }

    /** Seals [len] bytes of a plain packet from [src] into [dst]; returns the sealed length. */
    fun seal(src: ByteArray, len: Int, dst: ByteArray): Int {
        System.arraycopy(src, 0, dst, 0, Protocol.HEADER)
        dst[3] = (dst[3].toInt() or ENCRYPTED_FLAG).toByte()
        val c = ciphers.get()!!
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(dst)))
        c.updateAAD(dst, 0, Protocol.HEADER)
        val n = c.doFinal(src, Protocol.HEADER, len - Protocol.HEADER, dst, Protocol.HEADER)
        return Protocol.HEADER + n
    }

    /**
     * Opens a sealed packet in [buf] (in place: plain packet written back to
     * the same array). Returns the plain length, or -1 if it is not ours.
     */
    fun open(buf: ByteArray, len: Int, scratch: ByteArray): Int {
        if (len < Protocol.HEADER + TAG_BITS / 8) return -1
        if (buf[3].toInt() and ENCRYPTED_FLAG == 0) return -1
        return try {
            val c = ciphers.get()!!
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(buf)))
            c.updateAAD(buf, 0, Protocol.HEADER)
            val n = c.doFinal(buf, Protocol.HEADER, len - Protocol.HEADER, scratch, 0)
            System.arraycopy(scratch, 0, buf, Protocol.HEADER, n)
            buf[3] = (buf[3].toInt() and ENCRYPTED_FLAG.inv()).toByte()
            Protocol.HEADER + n
        } catch (_: Exception) {
            -1 // wrong group code, or tampered
        }
    }

    private fun nonce(header: ByteArray): ByteArray {
        val n = nonces.get()!!
        val b = ByteBuffer.wrap(n).order(ByteOrder.LITTLE_ENDIAN)
        b.put(header, 4, 4) // sender id
        b.put((header[3].toInt() and ENCRYPTED_FLAG.inv()).toByte()) // stream type
        b.put(0).put(0).put(0)
        b.put(header, 8, 4) // sequence
        return n
    }

    companion object {
        const val ENCRYPTED_FLAG = 0x80
        const val OVERHEAD = 16
        private const val TAG_BITS = 128
        private const val NONCE = 12

        /** Online groups: the code travels past public servers, so guessing it must be slow. */
        const val ONLINE_ITERATIONS = 200_000
        const val ONLINE_SALT = "HelmetMedia-online-v1"

        private fun deriveKey(code: String, iterations: Int, salt: String): ByteArray {
            val spec = PBEKeySpec(code.trim().toCharArray(), salt.toByteArray(), iterations, 128)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }

        /** Whether a raw datagram carries the encrypted flag. */
        fun isSealed(buf: ByteArray, len: Int) = len > 3 && buf[3].toInt() and ENCRYPTED_FLAG != 0
    }
}
