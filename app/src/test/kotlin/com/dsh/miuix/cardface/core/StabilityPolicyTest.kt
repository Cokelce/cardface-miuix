package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class StabilityPolicyTest {
    private val first="https://wallet.example/cards/11111111-1111-1111-1111-111111111111/front.png"
    private val second="https://wallet.example/cards/22222222-2222-2222-2222-222222222222/front.png"
    @Test fun uuidDirectoriesRemainDistinct() { assertNull(MappingPolicy.lookup(second,mapOf(first to "a.png"))) }
    @Test fun updateDoesNotEraseAnotherUuid() { assertEquals(setOf(first),MappingPolicy.mutationKeys(first,mapOf(first to "a.png",second to "b.png"))) }
    @Test fun filenameFallbackDoesNotCrossDomains() { assertNull(MappingPolicy.lookup("https://other.example/front.png",mapOf("https://wallet.example/front.png" to "a.png"))) }
    @Test fun qualityVariantStillMatches() { assertEquals("a.png",MappingPolicy.lookup("https://x/a.png.-o.webp",mapOf("https://x/a.png.-q90.webp" to "a.png"))) }
    @Test fun ambiguousVariantsNotSelected() { assertNull(MappingPolicy.lookup("https://x/a.png",mapOf("https://x/a.png.-o.webp" to "a.png","https://x/a.png.-q90.webp" to "b.png"))) }
    @Test fun exactMappingRemainsIndependent() { assertEquals(setOf("https://x/a.png.-o.webp"),MappingPolicy.mutationKeys("https://x/a.png.-o.webp",mapOf("https://x/a.png.-o.webp" to "a.png","https://x/a.png.-q90.webp" to "b.png"))) }
    @Test fun explicitEmptyProviderIsAuthoritative() { assertTrue(MappingPolicy.select(MappingPolicy.State(emptyMap(),4),null,MappingPolicy.State(mapOf(first to "a.png"),3),null).mappings.isEmpty()) }
    @Test fun nonnullEmptyFrameworkDoesNotHideNewerSnapshot() { assertEquals("a.png",MappingPolicy.select(null,MappingPolicy.State(emptyMap(),0),MappingPolicy.State(mapOf(first to "a.png"),4),null).mappings[first]) }
    @Test fun oldCacheDoesNotOverrideNewSnapshot() { assertTrue(MappingPolicy.select(null,null,MappingPolicy.State(emptyMap(),5),MappingPolicy.State(mapOf(first to "a.png"),4)).mappings.isEmpty()) }
    @Test fun frameworkOutageCanUseSnapshot() { assertEquals("a.png",MappingPolicy.select(null,null,MappingPolicy.State(mapOf(first to "a.png"),2),null).mappings[first]) }
    @Test fun latestCachePreservedWhenOffline() { assertEquals(9,MappingPolicy.select(null,null,MappingPolicy.State(emptyMap(),1),MappingPolicy.State(mapOf(first to "a.png"),9)).revision.toInt()) }
    @Test fun snapshotRoundTrip() { val m=mapOf(first to "a.png"); val decoded=MappingSnapshot.decode(MappingSnapshot.encode(m,12));assertEquals(m,decoded.mappings);assertEquals(12L,decoded.revision) }
    @Test fun emptySnapshotIsDeletionEvidence() { assertTrue(MappingSnapshot.decode(MappingSnapshot.encode(emptyMap(),12)).mappings.isEmpty()) }
    @Test fun legacySnapshotIsReadable() { assertEquals("a.png",MappingSnapshot.decode("$first\ta.png\n").mappings[first]) }
    @Test(expected=IllegalArgumentException::class) fun invalidSnapshotRejected() { MappingSnapshot.decode(MappingSnapshot.encode(mapOf(first to "../x"),1)) }
    @Test fun stableIdLinksNewUuidOnlyWhenExplicit() { val old="https://x/old.png";val new="https://x/new.png";assertEquals("a.png",CardSourceIndex.expand(mapOf(old to "a.png"),listOf(setOf(old,new)))[new]) }
    @Test fun noStableIdDoesNotGuessNewUuid() { assertNull(CardSourceIndex.expand(mapOf(first to "a.png"),emptyList())[second]) }
    @Test fun conflictingStableIdEvidenceDoesNotGuess() { assertNull(CardSourceIndex.expand(mapOf(first to "a.png",second to "b.png"),listOf(setOf(first,second,"https://x/new.png")))["https://x/new.png"]) }
    @Test fun oldAssetCanResolveOneKnownOriginal() { assertEquals(first,CardSourceIndex.originalForAsset("file:///x/old.png",emptyMap(),setOf(first))) }
    @Test fun sharedAssetDoesNotGuessOriginal() { assertNull(CardSourceIndex.originalForAsset("file:///x/old.png",emptyMap(),setOf(first,second))) }
    class Card { fun getUniqueCardId()="a-stable-id" }
    class NoId { fun getCardName()="公交卡" }
    @Test fun stableIdDoesNotExposeRawValue() { val id=CardSourceIndex.stableId(Card(),"wallet");assertEquals(64,id!!.length);assertFalse(id.contains("stable")) }
    @Test fun nameAloneIsNotAStableIdentifier() { assertNull(CardSourceIndex.stableId(NoId(),"wallet")) }
}
