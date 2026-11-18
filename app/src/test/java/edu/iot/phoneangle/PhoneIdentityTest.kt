package edu.iot.phoneangle

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.PhonePose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneIdentityTest {

    private val setA = setOf(PhonePose.SCREEN_UP, PhonePose.SCREEN_DOWN, PhonePose.PORTRAIT_UP)

    @Test
    fun sameNationality_classAndPersonalAreComplementary() {
        for (nationality in Nationality.entries) {
            val cls = PhoneIdentity.trainingPoses(nationality, ClientRole.CLIENT_A)
            val personal = PhoneIdentity.trainingPoses(nationality, ClientRole.CLIENT_B)
            assertEquals(3, cls.size)
            assertEquals(3, personal.size)
            assertTrue("$nationality sets overlap", cls.intersect(personal).isEmpty())
            assertEquals(PhonePose.entries.toSet(), cls.union(personal))
        }
    }

    @Test
    fun classPhones_areComplementaryAcrossNationalities() {
        val canada = PhoneIdentity.trainingPoses(Nationality.CANADA, ClientRole.CLIENT_A)
        val peru = PhoneIdentity.trainingPoses(Nationality.PERU, ClientRole.CLIENT_A)
        assertTrue(canada.intersect(peru).isEmpty())
        assertEquals(PhonePose.entries.toSet(), canada.union(peru))
    }

    @Test
    fun canadaClass_trainsOnSetA() {
        assertEquals(setA, PhoneIdentity.trainingPoses(Nationality.CANADA, ClientRole.CLIENT_A))
    }
}
