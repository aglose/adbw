package dev.adbw.agent

import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One inner byte stream (the loopback connection to adbd), resumable across links.
 *
 * Bytes read from adbd are kept in a replay buffer until the bridge ACKs them. When a new link
 * comes up, [attach] replays everything past the peer's rx offset, so the adb session on the host
 * never sees the outage. Mirrors Session in host/adbw.py.
 */
class Session(
    val sid: String,
    private val inner: Socket,
    val target: String,
    private val log: (String) -> Unit,
    private val onFinished: (Session) -> Unit,
) {
    private class Chunk(val seq: Long, val data: ByteArray)

    private val lock = ReentrantLock()
    private val space = lock.newCondition()
    private val chunks = ArrayDeque<Chunk>()
    private var txNext = 0L
    private var txBytes = 0L
    private val innerOut = inner.getOutputStream()

    @Volatile var rx = 0L
        private set
    @Volatile var rxAcked = 0L
        private set
    @Volatile var link: Link? = null
        private set
    @Volatile var localEof = false
        private set
    @Volatile var closed = false
        private set
    @Volatile var bytesUp = 0L
        private set
    @Volatile var bytesDown = 0L
        private set

    val unacked: Long get() = lock.withLock { txBytes }

    fun start() {
        Thread({ pump() }, "adbw-pump-$sid").apply { isDaemon = true }.start()
    }

    /** (Re)bind to a link and replay everything the peer hasn't received. */
    fun attach(l: Link, peerRx: Long) {
        lock.withLock {
            val base = chunks.firstOrNull()?.seq ?: txNext
            if (peerRx < base || peerRx > txNext) {
                throw IOException("cannot resume $sid: peer rx=$peerRx outside [$base,$txNext]")
            }
            ackLocked(peerRx)
            // Partially acked chunks are replayed whole; the receiver skips bytes it already has.
            for (c in chunks) l.sendData(c.seq, c.data)
            link = l
        }
        if (rx != rxAcked) sendAck()
        if (localEof) sendClose(abort = false, reason = "eof")
    }

    fun detach() {
        link = null
    }

    fun onAck(n: Long) = lock.withLock { ackLocked(n) }

    private fun ackLocked(n: Long) {
        while (true) {
            val c = chunks.firstOrNull() ?: break
            if (c.seq + c.data.size > n) break
            chunks.removeFirst()
            txBytes -= c.data.size
        }
        if (txBytes < Proto.TX_CAP) space.signalAll()
    }

    /** Bridge -> adbd. Called on the link reader thread. */
    fun onData(seq: Long, payload: ByteArray, off: Int) {
        val len = payload.size - off
        if (seq > rx) throw IOException("gap in $sid: seq=$seq rx=$rx")
        val skip = (rx - seq).toInt()
        if (skip >= len || closed) return
        try {
            innerOut.write(payload, off + skip, len - skip)
        } catch (e: IOException) {
            innerGone()
            return
        }
        rx += (len - skip)
        bytesDown += (len - skip)
        if (rx - rxAcked >= Proto.ACK_EVERY) sendAck()
    }

    fun sendAck() {
        val l = link ?: return
        val v = rx
        if (l.sendU64(Proto.ACK, v)) rxAcked = v
    }

    /** adbd -> bridge. */
    private fun pump() {
        val buf = ByteArray(Proto.CHUNK)
        val input = inner.getInputStream()
        try {
            while (!closed) {
                lock.withLock { while (txBytes >= Proto.TX_CAP && !closed) space.await() }
                val n = input.read(buf)
                if (n < 0) break
                val data = buf.copyOf(n)
                val (seq, l) = lock.withLock {
                    val s = txNext
                    chunks.addLast(Chunk(s, data))
                    txNext += n
                    txBytes += n
                    s to link
                }
                bytesUp += n
                l?.sendData(seq, data)
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
        innerGone()
    }

    private fun innerGone() {
        if (localEof || closed) return
        localEof = true
        log("session $sid: adbd closed the connection")
        sendClose(abort = false, reason = "eof")
    }

    private fun sendClose(abort: Boolean, reason: String) {
        val l = link ?: return
        val fin = lock.withLock { txNext }
        val msg = JSONObject().put("session", sid).put("final", fin).put("reason", reason)
        if (abort) msg.put("abort", true)
        l.sendJson(Proto.CLOSE, msg)
    }

    /** Peer's end closed. Only finish once every byte before the CLOSE has arrived. */
    fun onClose(final: Long, abort: Boolean) {
        if (abort) {
            localEof = true
            finish()
            return
        }
        if (rx < final) return // data still in flight; the peer replays and re-sends CLOSE
        if (!localEof) {
            localEof = true
            sendClose(abort = false, reason = "peer-closed")
        }
        finish()
    }

    fun abort(reason: String) {
        if (closed) return
        log("session $sid aborted: $reason")
        sendClose(abort = true, reason = reason)
        finish()
    }

    fun finish() {
        if (closed) return
        closed = true
        try {
            inner.close()
        } catch (_: IOException) {
        }
        lock.withLock { space.signalAll() }
        onFinished(this)
    }
}
