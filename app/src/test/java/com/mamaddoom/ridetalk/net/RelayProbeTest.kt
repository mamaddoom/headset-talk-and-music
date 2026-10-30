package com.mamaddoom.ridetalk.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Not a regular unit test: with PROBE_RELAYS=1 (set by CI) it measures every
 * candidate relay server the way the intercom uses it: connect time, then
 * 150 packets of 300 bytes at 50 per second (voice rate) echoed back through
 * the server, giving round-trip times and loss. The scores in RelayServers
 * come from these numbers.
 */
class RelayProbeTest {

    class Result(val s: RelayServers.Server, val connectMs: Long, val medianMs: Long, val p95Ms: Long, val lossPct: Int) {
        val score: Int
            get() {
                if (connectMs < 0) return 0
                var x = 100.0
                x -= minOf(40.0, medianMs / 8.0)
                x -= minOf(25.0, (p95Ms - medianMs).coerceAtLeast(0) / 15.0)
                x -= minOf(30.0, lossPct * 3.0)
                x -= minOf(10.0, connectMs / 300.0)
                return x.toInt().coerceIn(1, 100)
            }
    }

    @Test
    fun probeRelays() {
        assumeTrue(System.getenv("PROBE_RELAYS") == "1")
        val results = RelayServers.CANDIDATES.map { probe(it) }
        val report = buildString {
            for (r in results.sortedByDescending { it.score }) {
                appendLine(
                    "PROBE ${r.s.id.padEnd(12)} ${r.s.host}:${r.s.port}  connect=${r.connectMs}ms  " +
                        "median=${r.medianMs}ms  p95=${r.p95Ms}ms  loss=${r.lossPct}%  score=${r.score}",
                )
            }
        }
        println(report)
        File("build").mkdirs()
        File("build/relay-probe.txt").writeText(report)
    }

    private fun probe(s: RelayServers.Server): Result {
        val m = MqttConnection(s.host, s.port, s.user, s.password)
        val t0 = System.nanoTime()
        try {
            m.connect("hm-probe-" + Random.nextInt(1_000_000), 8000, 30)
        } catch (e: Exception) {
            println("PROBE ${s.id} failed: ${e.javaClass.simpleName}: ${e.message}")
            return Result(s, -1, 0, 0, 100)
        }
        val connectMs = (System.nanoTime() - t0) / 1_000_000
        val topic = "hm-probe/" + Random.nextLong().toULong().toString(16)
        val sent = ConcurrentHashMap<Int, Long>()
        val rtts = java.util.Collections.synchronizedList(ArrayList<Long>())
        m.subscribe(topic)
        val reader = Thread {
            try {
                while (true) {
                    val p = m.read()
                    if (p.type != MqttCodec.PUBLISH) continue
                    val body = MqttCodec.parsePublish(p)?.second ?: continue
                    if (body.size < 4) continue
                    val seq = ByteBuffer.wrap(body).int
                    sent.remove(seq)?.let { rtts += (System.nanoTime() - it) / 1_000_000 }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(500) // let the subscription settle
        val n = 150
        val payload = ByteArray(300)
        for (i in 0 until n) {
            ByteBuffer.wrap(payload).putInt(i)
            sent[i] = System.nanoTime()
            try {
                m.publish(topic, payload)
            } catch (_: Exception) {
                break
            }
            Thread.sleep(20)
        }
        Thread.sleep(2000)
        m.close()
        reader.join(1000)
        val sorted = synchronized(rtts) { rtts.sorted() }
        val loss = ((n - sorted.size) * 100 / n)
        if (sorted.isEmpty()) return Result(s, connectMs, 9999, 9999, 100)
        return Result(s, connectMs, sorted[sorted.size / 2], sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)], loss)
    }
}
