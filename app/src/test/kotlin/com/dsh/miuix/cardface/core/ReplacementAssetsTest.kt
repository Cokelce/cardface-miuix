package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class ReplacementAssetsTest {
    private val original="https://wallet.example/card.png"
    private val custom="https://custom.example/my.png"
    @Test fun registeredRemoteSurvivesMappingClear() {
        assertTrue(ReplacementAssets.isKnown(custom,emptyMap(),setOf(ReplacementAssets.key(custom)),emptySet()))
    }
    @Test fun historicalExactAssetSurvivesMappingClear() {
        assertTrue(ReplacementAssets.isKnown(custom,emptyMap(),emptySet(),setOf(CardRuntime.fingerprint(custom))))
    }
    @Test fun officialOriginalNotHiddenBySameNameOrOtherHost() {
        assertFalse(ReplacementAssets.isKnown(original,emptyMap(),setOf(ReplacementAssets.key(custom)),emptySet()))
        assertFalse(ReplacementAssets.isKnown("https://wallet.example/my.png",emptyMap(),setOf(ReplacementAssets.key(custom)),emptySet()))
    }
    @Test fun queryVariantMatchesPermanentProvenance() {
        assertTrue(ReplacementAssets.isKnown("$custom?token=2",emptyMap(),setOf(ReplacementAssets.key("$custom?token=1")),emptySet()))
    }
    @Test fun currentMappingMaterialRemainsFiltered() {
        assertTrue(ReplacementAssets.isKnown(custom,mapOf(original to custom),emptySet(),emptySet()))
    }
    @Test fun localCopiedMaterialIsNotAnOriginal() {
        assertTrue(ReplacementAssets.isKnown("file:///data/user/0/wallet/files/images/custom.png",emptyMap(),setOf(ReplacementAssets.key("custom.png")),emptySet()))
    }
    @Test fun unrelatedUuidNeverFiltered() {
        assertFalse(ReplacementAssets.isKnown("https://custom.example/other.png",emptyMap(),setOf(ReplacementAssets.key(custom)),emptySet()))
    }
    @Test fun legacyUnidentifiedEntryRequiresUserConfirmation() {
        assertFalse(ReplacementAssets.isKnown(custom,emptyMap(),emptySet(),emptySet()))
    }
}
