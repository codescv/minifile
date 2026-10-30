package com.minifile.explorer.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun parsesValidTagsAndRejectsGarbage() {
        assertEquals(SemanticVersion(1, 0, 0), SemanticVersion.parse("v1.0.0"))
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse("V1.2"))
        assertEquals(SemanticVersion(2, 3, 4, "beta.1"), SemanticVersion.parse("2.3.4-beta.1+build.9"))
        assertEquals("1.0.0", SemanticVersion.stripDebugSuffix("1.0.0-DEBUG"))
        assertEquals("1.0.0", SemanticVersion.stripDebugSuffix("1.0.0-debug"))

        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("not-a-version"))
        assertNull(SemanticVersion.parse("1.2.3-beta..1"))
        assertNull(SemanticVersion.parse("99999999999999999999.0.0"))
    }

    @Test
    fun ordersReleasesAbovePreReleasesAndComparesNumerically() {
        val rc1 = SemanticVersion.parse("v1.0.0-rc.1")!!
        val rc2 = SemanticVersion.parse("v1.0.0-rc.2")!!
        val rc10 = SemanticVersion.parse("v1.0.0-rc.10")!!
        val final100 = SemanticVersion.parse("v1.0.0")!!
        val v101 = SemanticVersion.parse("v1.0.1")!!

        assertTrue(rc1 < rc2)
        assertTrue(rc2 < rc10)
        assertTrue(rc10 < final100)
        assertTrue(final100 < v101)

        assertTrue(SemanticVersion.isNewerVersion("1.0.0-DEBUG", "v1.0.1"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.0-DEBUG", "v1.0.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.0", "v1.0.0-rc.1"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.0", "garbage"))
    }
}
