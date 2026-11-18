package com.dsh.miuix.cardface.core

import org.junit.Assert.*
import org.junit.Test

class OnePlusUpdatesTest {
    @Test fun datedVersions() {
        assertTrue(Updates.isNewer("2.1.0-2611191", "2.1.0-261119"))
        assertTrue(Updates.isNewer("2.1.0-26111910", "2.1.0-2611199"))
        assertTrue(Updates.isNewer("2.1.0-261120", "2.1.0-26111999"))
        assertFalse(Updates.isNewer("2.1.0-261119", "2.1.0-2611191"))
        assertFalse(Updates.isNewer("2.1.0-2611191", "2.1.0-2611191"))
        assertTrue(Updates.isNewer("2.1.0", "2.0.9-2611191"))
    }
}
