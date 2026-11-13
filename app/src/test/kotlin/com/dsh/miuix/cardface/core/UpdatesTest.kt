package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, draft: Boolean = false, state: String = "uploaded", apk: Boolean = true,
        repo: String = Updates.REPOSITORY): String = """{"tag_name":"$tag","draft":$draft,"prerelease":true,"body":"notes","assets":${if (apk) """[{"name":"CardFace-test.apk","state":"$state","size":100,"browser_download_url":"https://github.com/$repo/releases/download/$tag/CardFace-test.apk"}]""" else "[]"}}"""

    @Test fun forkRevisionIsCompared() { assertTrue(Updates.isNewer("1.0.5-oneplus.7", "1.0.5-oneplus.6")) }
    @Test fun sameVersionDoesNotNotify() { assertFalse(Updates.isNewer("v1.0.5-oneplus.7", "1.0.5-oneplus.7")) }
    @Test fun olderVersionDoesNotNotify() { assertFalse(Updates.isNewer("1.0.5-oneplus.6", "1.0.5-oneplus.7")) }
    @Test fun numericNotAlphabetic() { assertTrue(Updates.isNewer("1.0.5-oneplus.10", "1.0.5-oneplus.9")) }
    @Test fun baseVersionWins() { assertTrue(Updates.isNewer("1.0.6-oneplus.1", "1.0.5-oneplus.99")) }
    @Test fun handlesUpstreamVersions() { assertTrue(Updates.isNewer("1.0.10", "1.0.6")) }
    @Test fun invalidVersionDoesNotNotify() { assertFalse(Updates.isNewer("garbage", "1.0.5-oneplus.7")) }
    @Test fun keepsFullTagVersion() { assertEquals("1.0.5-oneplus.7", UpdateVersion.fromTag("v1.0.5-oneplus.7")) }
    @Test fun updateSourceIsOwnerRepository() { assertEquals("https://api.github.com/repos/Cokelce/cardface-miuix/releases?per_page=30", Updates.API) }
    @Test fun prereleaseWithApkIsIncluded() { assertEquals("1.0.5-oneplus.7", Updates.parseReleases("[${release("v1.0.5-oneplus.7")}]")!!.versionName) }
    @Test fun choosesHighestVersionRatherThanArrayOrder() {
        val json = "[${release("v1.0.5-oneplus.6")},${release("v1.0.5-oneplus.10")},${release("v1.0.5-oneplus.7")}]"
        assertEquals("1.0.5-oneplus.10", Updates.parseReleases(json)!!.versionName)
    }
    @Test fun draftIsExcluded() { assertNull(Updates.parseReleases("[${release("v1.0.5-oneplus.7", draft = true)}]")) }
    @Test fun missingApkIsExcluded() { assertNull(Updates.parseReleases("[${release("v1.0.5-oneplus.7", apk = false)}]")) }
    @Test fun unfinishedUploadIsExcluded() { assertNull(Updates.parseReleases("[${release("v1.0.5-oneplus.7", state = "new")}]")) }
    @Test fun otherRepositoryAssetIsExcluded() { assertNull(Updates.parseReleases("[${release("v1.0.5-oneplus.7", repo = "Other/project")}]")) }
    @Test fun unknownTagIsExcluded() { assertNull(Updates.parseReleases("[${release("nightly")} ]")) }
    @Test fun emptyList() { assertNull(Updates.parseReleases("[]")) }
    @Test fun downloadIsActualReleaseAsset() {
        assertEquals("https://github.com/Cokelce/cardface-miuix/releases/download/v1.0.5-oneplus.7/CardFace-test.apk", Updates.parseReleases("[${release("v1.0.5-oneplus.7")}]")!!.downloadUrl)
    }
}
