package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFileCopyTest {
    private fun run(source: File, dir: File, name: String="card.png"): Int {
        val script=AtomicFileCopy.script(source.absolutePath,dir.absolutePath,name)
        val process=ProcessBuilder("sh","-c",script).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().readText();val code=process.waitFor()
        if (code!=0) println("copy returned $code: $output")
        return code
    }
    @Test fun zeroByteCopyIsRepaired() { val root=Files.createTempDirectory("atomic").toFile();try {
        val src=File(root,"source").apply {writeText("valid image data")};val dir=File(root,"dest").apply {mkdir()};File(dir,"card.png").writeBytes(byteArrayOf())
        assertEquals(0,run(src,dir));assertEquals(src.readText(),File(dir,"card.png").readText())
    } finally {root.deleteRecursively()} }
    @Test fun truncatedExistingIsRepaired() { val root=Files.createTempDirectory("atomic").toFile();try {
        val src=File(root,"source").apply {writeText("complete content")};val dir=File(root,"dest").apply {mkdir()};File(dir,"card.png").writeText("part")
        assertEquals(0,run(src,dir));assertArrayEquals(src.readBytes(),File(dir,"card.png").readBytes())
    } finally {root.deleteRecursively()} }
    @Test fun failedSourcePreservesOldTarget() { val root=Files.createTempDirectory("atomic").toFile();try {
        val src=File(root,"missing");val dir=File(root,"dest").apply {mkdir()};val old=File(dir,"card.png").apply {writeText("previous usable image")}
        assertNotEquals(0,run(src,dir));assertEquals("previous usable image",old.readText());assertEquals(1,dir.listFiles()!!.size)
    } finally {root.deleteRecursively()} }
    @Test fun quotedPathsAreSupported() { val root=Files.createTempDirectory("atomic").toFile();try {
        val src=File(root,"with 'quote").apply {writeText("image")};val dir=File(root,"destination with space")
        assertEquals(0,run(src,dir,"我的 卡.png"));assertEquals("image",File(dir,"我的 卡.png").readText())
    } finally {root.deleteRecursively()} }
    @Test fun matchingCopyLeavesNoPartFiles() { val root=Files.createTempDirectory("atomic").toFile();try {
        val src=File(root,"source").apply {writeText("image")};val dir=File(root,"dest")
        assertEquals(0,run(src,dir));assertEquals(0,run(src,dir));assertEquals(1,dir.listFiles()!!.size)
    } finally {root.deleteRecursively()} }
    @Test(expected=IllegalArgumentException::class) fun pathTraversalRejected() { AtomicFileCopy.script("a","b","../x") }
}
