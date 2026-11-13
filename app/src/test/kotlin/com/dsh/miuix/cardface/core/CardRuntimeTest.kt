package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class CardRuntimeTest {
    private val value = "custom.png"
    private val fp = CardRuntime.fingerprint(value)
    @Test fun unset() { assertEquals("未设置", CardRuntime.state(null, 0, null, 0)) }
    @Test fun savedIsNotProofOfHit() { assertEquals("待加载", CardRuntime.state(value, 100, null, 0)) }
    @Test fun matchingEvidence() { assertEquals("已命中", CardRuntime.state(value, 100, fp, 101)) }
    @Test fun staleEvidence() { assertEquals("待加载", CardRuntime.state(value, 100, fp, 99)) }
    @Test fun changedImageInvalidatesEvidence() { assertEquals("待加载", CardRuntime.state("new.png", 100, fp, 101)) }
    @Test fun failureAfterSave() { assertEquals("加载异常", CardRuntime.state(value, 100, fp, 0, 110, "copy failed")) }
    @Test fun oldFailureDoesNotMarkNewSave() { assertEquals("待加载", CardRuntime.state(value, 100, fp, 0, 90, "copy failed")) }
    @Test fun removedMappingIsUnset() { assertEquals("未设置", CardRuntime.state("", 100, fp, 101)) }
    @Test fun fingerprintStable() { assertEquals(fp, CardRuntime.fingerprint(value)); assertNotEquals(fp, CardRuntime.fingerprint("new.png")) }
    @Test fun qualityVariantsGroup() { assertEquals(CardIdentity.key("https://x/a.png.-q90.webp"), CardIdentity.key("https://x/a.png.-o.webp?token=2")) }
    @Test fun differentUuidNotGrouped() { assertNotEquals(CardIdentity.key("https://x/aaa.png"), CardIdentity.key("https://x/bbb.png")) }
    @Test fun differentHostNotGrouped() { assertNotEquals(CardIdentity.key("https://x/a.png"), CardIdentity.key("https://y/a.png")) }
    @Test fun sizeSegmentsGroup() { assertEquals(CardIdentity.key("https://x/card.png"), CardIdentity.key("https://x/w960h606/card.png")) }
    @Test fun manualNameAndMappingArePreserved() {
        val cards = listOf(CardFaceItem("https://x/a.png.-q90.webp", "saved.png", customName = "我的卡"),
            CardFaceItem("https://x/a.png.-o.webp", null, detectedName = "公交卡"))
        val grouped = CardIdentity.group(cards).single()
        assertEquals(2, grouped.aliasCount); assertEquals("我的卡", grouped.displayName)
        assertEquals("saved.png", grouped.replacement); assertEquals("公交卡", grouped.detectedName)
    }
    @Test fun sameNameDoesNotMergeDifferentPictures() {
        assertEquals(2, CardIdentity.group(listOf(CardFaceItem("https://x/a.png", null, detectedName = "公交卡"), CardFaceItem("https://x/b.png", null, detectedName = "公交卡"))).size)
    }
    @Test fun identityLookupMatchesHookAndUi() {
        assertEquals("saved.png", com.dsh.miuix.cardface.xposed.UrlMapper.lookupMapping("https://x/a.png.-o.webp", mapOf("https://x/a.png.-q90.webp" to "saved.png")))
    }
    @Test fun conflictingManualNamesStaySeparate() {
        val cards=listOf(CardFaceItem("https://x/a.png.-o.webp",null,customName="甲卡"),CardFaceItem("https://x/a.png.-q90.webp",null,customName="乙卡"))
        assertEquals(2,CardIdentity.group(cards).size)
    }
    @Test fun conflictingMappingsStaySeparate() {
        val cards=listOf(CardFaceItem("https://x/a.png.-o.webp","one.png"),CardFaceItem("https://x/a.png.-q90.webp","two.png"))
        assertEquals(2,CardIdentity.group(cards).size)
    }

}
