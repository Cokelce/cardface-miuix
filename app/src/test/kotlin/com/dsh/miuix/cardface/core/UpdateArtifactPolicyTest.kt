package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateArtifactPolicyTest {
    @Test fun validatesLengthAndSizeCap() {
        assertTrue(UpdateArtifactPolicy.validateSize(20358301, 20358301))
        assertFalse(UpdateArtifactPolicy.validateSize(20358300, 20358301))
        assertFalse(UpdateArtifactPolicy.validateSize(0, 0))
        assertFalse(UpdateArtifactPolicy.validateSize(UpdateArtifactPolicy.MAX_BYTES + 1, 0))
    }
    @Test fun restrictsGithubHttpsRedirects() {
        assertTrue(UpdateArtifactPolicy.allowedDownloadUrl("https://github.com/Cokelce/cardface-miuix/releases/download/v1/a.apk"))
        assertTrue(UpdateArtifactPolicy.allowedDownloadUrl("https://release-assets.githubusercontent.com/a.apk?token=x"))
        for (url in listOf("http://github.com/a", "https://evil.example/a", "https://github.com@evil.example/a", "file:///cache/update.apk", "https://github.com:8443/a")) {
            assertFalse(url, UpdateArtifactPolicy.allowedDownloadUrl(url))
        }
    }
    @Test fun apkStructureIsRequired() {
        val file = Files.createTempFile("update-policy", ".apk").toFile()
        try {
            file.writeText("<html>download error</html>")
            assertFalse(UpdateArtifactPolicy.validateZip(file))
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in listOf("AndroidManifest.xml", "classes.dex", "resources.arsc")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
            }
            assertTrue(UpdateArtifactPolicy.validateZip(file))
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.closeEntry()
            }
            assertFalse(UpdateArtifactPolicy.validateZip(file))
        } finally { file.delete() }
    }
    @Test fun fileNamesCannotEscapeDownloadDirectory() {
        assertEquals("unknown", UpdateArtifactPolicy.safeVersion("../../escape"))
        assertEquals("2.0.1-oneplus.2", UpdateArtifactPolicy.safeVersion("2.0.1-oneplus.2"))
        assertEquals("unknown", UpdateArtifactPolicy.safeHash("../x"))
    }
    @Test fun releaseMetadataCarriesSizeAndDigest() {
        val hash = "a".repeat(64)
        val text = """[{"tag_name":"v2.0.1-oneplus.2","assets":[{"name":"CardFace-2.0.1-oneplus.2.apk","state":"uploaded","size":20358301,"digest":"sha256:$hash","browser_download_url":"https://github.com/Cokelce/cardface-miuix/releases/download/v2.0.1-oneplus.2/CardFace-2.0.1-oneplus.2.apk"}]}]"""
        val release = Updates.parseLatest(text)!!
        assertEquals(20358301L, release.expectedSize)
        assertEquals(hash, release.sha256)
    }
}
