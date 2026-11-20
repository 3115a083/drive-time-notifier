package de.drivetime.notifier.ui

import org.junit.Assert.*
import org.junit.Test

class FieldDraftTest {
    @Test fun initialLoadCanReplaceAnUntouchedPlaceholder() {
        assertEquals("stored value", DraftValue("").receive("stored value").text)
    }
    @Test fun deletionAndInvalidInputSurviveAnyPersistenceEcho() {
        for (raw in listOf("", "0", "0005", "99999999999999999999999", "1x", "two words ")) {
            val draft = DraftValue("15").edit(raw)
            assertEquals(raw, draft.receive("15").receive("30").text)
        }
    }
    @Test fun olderSaveCannotReplaceNewerTypingEvenAfterAnAcknowledgement() {
        val old = DraftValue("15").edit("30").receive("30")
        assertEquals("305", old.edit("305").receive("15").receive("30").text)
    }
    @Test fun everyNumericRangeValidatesWithoutClampingTheDraft() {
        for ((min, max) in listOf(0 to 180, 1 to 50, 100 to 10_000, 1 to 1_000_000, 1 to 300, 1 to 500)) {
            assertNull(DraftValue("").number(min, max))
            assertNull(DraftValue((max + 1).toString()).number(min, max))
            assertEquals(min, DraftValue(min.toString()).number(min, max))
            assertEquals(max, DraftValue(max.toString()).number(min, max))
        }
    }
    @Test fun delayedLocationIsRejectedAfterEditingEvenWhenTheTextIsRestored() {
        assertTrue(fieldRequestStillCurrent("Home", 1, "Home", 1))
        assertFalse(fieldRequestStillCurrent("Home", 1, "Office", 2))
        assertFalse(fieldRequestStillCurrent("Home", 1, "Home", 3))
    }
}
