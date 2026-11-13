package com.dsh.miuix.cardface.xposed

import org.junit.Assert.*
import org.junit.Test

class ReplacementTrackingTest {
    private val original = "https://wallet.example/card/original.png.-q90.webp"
    private val remote = "https://images.example/custom.png"
    @Test fun remoteReplacementDoesNotBecomeAnOriginalCard() {
        val result = UrlMapper.map(remote, UrlMapper.Env(mappings = mapOf(original to remote)))
        assertFalse(result.trackUrl)
        assertFalse(result.replaced)
    }
    @Test fun replacementQueryVariantsAreAlsoExcluded() {
        assertTrue(UrlMapper.isReplacementAsset("$remote?cache=2", mapOf(original to "$remote?cache=1")))
    }
    @Test fun replacementIsExcludedEvenInTestMode() {
        val result = UrlMapper.map(remote, UrlMapper.Env(testMode = true, mappings = mapOf(original to remote)))
        assertFalse(result.trackUrl)
        assertEquals("", result.url)
    }
    @Test fun originalRemainsTrackedAndReplaced() {
        val result = UrlMapper.map(original, UrlMapper.Env(mappings = mapOf(original to remote)))
        assertTrue(result.trackUrl)
        assertEquals(remote, result.url)
    }
    @Test fun uiAndHookCanResolveTheSameQueryVariant() {
        val mappings = mapOf(original to remote)
        assertEquals(remote, UrlMapper.lookupMapping("$original?token=2", mappings))
    }
    @Test fun localReplacementDoesNotBecomeAnOriginalCard() {
        assertTrue(UrlMapper.isReplacementAsset("file:///data/user/0/com.finshell.wallet/files/images/saved.png",
            mapOf(original to "saved.png")))
    }
    @Test fun unrelatedOriginalIsNotFiltered() {
        assertFalse(UrlMapper.isReplacementAsset("https://wallet.example/card/another.png", mapOf(original to remote)))
    }
    @Test fun providerImagesAreNotOriginals() {
        assertTrue(UrlMapper.isReplacementAsset(UrlMapper.LOCAL_IMAGE_URI_PREFIX + "test.png", emptyMap()))
    }
}
