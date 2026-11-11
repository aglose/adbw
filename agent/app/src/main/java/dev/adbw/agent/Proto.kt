package dev.adbw.agent

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Wire protocol shared with host/adbw.py (keep the two in sync).
 *
 *   frame     = u8 type | u32 length (big endian) | payload
 *   DATA      = u64 seq (stream offset of payload[0]) | bytes
 *   ACK       = u64 rx (bytes received and delivered so far)
 *   PING/PONG = u64 millis
 *   others    = UTF-8 JSON
 *
 * Handshake (mutual, with the per-device key `adbw setup` stored on both sides):
 *   agent  -> HELLO     {v, device, nonce}      (device = this device's id on that computer)
 *   bridge -> CHALLENGE {nonce, mac = HMAC(key, "adbw2-bridge|na|nb")}
 *   agent  -> AUTH      {mac = HMAC(key, "adbw2-agent|na|nb"), name, session, rx, ...}
 *   bridge -> WELCOME   {serial, resume, rx}      (or REJECT {error} at any point)
 */
object Proto {
    const val VERSION = 2
    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val CHALLENGE = 0x03
    const val AUTH = 0x04
    const val REJECT = 0x05
    const val OPEN = 0x10
    const val OPEN_OK = 0x11
    const val OPEN_FAIL = 0x12
    const val DATA = 0x20
    const val ACK = 0x21
    const val CLOSE = 0x22
    const val PING = 0x30
    const val PONG = 0x31
    const val STATUS = 0x40

    const val MAX_PAYLOAD = 1 shl 20
    const val CHUNK = 64 * 1024
    const val TX_CAP = 8L shl 20      // replay buffer cap; reading from adbd pauses beyond it
    const val ACK_EVERY = 1L shl 20
    const val PING_MS = 2_000L
    const val LINK_TIMEOUT_MS = 10_000 // no frame for this long => link dead (session survives)
    const val DEFAULT_PORT = 7100 // 7000 is taken by macOS AirPlay Receiver
}

/** HMAC-SHA256 over "adbw2-<role>|<nonceA>|<nonceB>", hex encoded. */
fun linkMac(keyHex: String, role: String, nonceA: String, nonceB: String): String {
    val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal("adbw2-$role|$nonceA|$nonceB".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class Frame(val type: Int, val payload: ByteArray) {
    fun json() = JSONObject(String(payload, Charsets.UTF_8))
    fun u64(): Long = ByteBuffer.wrap(payload).long
}

/** One outer TCP connection to the bridge. Writes are thread-safe; reads happen on one thread. */
class Link(private val socket: Socket, val label: String) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))

    @Volatile var closed = false
        private set

    @Volatile var rttMs: Long = -1

    fun read(): Frame {
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > Proto.MAX_PAYLOAD) throw IOException("bad frame length $len")
        val p = ByteArray(len)
        input.readFully(p)
        return Frame(type, p)
    }

    fun send(type: Int, payload: ByteArray = ByteArray(0)) = write {
        writeByte(type); writeInt(payload.size); write(payload)
    }

    fun sendJson(type: Int, obj: JSONObject) = send(type, obj.toString().toByteArray())

    fun sendU64(type: Int, v: Long) = write { writeByte(type); writeInt(8); writeLong(v) }

    fun sendData(seq: Long, data: ByteArray) = write {
        writeByte(Proto.DATA); writeInt(8 + data.size); writeLong(seq); write(data)
    }

    private inline fun write(block: DataOutputStream.() -> Unit): Boolean {
        if (closed) return false
        return try {
            synchronized(output) {
                output.block()
                output.flush()
            }
            true
        } catch (e: Exception) { // IOException, or anything else: a dead link is just closed
            close()
            false
        }
    }

    override fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
