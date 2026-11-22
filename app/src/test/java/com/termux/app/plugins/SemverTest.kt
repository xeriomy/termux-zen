package com.termux.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {

    @Test
    fun parsesValidVersions() {
        assertNotNull(Semver.parse("1.2.3"))
        assertNotNull(Semver.parse("0.0.0"))
        assertNotNull(Semver.parse("1.0.0-alpha.1"))
        assertNotNull(Semver.parse("1.0.0+build.5"))
        assertNotNull(Semver.parse("1.0.0-rc.1+meta"))
        assertEquals(1, Semver.parse("1.2.3")!!.major)
        assertEquals(2, Semver.parse("1.2.3")!!.minor)
        assertEquals(3, Semver.parse("1.2.3")!!.patch)
    }

    @Test
    fun rejectsInvalidVersions() {
        assertNull(Semver.parse(null))
        assertNull(Semver.parse(""))
        assertNull(Semver.parse("1.2"))
        assertNull(Semver.parse("01.2.3"))
        assertNull(Semver.parse("1.02.3"))
        assertNull(Semver.parse("1.2.03"))
        assertNull(Semver.parse("1.2.3-"))
        assertNull(Semver.parse("1.2.3-01"))
        assertNull(Semver.parse("a.b.c"))
        assertNull(Semver.parse("1.2.3.4"))
        assertNull(Semver.parse("1.2.3+"))
        assertNull(Semver.parse("v1.2.3"))
    }

    @Test
    fun ordersCoreVersions() {
        assertTrue(Semver.parse("1.0.0")!! < Semver.parse("1.0.1")!!)
        assertTrue(Semver.parse("1.0.9")!! < Semver.parse("1.1.0")!!)
        assertTrue(Semver.parse("1.9.9")!! < Semver.parse("2.0.0")!!)
        assertEquals(0, Semver.parse("1.2.3")!!.compareTo(Semver.parse("1.2.3")!!))
    }

    @Test
    fun ordersPrereleasesPerSpec() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
            "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"
        ).map { Semver.parse(it)!! }
        for (i in 0 until ordered.size - 1) {
            assertTrue("${ordered[i]} should sort below ${ordered[i + 1]}", ordered[i] < ordered[i + 1])
        }
    }

    @Test
    fun buildMetadataIsIgnoredForPrecedence() {
        assertEquals(0, Semver.parse("1.0.0+a")!!.compareTo(Semver.parse("1.0.0+b")!!))
    }
}
