package gr.kingpool.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    @Test
    fun volumePlusMinusPlusMinusTriggersAndKeepsOriginalVolume() {
        val r = VolumeGestureRecognizer()
        assertNull(r.onVolumeChange(7, 8, 100))
        assertNull(r.onVolumeChange(8, 7, 400))
        assertNull(r.onVolumeChange(7, 8, 700))
        assertEquals(7, r.onVolumeChange(8, 7, 1000)?.originalVolume)
    }

    @Test
    fun volumeMinusPlusMinusPlusTriggers() {
        val r = VolumeGestureRecognizer()
        r.onVolumeChange(7, 6, 100)
        r.onVolumeChange(6, 7, 400)
        r.onVolumeChange(7, 6, 700)
        assertEquals(7, r.onVolumeChange(6, 7, 1000)?.originalVolume)
    }

    @Test
    fun nonAlternatingAndSlowSequencesDoNotTrigger() {
        val r = VolumeGestureRecognizer()
        r.onVolumeChange(7, 8, 100)
        r.onVolumeChange(8, 9, 300)
        r.onVolumeChange(9, 8, 500)
        assertNull(r.onVolumeChange(8, 9, 700))

        val slow = VolumeGestureRecognizer()
        slow.onVolumeChange(7, 8, 100)
        slow.onVolumeChange(8, 7, 1000)
        slow.onVolumeChange(7, 8, 1300)
        assertNull(slow.onVolumeChange(8, 7, 1600))
    }

    @Test
    fun greekAndGreeklishContactsMatch() {
        assertTrue(ContactVoiceMatcher.isCallCommand("κάλεσε την Klairi"))
        assertEquals("Klairi", ContactVoiceMatcher.extractContactQuery("κάλεσε την Klairi"))
        assertEquals("Palios Giannis", ContactVoiceMatcher.extractContactQuery("πάρε τηλέφωνο τον Palios Giannis"))
        assertEquals("Palios Giannis", ContactVoiceMatcher.extractContactQuery("πάρε τον Palios Giannis τηλέφωνο"))
        assertEquals(100, ContactVoiceMatcher.score("Κλαίρη", "Klairi"))
        assertEquals(100, ContactVoiceMatcher.score("Παλιός Γιάννης", "Palios Giannis"))
        assertFalse(ContactVoiceMatcher.score("Κλαίρη", "Kostas") >= ContactVoiceMatcher.MIN_SCORE)
    }

    @Test
    fun codedAndMisspelledContactsMatchPhoneticallyAnywhere() {
        assertTrue(ContactVoiceMatcher.score("Κλαίρη", "ΜΠ Π Κλαίρη") >= ContactVoiceMatcher.MIN_SCORE)
        assertTrue(ContactVoiceMatcher.score("Κλαίρη", "ΠΕΛ ΜΠ Klairi Χερσόνησος") >= ContactVoiceMatcher.MIN_SCORE)
        assertTrue(ContactVoiceMatcher.score("Κλαίρη", "ΜΠ Π Klery") >= ContactVoiceMatcher.MIN_SCORE)
        assertTrue(ContactVoiceMatcher.score("Γιάννης", "Π ΓΙΑΝΗΣ") >= ContactVoiceMatcher.MIN_SCORE)
        assertTrue(ContactVoiceMatcher.score("Καθαράκης", "ΜΠ Καταρακης") >= ContactVoiceMatcher.MIN_SCORE)
        assertTrue(
            ContactVoiceMatcher.score("Νίκος Καθαράκης", "ΠΕΛ ΜΠ Καθαρακης Νικος") >=
                ContactVoiceMatcher.MIN_SCORE,
        )
        assertFalse(ContactVoiceMatcher.score("Κλαίρη", "ΜΠ Π Κώστας") >= ContactVoiceMatcher.MIN_SCORE)
    }
}
