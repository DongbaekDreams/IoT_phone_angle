package edu.iot.phoneangle

import edu.iot.phoneangle.data.ClientRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotBindingTest {

    private val slot = IdentitySlot(Nationality.CANADA, ClientRole.CLIENT_A)

    @Test
    fun sameDevice_byInstallKeyOrHardwareModel() {
        val original = SlotBinding(slot, "key-1", deviceModel = "model-x")
        assertTrue(original.sameDeviceAs(SlotBinding(slot, "key-1")))
        assertTrue(original.sameDeviceAs(SlotBinding(slot, "key-2", deviceModel = "model-x")))
        assertFalse(original.sameDeviceAs(SlotBinding(slot, "key-3", deviceModel = "model-y")))
    }

    @Test
    fun legacyBinding_withoutModel_onlyMatchesKey() {
        val legacy = SlotBinding(slot, "key-1")
        assertFalse(legacy.sameDeviceAs(SlotBinding(slot, "key-2")))
        assertFalse(legacy.sameDeviceAs(SlotBinding(slot, "key-2", deviceModel = "model-x")))
    }

    @Test
    fun codec_roundTripsDeviceModel() {
        val b = SlotBinding(slot, "key-1", createdAtEpochMs = 5, deviceModel = "model-x")
        val json = BindingCodec.toJson(b)
        assertTrue(json.keys().asSequence().all { it in BindingCodec.KEYS })
        assertEquals(b, BindingCodec.fromJson(json))
    }
}
