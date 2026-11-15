package com.dsh.miuix.cardface.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DraftValidationTest {
    @Test fun cropRejectsNonFiniteWrongCountAndInvalidBounds() {
        listOf("NaN,0,4,5", "0,Infinity,4,5", "0,0,4", "-1,0,4,5", "0,0,0,5", "0,5,4,4").forEach {
            assertNull(it, DraftValidation.parseCrop(it))
        }
        assertEquals(listOf(0f, 1f, 4f, 5f), DraftValidation.parseCrop("0,1,4,5"))
    }

    @Test fun snapshotMustBelongToUrl() {
        val url = "https://example.test/card.png"
        val name = "editor-draft-${DraftValidation.urlHash(url)}-${UUID.randomUUID()}.png"
        assertTrue(DraftValidation.snapshotNameMatches(name, url))
        assertFalse(DraftValidation.snapshotNameMatches(name, url + "?other"))
        assertFalse(DraftValidation.snapshotNameMatches("editor-draft.png", url))
    }

    @Test fun queueFiltersOversizeUrisAndCapsCount() {
        assertFalse(DraftValidation.validQueueUri("x".repeat(DraftValidation.MAX_URI_LENGTH + 1)))
        assertFalse(DraftValidation.validQueueUri("not a uri"))
        val values = (0..DraftValidation.MAX_CROP_QUEUE).map { "content://provider/item/$it" }
        assertEquals(DraftValidation.MAX_CROP_QUEUE, DraftValidation.boundedQueue(values).size)
    }
}
