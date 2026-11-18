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
        assertTrue(Updates.isNewer("2.0.0-261119.10", "2.0.0-261119.9"))
        assertTrue(Updates.isNewer("2.0.0-261119.1", "1.0.8-261119.12"))
        assertFalse(Updates.isNewer("1.0.8-261119.12", "2.0.0-261119.1"))
        assertFalse(Updates.isNewer("2.0.0-261119.1", "2.0.0-261119.1"))
    }
    @Test fun prereleaseIsIncludedAndLatestIsNumeric() {
        val text = "[${release("v2.0.0-261119.9")},${release("v2.0.0-261119.10", "\"prerelease\":true,")}]"
        assertEquals("2.0.0-261119.10", Updates.parseLatest(text)?.versionName)
    }
    @Test fun draftsIncompleteAssetsAndForeignDownloadsAreExcluded() {
        val text = "[${release("v2.0.0-261119.1")},${release("v2.0.0-261119.2", "\"draft\":true,")},${release("v2.0.0-261119.3", state="new")},${release("v2.0.0-261119.4", repo="MiamBao/cardface-miuix")}]"
        assertEquals("2.0.0-261119.1", Updates.parseLatest(text)?.versionName)
    }
    @Test fun upstreamOnlyReleaseIsNotOffered() {
        assertNull(Updates.parseLatest("[${release("v3.0.0")}]"))
        assertEquals("Cokelce/cardface-miuix", Updates.REPOSITORY)
    }
    @Test fun originalNumericPrefixTagsStillParseForChangelogComparison() {
        assertNull(UpdateVersion.fromTag("20-2.0.0"))
    }
}
