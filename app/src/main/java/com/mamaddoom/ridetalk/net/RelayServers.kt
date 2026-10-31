package com.mamaddoom.ridetalk.net

/**
 * Free public relay servers (MQTT brokers) for online mode, best first.
 * No account, TLS with a normal certificate; they only ever see packets that
 * are already end-to-end encrypted with the group code.
 *
 * [score] (0..100) comes from probing them from GitHub's servers (connect
 * time, round-trip time, loss at intercom packet rates); see RelayProbeTest.
 * Every phone uses the same order, so "automatic" lands the whole group on
 * the same server.
 */
object RelayServers {
    class Server(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val score: Int,
        val user: String? = null,
        val password: String? = null,
    )

    // Probed from GitHub's servers on 2026-10-30 (150 packets of 300 bytes at 50/s and 25/s):
    //   HiveMQ    connect ~0.4-0.6 s, round trip ~115 ms, loss 0%   -> 83
    //   shiftr.io connect ~0.4-0.6 s, round trip ~128 ms, loss 0%   -> 82
    // Left out: EMQX (60-80% loss: rate-limited at voice packet rates), Eclipse and
    // MQTT.Cool (unreachable), Mosquitto (refused the TLS session / self-signed CA),
    // flespi (needs an account), HiveMQ Dashboard alias (certificate name mismatch).
    val ALL: List<Server> = listOf(
        Server("hivemq", "HiveMQ", "broker.hivemq.com", 8883, score = 83),
        Server("shiftr", "shiftr.io", "public.cloud.shiftr.io", 8883, score = 82, user = "public", password = "public"),
    ).sortedByDescending { it.score }

    /** Also probed in CI, to replace a weak entry above if one of these does better. */
    val CANDIDATES: List<Server> = ALL + listOf(
        Server("emqx", "EMQX", "broker.emqx.io", 8883, score = 0),
        Server("eclipse", "Eclipse", "mqtt.eclipseprojects.io", 8883, score = 0),
        Server("mosquitto", "Mosquitto", "test.mosquitto.org", 8886, score = 0),
        Server("mqttcool", "MQTT.Cool", "broker.mqtt.cool", 8883, score = 0),
        Server("hivemq-dash", "HiveMQ Dashboard", "broker.mqttdashboard.com", 8883, score = 0),
        Server("mosquitto-8883", "Mosquitto (8883)", "test.mosquitto.org", 8883, score = 0),
        Server("emqx-cn", "EMQX (alt)", "broker-cn.emqx.io", 8883, score = 0),
        Server("flespi", "flespi", "mqtt.flespi.io", 8883, score = 0),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
