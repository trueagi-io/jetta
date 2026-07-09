package net.singularity.jetta.compiler.frontend.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayMapTest {

    @Test
    fun `reads fall through to parent`() {
        val parent = mapOf("a" to 1, "b" to 2)
        val overlay = OverlayMap(parent)
        assertEquals(1, overlay["a"])
        assertEquals(2, overlay["b"])
        assertTrue(overlay.containsKey("a"))
        assertNull(overlay["missing"])
    }

    @Test
    fun `local writes shadow parent and do not mutate it`() {
        val parent = mutableMapOf("a" to 1)
        val overlay = OverlayMap<String, Int>(parent)
        overlay["a"] = 99
        overlay["c"] = 3
        assertEquals(99, overlay["a"])
        assertEquals(3, overlay["c"])
        // Parent is untouched — the COW guarantee a fork relies on.
        assertEquals(1, parent["a"])
        assertFalse(parent.containsKey("c"))
    }

    @Test
    fun `iteration merges parent and local with local winning`() {
        val parent = mapOf("a" to 1, "b" to 2)
        val overlay = OverlayMap(parent)
        overlay["b"] = 20
        overlay["c"] = 30
        assertEquals(mapOf("a" to 1, "b" to 20, "c" to 30), overlay.toMap())
        assertEquals(3, overlay.size)
    }

    @Test
    fun `remove only affects the local layer`() {
        val parent = mutableMapOf("a" to 1)
        val overlay = OverlayMap<String, Int>(parent)
        overlay["b"] = 2
        assertEquals(2, overlay.remove("b"))
        assertNull(overlay["b"])
        // Removing a parent-only key is a no-op on the parent.
        assertNull(overlay.remove("a"))
        assertEquals(1, parent["a"])
    }
}
