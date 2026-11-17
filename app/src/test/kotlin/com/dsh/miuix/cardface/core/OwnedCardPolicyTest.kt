package com.dsh.miuix.cardface.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCardPolicyTest {
    private fun card(
        type: String = "1", status: String = "ACTIVE", aid: String? = "aid",
        unique: String? = null, url: String? = "https://cdn/card.png",
    ) = OwnedCardPolicy.Candidate(type, status, aid, unique, url, "公交卡")

    @Test fun rejectsTemplateTypesAndNonOwnedStatuses() {
        assertFalse(OwnedCardPolicy.accept(card(type = "19")))
        assertFalse(OwnedCardPolicy.accept(card(status = "RECOMMEND")))
        assertFalse(OwnedCardPolicy.accept(card(status = "WAIT_TO_RECEIVE")))
        assertFalse(OwnedCardPolicy.accept(card(status = "NOT_BINDED")))
        assertFalse(OwnedCardPolicy.accept(card(status = "DELETED")))
    }

    @Test fun requiresProvisioningIdentityAndImage() {
        assertFalse(OwnedCardPolicy.accept(card(aid = null, unique = null)))
        assertFalse(OwnedCardPolicy.accept(card(aid = " ", unique = " ")))
        assertFalse(OwnedCardPolicy.accept(card(url = null)))
        assertTrue(OwnedCardPolicy.accept(card(aid = "aid")))
        assertTrue(OwnedCardPolicy.accept(card(aid = null, unique = "unique")))
        assertTrue(OwnedCardPolicy.accept(card(status = "SUSPENDED")))
    }

    @Test fun snapshotIsExactAndDoesNotImportLegacyUrls() {
        val json = OwnedCardPolicy.snapshotJson(42L, listOf(
            " https://b " to "B", "https://a" to "A", "https://a" to "old",
        ))
        assertEquals(setOf("https://a", "https://b"), OwnedCardPolicy.snapshotUrls(json))
        assertEquals("A", OwnedCardPolicy.snapshotNames(json)["https://a"])
        assertEquals(emptySet<String>(), OwnedCardPolicy.snapshotUrls(null))
        assertFalse(OwnedCardPolicy.snapshotUrls(json).contains("https://legacy-polluted"))
    }
    @Test fun provisionedCardsDoNotAllHaveNfcAid() {
        for (type in listOf("1", "6", "14", "33")) {
            assertTrue("ACTIVE type=$type", OwnedCardPolicy.accept(card(type=type, aid=null, unique="instance-id")))
            assertTrue("SUSPENDED type=$type", OwnedCardPolicy.accept(card(type=type, status="SUSPENDED", aid=null, unique="instance-id")))
        }
    }

    @Test fun templateIdentityNeverGrantsMembership() {
        for (status in listOf("RECOMMEND", "WAIT_TO_RECEIVE", "ALLOW_OPEN", "NOT_BINDED", "DELETED", "DELETING", "UNKNOWN", "")) {
            assertFalse(status, OwnedCardPolicy.accept(card(status=status, aid=null, unique="template-id")))
        }
        for (type in listOf("0", "4", "19")) {
            assertFalse(type, OwnedCardPolicy.accept(card(type=type, aid=null, unique="template-id")))
        }
        assertFalse(OwnedCardPolicy.accept(card(aid=null, unique="null")))
        assertFalse(OwnedCardPolicy.accept(card(aid="null", unique=null)))
    }

}
