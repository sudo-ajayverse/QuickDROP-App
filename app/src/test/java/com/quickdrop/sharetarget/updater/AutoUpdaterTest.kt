package com.quickdrop.sharetarget.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdaterTest {

    @Test
    fun testIsNewerVersion() {
        // Basic semantic versions
        assertTrue(AutoUpdater.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(AutoUpdater.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(AutoUpdater.isNewerVersion("1.0.0", "2.0.0"))
        
        // Identical versions
        assertFalse(AutoUpdater.isNewerVersion("1.0.0", "1.0.0"))
        
        // Older versions
        assertFalse(AutoUpdater.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(AutoUpdater.isNewerVersion("2.0.0", "1.9.9"))
        
        // With 'v' prefixes and extra text
        assertTrue(AutoUpdater.isNewerVersion("v1.0", "v1.1"))
        assertTrue(AutoUpdater.isNewerVersion("1.0", "v1.0.1"))
        assertFalse(AutoUpdater.isNewerVersion("v1.1.0", "v1.0.9"))

        // Different length versions
        assertTrue(AutoUpdater.isNewerVersion("1.0", "1.0.1"))
        assertFalse(AutoUpdater.isNewerVersion("1.0.1", "1.0"))

        // Multi-digit versions
        assertTrue(AutoUpdater.isNewerVersion("0.5.9", "0.5.10"))
        assertFalse(AutoUpdater.isNewerVersion("0.5.10", "0.5.9"))
        assertTrue(AutoUpdater.isNewerVersion("0.5.10", "0.6.0"))
    }
}
