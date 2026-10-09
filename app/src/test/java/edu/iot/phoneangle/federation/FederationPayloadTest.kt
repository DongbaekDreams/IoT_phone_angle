package edu.iot.phoneangle.federation

import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.SoftmaxClassifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FederationPayloadTest {

    private fun update(client: String, round: Int, samples: Int, bias0: Float, config: SensorConfig = SensorConfig.ALL) =
        ModelPayload(
            type = PayloadType.UPDATE,
            clientId = client,
            clientRole = "CLIENT_A",
            round = round,
            sampleCount = samples,
            poseCounts = mapOf("screen_up" to samples),
            sensorConfig = config,
            model = SoftmaxClassifier().also { it.bias[0] = bias0 },
        )

    private fun result() = ResultPayload(
        clientId = "client-a",
        clientRole = "CLIENT_A",
        modelKind = "global",
        round = 1,
        sensorConfig = SensorConfig.ALL,
        evalCount = 4,
        accuracy = 0.75f,
        macroF1 = 0.7f,
        perClass = listOf(ClassScore("screen_up", 1f, 1f, 2)),
        classes = listOf("screen_up"),
        confusion = arrayOf(intArrayOf(2)),
        sessionTags = listOf("room-1"),
    )

    // --- privacy ----------------------------------------------------------------------

    @Test
    fun modelPayloadJson_onlyContainsAllowlistedKeys() {
        val json = PayloadCodec.toJson(update("client-a", 1, 10, 1f))
        val extra = json.keys().asSequence().filter { it !in PrivacyGuard.MODEL_KEYS }.toList()
        assertTrue("unexpected keys: $extra", extra.isEmpty())
    }

    @Test
    fun resultPayloadJson_onlyContainsAllowlistedKeys() {
        val json = PayloadCodec.toJson(result())
        val extra = json.keys().asSequence().filter { it !in PrivacyGuard.RESULT_KEYS }.toList()
        assertTrue("unexpected keys: $extra", extra.isEmpty())
    }

    @Test
    fun guardRejectsRawSensorKeys() {
        for (bad in listOf("samples", "trial", "gravity", "accel", "gyro", "features", "rotationVector", "timestampNs")) {
            val obj = JSONObject().put("schema", 1).put(bad, "x")
            try {
                PrivacyGuard.check(obj, PrivacyGuard.MODEL_KEYS + bad)
                fail("guard accepted forbidden key '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun guardRejectsUnknownKeys() {
        val obj = JSONObject().put("schema", 1).put("somethingNew", 1)
        try {
            PrivacyGuard.check(obj, PrivacyGuard.MODEL_KEYS)
            fail("guard accepted a non-allowlisted key")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /** Structural check: payload types have no field that could carry a Trial or SensorSample. */
    @Test
    fun payloadTypesCannotHoldTrialsOrSamples() {
        val forbidden = setOf(Trial::class.java, SensorSample::class.java)
        for (cls in listOf(ModelPayload::class.java, ResultPayload::class.java, ClassScore::class.java)) {
            for (field in cls.declaredFields) {
                assertFalse("${cls.simpleName}.${field.name}", field.type in forbidden)
                val generic = field.genericType.toString()
                assertFalse("${cls.simpleName}.${field.name}: $generic", forbidden.any { generic.contains(it.name) })
            }
        }
    }

    // --- codec ------------------------------------------------------------------------

    @Test
    fun modelPayload_roundTrips() {
        val original = update("client-a", 2, 7, 3.5f)
        val back = PayloadCodec.modelFromJson(PayloadCodec.toJson(original))
        assertEquals(original.clientId, back.clientId)
        assertEquals(2, back.round)
        assertEquals(7, back.sampleCount)
        assertEquals(PayloadType.UPDATE, back.type)
        assertEquals(3.5f, back.model.bias[0], 1e-5f)
        assertEquals(mapOf("screen_up" to 7), back.poseCounts)
    }

    // --- aggregation ------------------------------------------------------------------

    @Test
    fun aggregator_weightsBySampleCount() {
        val g = Aggregator.aggregate(
            1,
            listOf(update("client-a", 1, 1, 0f), update("client-b", 1, 3, 8f)),
        )
        assertEquals(PayloadType.GLOBAL, g.type)
        assertEquals(1, g.round)
        assertEquals(6f, g.model.bias[0], 1e-5f)
        assertEquals(4, g.sampleCount)
        assertEquals(listOf("client-a", "client-b"), g.contributors)
        PayloadCodec.toJson(g) // must pass the privacy guard
    }

    @Test
    fun aggregator_handlesFourPhones() {
        val g = Aggregator.aggregate(
            1,
            listOf(
                update("client-a-aaaa", 1, 10, 0f),
                update("client-a-bbbb", 1, 10, 4f),
                update("client-b-cccc", 1, 20, 8f),
                update("client-b-dddd", 1, 40, 12f),
            ),
        )
        // (0*10 + 4*10 + 8*20 + 12*40) / 80 = 8.5
        assertEquals(8.5f, g.model.bias[0], 1e-4f)
        assertEquals(80, g.sampleCount)
        assertEquals(4, g.contributors.size)
    }

    @Test
    fun aggregator_rejectsIncompatibleSensorConfig() {
        try {
            Aggregator.aggregate(
                1,
                listOf(
                    update("client-a", 1, 1, 0f, SensorConfig.ALL),
                    update("client-b", 1, 1, 0f, SensorConfig.GRAVITY_ONLY),
                ),
            )
            fail("should reject mismatched sensorConfig")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensorConfig"))
        }
    }

    @Test
    fun aggregator_rejectsWrongRound() {
        try {
            Aggregator.aggregate(2, listOf(update("client-a", 1, 1, 0f)))
            fail("should reject wrong round")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
