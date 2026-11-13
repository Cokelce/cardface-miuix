package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class BackupArchiveTest {
    private fun meta() = JSONObject().put("urls", JSONArray(listOf("https://wallet.example/a.png")))
        .put("blacklist", JSONArray()).put("mappings", JSONObject().put("https://wallet.example/a.png", "test.png"))
        .put("names", JSONObject().put("https://wallet.example/a.png", "我的卡"))
        .put("detected", JSONObject())
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()
    private fun rejects(bytes: ByteArray) {
        val root = Files.createTempDirectory("backup-test").toFile()
        try {
            try { BackupArchive.read(ByteArrayInputStream(bytes), root).use { }; fail("expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            assertEquals(0, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsPathTraversal() { assertFalse(BackupArchive.validPath("../test.png")); assertFalse(BackupArchive.validPath("images/../../x")) }
    @Test fun rejectsAbsolutePaths() { assertFalse(BackupArchive.validPath("/images/test.png")) }
    @Test fun rejectsWindowsSeparators() { assertFalse(BackupArchive.validPath("images/..\\test.png")) }
    @Test fun onlyExpectedFiles() { assertFalse(BackupArchive.validPath("settings.xml")); assertFalse(BackupArchive.validPath("images/test.apk")) }
    @Test fun supportsChineseAndSpaces() { assertTrue(BackupArchive.validPath("images/我的 卡.png")) }
    @Test fun roundTrip() {
        val root = Files.createTempDirectory("roundtrip").toFile()
        try {
            val image = File(root, "test.png").apply { writeBytes(byteArrayOf(1,2,3)) }
            val out = ByteArrayOutputStream(); BackupArchive.write(out, meta(), mapOf("applied/test.png" to image))
            BackupArchive.read(ByteArrayInputStream(out.toByteArray()), File(root,"stage")).use { plan ->
                assertEquals(1, plan.cardCount); assertEquals("我的卡", plan.manifest.getJSONObject("names").getString("https://wallet.example/a.png"))
                assertArrayEquals(byteArrayOf(1,2,3), plan.images.getValue("applied/test.png").readBytes())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun badArchivePathRejectedBeforePublish() { rejects(zip(listOf("../x.png" to byteArrayOf(1)))) }
    @Test fun missingManifest() { rejects(zip(listOf("images/test.png" to byteArrayOf(1)))) }
    @Test fun incompatibleSchema() {
        val manifest = meta().put("format", "cardface-backup").put("schema", 99).put("images", JSONObject())
        rejects(zip(listOf("manifest.json" to manifest.toString().toByteArray())))
    }
    @Test fun checksumFailure() {
        val manifest = meta().put("format", "cardface-backup").put("schema",1).put("images", JSONObject().put("images/test.png",JSONObject().put("size",1).put("sha256","0".repeat(64))))
        rejects(zip(listOf("manifest.json" to manifest.toString().toByteArray(), "images/test.png" to byteArrayOf(1))))
    }
    @Test fun referencedImageMissing() {
        val manifest = meta().put("format", "cardface-backup").put("schema",1).put("images",JSONObject())
        rejects(zip(listOf("manifest.json" to manifest.toString().toByteArray())))
    }
    @Test fun networkMappingDoesNotRequireLocalFile() {
        val root = Files.createTempDirectory("remote").toFile()
        try {
            val manifest = meta().put("mappings",JSONObject().put("https://wallet.example/a.png","https://cdn.example/custom.png"))
            val out=ByteArrayOutputStream(); BackupArchive.write(out,manifest,emptyMap())
            BackupArchive.read(ByteArrayInputStream(out.toByteArray()),root).use { assertTrue(it.images.isEmpty()) }
        } finally {root.deleteRecursively()}
    }
    @Test fun extraFilesRejected() {
        val manifest=meta().put("format","cardface-backup").put("schema",1).put("images",JSONObject())
        rejects(zip(listOf("manifest.json" to manifest.toString().toByteArray(), "images/test.png" to byteArrayOf(1))))
    }
}
