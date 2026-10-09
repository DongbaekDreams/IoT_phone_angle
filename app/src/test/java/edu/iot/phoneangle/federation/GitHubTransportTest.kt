package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.SoftmaxClassifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64

/**
 * GitHubTransport against an in-memory fake of the Contents API (files, sha checks, directory
 * listing), served by a tiny socket HTTP server so no extra dependency is needed.
 */
class GitHubTransportTest {

    private class Stored(val sha: String, val text: String)

    private val files = linkedMapOf<String, Stored>()
    private var shaCounter = 0
    private val requestsWithToken = mutableListOf<String>()
    private lateinit var listener: ServerSocket
    private lateinit var base: String

    @Before
    fun start() {
        listener = ServerSocket(0)
        base = "http://127.0.0.1:${listener.localPort}"
        Thread {
            while (!listener.isClosed) {
                val socket = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    synchronized(this) { serve(socket) }
                } catch (_: Exception) {
                    // ignore broken connections
                } finally {
                    socket.close()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() = listener.close()

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = if (length > 0) String(input.readNBytes(length), Charsets.UTF_8) else ""

        val (code, response) = route(method, target.substringBefore('?'), headers["authorization"], body)
        val bytes = response.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun route(method: String, rawPath: String, auth: String?, body: String): Pair<Int, String> {
        auth?.let { requestsWithToken += it }
        if (auth != "Bearer tok") return 401 to """{"message":"Bad credentials"}"""
        val prefix = "/repos/me/fed"
        if (rawPath == prefix) return 200 to """{"permissions":{"push":true}}"""
        val rel = URLDecoder.decode(rawPath.removePrefix("$prefix/contents/"), "UTF-8")
        return when (method) {
            "GET" -> {
                val file = files[rel]
                if (file != null) {
                    val b64 = Base64.getMimeEncoder().encodeToString(file.text.toByteArray())
                    return 200 to JSONObject().put("type", "file").put("sha", file.sha).put("content", b64).toString()
                }
                val children = files.keys.filter { it.startsWith("$rel/") && !it.removePrefix("$rel/").contains('/') }
                if (children.isEmpty()) return 404 to """{"message":"Not Found"}"""
                val arr = JSONArray()
                children.forEach { arr.put(JSONObject().put("type", "file").put("name", it.substringAfterLast('/'))) }
                200 to arr.toString()
            }
            "PUT" -> {
                val json = JSONObject(body)
                val existing = files[rel]
                if (existing != null && json.optString("sha") != existing.sha) {
                    return 422 to """{"message":"sha mismatch"}"""
                }
                val text = String(Base64.getDecoder().decode(json.getString("content")), Charsets.UTF_8)
                files[rel] = Stored("sha${++shaCounter}", text)
                201 to """{"ok":true}"""
            }
            else -> 405 to "{}"
        }
    }

    private fun transport(token: String = "tok") = GitHubTransport("me/fed", "main", token, apiBase = base)

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
    fun fullRoundThroughContentsApi() {
        val a = transport()
        val b = transport()
        assertTrue(a.ping().contains("write access"))
        assertEquals(0, a.status().latestGlobalRound)
        assertNull(a.fetchGlobal())

        a.putBaseline(payload(PayloadType.BASELINE, "client-a", 0, 5, 1f))
        a.submitUpdate(payload(PayloadType.UPDATE, "client-a", 1, 1, 0f))
        b.submitUpdate(payload(PayloadType.UPDATE, "client-b", 1, 3, 8f))
        assertEquals(listOf("client-a", "client-b"), b.status().pendingClients)

        val global = b.aggregate()
        assertEquals(6f, global.model.bias[0], 1e-4f)
        assertTrue(files.containsKey("federation/rounds/0001/global.json"))
        assertTrue(files.containsKey("federation/rounds/0001/updates/client-a.json"))
        assertEquals(1, JSONObject(files["federation/latest.json"]!!.text).getInt("round"))

        // Pressing aggregate again (another client already did) is a clear no-op error, not corruption.
        try {
            a.aggregate()
            fail("aggregated an empty round")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("already aggregated"))
        }

        assertNotNull(a.fetchGlobal())
        assertEquals(2, a.status().pendingRound)
        assertEquals(listOf("client-a"), b.fetchBaselines().map { it.clientId })

        // Overwriting your own update (same path) works through the sha handshake.
        a.submitUpdate(payload(PayloadType.UPDATE, "client-a", 2, 1, 1f))
        a.submitUpdate(payload(PayloadType.UPDATE, "client-a", 2, 2, 2f))
        assertEquals(2, JSONObject(files["federation/rounds/0002/updates/client-a.json"]!!.text).getInt("sampleCount"))

        a.putResult(
            ResultPayload(
                clientId = "client-a", clientRole = "CLIENT_A", modelKind = "global", round = 1,
                sensorConfig = SensorConfig.ALL, evalCount = 2, accuracy = 0.5f, macroF1 = 0.4f,
                perClass = listOf(ClassScore("screen_up", 0.5f, 0.4f, 2)), classes = listOf("screen_up"),
                confusion = arrayOf(intArrayOf(1)),
            ),
        )
        assertTrue(files.keys.any { it.startsWith("results/client-a/") && it.endsWith("-global.json") })

        // Nothing but federation/ and results/ is ever written, and nothing resembles raw data.
        assertTrue(files.keys.all { it.startsWith("federation/") || it.startsWith("results/") })
        assertTrue(files.values.none { it.text.contains("\"samples\"") || it.text.contains("\"gravity\"") })
    }

    @Test
    fun staleRoundIsRejected() {
        val a = transport()
        try {
            a.submitUpdate(payload(PayloadType.UPDATE, "client-a", 3, 1, 0f))
            fail("accepted wrong round")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("round"))
        }
    }

    @Test
    fun badTokenGivesReadableError() {
        try {
            transport("wrong").status()
            fail("accepted bad token")
        } catch (e: TransportException) {
            assertTrue(e.message!!, e.message!!.contains("401") || e.message!!.contains("token"))
        }
    }

    @Test
    fun missingTokenIsReportedBeforeAnyRequest() {
        try {
            transport("").status()
            fail("accepted blank token")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("token"))
        }
        assertTrue(requestsWithToken.isEmpty())
    }
}
