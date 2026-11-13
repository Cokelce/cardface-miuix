package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class UpdateIntegrityTest {
    private val hash = "a".repeat(64)
    @Test fun githubDigest() { assertEquals(hash, UpdateIntegrity.sha256("sha256:$hash")) }
    @Test fun releaseNotesFallback() { assertEquals(hash, UpdateIntegrity.sha256(null, "SHA-256: $hash")) }
    @Test fun noDigest() { assertNull(UpdateIntegrity.sha256(null,"nothing")) }
    @Test fun malformedDigest() { assertNull(UpdateIntegrity.sha256("sha256:bad")) }
    @Test fun matchingSizeAndHash() { UpdateIntegrity.check(100,100,hash,hash) }
    @Test(expected=IllegalArgumentException::class) fun sizeMismatch() { UpdateIntegrity.check(100,99,hash,hash) }
    @Test(expected=IllegalArgumentException::class) fun hashMismatch() { UpdateIntegrity.check(100,100,hash,"b".repeat(64)) }
    @Test(expected=IllegalArgumentException::class) fun missingHashBlocksInstaller() { UpdateIntegrity.check(100,100,null,hash) }
    @Test(expected=IllegalArgumentException::class) fun oversizedApkRejected() { UpdateIntegrity.check(129L*1024*1024,129L*1024*1024,hash,hash) }
    @Test fun signersMustMatch() { assertTrue(UpdateIntegrity.sameSigners(setOf("a"),setOf("a"))); assertFalse(UpdateIntegrity.sameSigners(setOf("a"),setOf("b"))) }
    @Test fun emptySignersRejected() { assertFalse(UpdateIntegrity.sameSigners(emptySet(),emptySet())) }
    @Test fun extraSignerRejected() { assertFalse(UpdateIntegrity.sameSigners(setOf("a"),setOf("a","b"))) }
}
