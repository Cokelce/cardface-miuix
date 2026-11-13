package com.dsh.miuix.cardface.xposed

import org.junit.Assert.*
import org.junit.Test

class OnePlusMappingTest {
    @Test fun onePlusOriginalUrlIsCollectedWithoutMapping() {
        val result = UrlMapper.map("https://wallet.example/card/bus.png", UrlMapper.Env(isMiui = false))
        assertTrue(result.trackUrl)
        assertFalse(result.replaced)
    }
    @Test fun onePlusLocalImageUsesWalletPrivateDirectory() {
        val url = "https://wallet.example/card/bus.png"
        val env = UrlMapper.Env(isMiui = false, mappings = mapOf(url to "custom.png"),
            walletImagesDir = "/data/user/0/com.finshell.wallet/files/images",
            existingWalletImages = setOf("custom.png"))
        val result = UrlMapper.map(url, env)
        assertEquals("file:///data/user/0/com.finshell.wallet/files/images/custom.png", result.url)
        assertNull(result.copy)
    }
    @Test fun onePlusMissingImageRequestsCopy() {
        val url = "https://wallet.example/card/door.png"
        val result = UrlMapper.map(url, UrlMapper.Env(isMiui = false,
            mappings = mapOf(url to "custom.png"), walletImagesDir = "/data/user/0/com.finshell.wallet/files/images"))
        assertNotNull(result.copy)
        assertEquals("/data/user/0/com.finshell.wallet/files/images/custom.png", result.copy!!.destPath)
    }
    @Test fun onePlusDoesNotInheritMiuiSpecificThumbnailFilter() {
        val url = "https://wallet.example/card/w270h480/bus.png"
        assertTrue(UrlMapper.map(url, UrlMapper.Env(isMiui = false)).trackUrl)
        assertFalse(UrlMapper.map(url, UrlMapper.Env(isMiui = true)).trackUrl)
    }
    @Test fun providerAuthorityMatchesAdaptedPackage() {
        assertEquals("content://io.github.miambao.cardface.oneplus.images/images/", UrlMapper.LOCAL_IMAGE_URI_PREFIX)
    }
}
