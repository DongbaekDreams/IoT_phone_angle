package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.SoftmaxClassifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

/**
 * Wire-format check: the Kotlin LAN client against the real Python server, two clients, two rounds.
 * Skipped when python is not installed.
 */
class LanServerRoundTripTest {

    private var process: Process? = null
    private lateinit var root: File
    private lateinit var transportA: LanServerTransport
    private lateinit var transportB: LanServerTransport

    @Before
    fun startServer() {
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "server/fedavg_server.py") }
            .firstOrNull { it.exists() }
        assumeTrue("server script not found", script != null)

        val port = ServerSocket(0).use { it.localPort }
        root = Files.createTempDirectory("fed-test").toFile()
        val log = File(root, "server.log")
        val started: Process? = listOf("python", "python3").firstNotNullOfOrNull { exe: String ->
            runCatching {
                ProcessBuilder(exe, script!!.path, "--host", "127.0.0.1", "--port", port.toString(), "--root", root.path)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
            }.getOrNull()
        }
        assumeTrue("python not available", started != null)
        process = started

        val base = "http://127.0.0.1:$port"
        transportA = LanServerTransport(base)
        transportB = LanServerTransport(base)
        var ready = false
        repeat(50) {
            if (!ready) {
                ready = runCatching { URL("$base/health").readText() }.isSuccess
                if (!ready) Thread.sleep(100)
            }
        }
        assumeTrue("server did not start", ready)
    }

    @After
    fun stopServer() {
        process?.destroyForcibly()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun payload(type: PayloadType, client: String, round: Int, samples: Int, bias0: Float) = ModelPayload(
        type = type,
        clientId = client,
        clientRole = "CLIENT_A",
        round = round,
        sampleCount = samples,
        poseCounts = mapOf("screen_up" to samples),
        sensorConfig = SensorConfig.ALL,
        model = SoftmaxClassifier().also { it.bias[0] = bias0 },
    )

    @Test
    fun twoClientsTwoRounds() {
        assertEquals(0, transportA.status().latestGlobalRound)
        assertEquals(null, transportA.fetchGlobal())

        transportA.putBaseline(payload(PayloadType.BASELINE, "client-a", 0, 5, 1f))
        transportA.submitUpdate(payload(PayloadType.UPDATE, "client-a", 1, 1, 0f))
        assertEquals(listOf("client-a"), transportB.status().pendingClients)
        transportB.submitUpdate(payload(PayloadType.UPDATE, "client-b", 1, 3, 8f))

        val global = transportA.aggregate()
        assertEquals(1, global.round)
        assertEquals(6f, global.model.bias[0], 1e-4f)
        assertEquals(listOf("client-a", "client-b"), global.contributors)

        val fetched = transportB.fetchGlobal()
        assertNotNull(fetched)
        assertEquals(6f, fetched!!.model.bias[0], 1e-4f)
        assertEquals(1, transportB.status().latestGlobalRound)
        assertEquals(2, transportB.status().pendingRound)

        // A stale round-1 update must be rejected with a readable error.
        try {
            transportA.submitUpdate(payload(PayloadType.UPDATE, "client-a", 1, 1, 0f))
            fail("stale update accepted")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("round"))
        }
        transportA.submitUpdate(payload(PayloadType.UPDATE, "client-a", 2, 1, 1f))
        assertEquals(listOf("client-a"), transportB.status().pendingClients)

        val baselines = transportB.fetchBaselines()
        assertEquals(listOf("client-a"), baselines.map { it.clientId })
        assertEquals(1f, baselines.first().model.bias[0], 1e-4f)

        transportB.putResult(
            ResultPayload(
                clientId = "client-b",
                clientRole = "CLIENT_B",
                modelKind = "global",
                round = 1,
                sensorConfig = SensorConfig.ALL,
                evalCount = 2,
                accuracy = 0.5f,
                macroF1 = 0.4f,
                perClass = listOf(ClassScore("screen_up", 0.5f, 0.4f, 2)),
                classes = listOf("screen_up"),
                confusion = arrayOf(intArrayOf(1)),
            ),
        )
        assertTrue(File(root, "results/client-b").listFiles()!!.isNotEmpty())
        assertTrue(File(root, "federation/rounds/0001/global.json").exists())
    }
}
