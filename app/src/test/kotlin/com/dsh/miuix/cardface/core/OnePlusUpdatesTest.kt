package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class OnePlusUpdatesTest {
    private fun release(tag: String, extra: String = "", state: String = "uploaded", repo: String = Updates.REPOSITORY): String = """
        {"tag_name":"$tag", "published_at":"2026-11-17T00:00:00Z", "body":"test", $extra
        "assets":[{"name":"CardFace-$tag.apk", "state":"$state", "size":1200,
        "browser_download_url":"https://github.com/$repo/releases/download/$tag/CardFace-$tag.apk"}]}
    """.trimIndent()

    @Test fun numericOnePlusBuildOrdering() {
        assertTrue(Updates.isNewer("2.0.0-2611191", "2.0.0-261119"))
        assertTrue(Updates.isNewer("2.0.0-261120", "2.0.0-26111999"))
        assertNull(UpdateVersion.fromTag("2.0.0-261119.1"))
        assertTrue(Updates.isNewer("2.0.0-26111910", "2.0.0-2611199"))
        assertTrue(Updates.isNewer("2.0.0-2611191", "1.0.8-26111912"))
        assertFalse(Updates.isNewer("1.0.8-26111912", "2.0.0-2611191"))
        assertFalse(Updates.isNewer("2.0.0-2611191", "2.0.0-2611191"))
    }
    @Test fun prereleaseIsExcludedAndLatestIsNumeric() {
        val text = "[${release("v2.0.0-2611199")},${release("v2.0.0-26111910", "\"prerelease\":true,")}]"
        assertEquals("2.0.0-2611199", Updates.parseLatest(text)?.versionName)
    }
    @Test fun draftsIncompleteAssetsAndForeignDownloadsAreExcluded() {
        val text = "[${release("v2.0.0-2611191")},${release("v2.0.0-2611192", "\"draft\":true,")},${release("v2.0.0-2611193", state="new")},${release("v2.0.0-2611194", repo="MiamBao/cardface-miuix")}]"
        assertEquals("2.0.0-2611191", Updates.parseLatest(text)?.versionName)
    }
    @Test fun upstreamOnlyReleaseIsNotOffered() {
        assertNull(Updates.parseLatest("[${release("v3.0.0")}]"))
        assertEquals("Cokelce/cardface-miuix", Updates.REPOSITORY)
    }
    @Test fun originalNumericPrefixTagsStillParseForChangelogComparison() {
        assertNull(UpdateVersion.fromTag("20-2.0.0"))
    }
    @Test fun artifactVersionUsesUndottedSameDaySuffix() {
        assertEquals("2.0.0-26111910", UpdateArtifactPolicy.safeVersion("2.0.0-26111910"))
        assertEquals("unknown", UpdateArtifactPolicy.safeVersion("2.0.0-261119.10"))
        assertEquals("unknown", UpdateArtifactPolicy.safeVersion("2.0.0-2611190"))
    }
    @Test fun selectedTagComesFromSelectedOfficialRelease() {
        val text = "[${release("v2.0.0-261119", "\"draft\":true,")},${release("2.0.0-261119")}]"
        assertEquals("2.0.0-261119", Updates.parseLatest(text)?.tag)
    }
    @Test fun comparesLegacyAndDateVersions() {
        assertTrue(Updates.isNewer("2.0.1-261119", "2.0.1-oneplus.99"))
        assertFalse(Updates.isNewer("2.0.1-oneplus.99", "2.0.1-261119"))
        assertFalse(Updates.isNewer("garbage", "2.0.1-261119"))
    }
}

class WebFallbackTest {
    private val tag = "v2.1.0-2611193"
    private val link = "/${Updates.REPOSITORY}/releases/download/$tag/CardFace-2.1.0-2611193.apk"
    private val hash = "a".repeat(64)
    private fun html(path: String = link, digest: String = hash) = "<li><a href=\"$path\">apk</a><span>sha256:$digest</span></li>"
    @Test fun parsesOnlyObservedAssetAndHash() {
        val release = Updates.parseWebAssets(tag, html())!!
        assertEquals("https://github.com$link", release.downloadUrl)
        assertEquals(hash, release.sha256)
        assertEquals("2.1.0-2611193", release.versionName)
    }
    @Test fun rejectsForeignOrMissingDigestOrMultipleAssets() {
        assertNull(Updates.parseWebAssets(tag, html(link.replace("Cokelce", "other"))))
        assertNull(Updates.parseWebAssets(tag, html(digest = "bad")))
        assertNull(Updates.parseWebAssets(tag, html() + html(link.replace("CardFace-", "CardFace-other-"))))
        assertNull(Updates.tagFromLatestLocation("https://evil.example/Cokelce/cardface-miuix/releases/tag/$tag"))
        assertNull(Updates.tagFromLatestLocation("/Cokelce/cardface-miuix/releases/tag/v3.0.0"))
    }
    @Test fun messagesDistinguishFailures() {
        assertTrue(Updates.Check(error=Updates.Error.RATE_LIMIT).message().contains("受限"))
        assertTrue(Updates.Check(error=Updates.Error.NETWORK).message().contains("网络"))
        assertTrue(Updates.Check(error=Updates.Error.PARSE).message().contains("解析"))
    }
}
