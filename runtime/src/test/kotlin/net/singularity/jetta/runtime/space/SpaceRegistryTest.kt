package net.singularity.jetta.runtime.space

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SpaceRegistryTest {

    @BeforeTest
    fun clean() = SpaceRegistry.reset()

    @AfterTest
    fun cleanup() = SpaceRegistry.reset()

    @Test
    fun `register and get round trip`() {
        val id = SpaceId.FromModule("foo")
        val space = SpaceImpl()
        SpaceRegistry.register(id, space)
        assertSame(space, SpaceRegistry.get(id))
    }

    @Test
    fun `get returns null for unregistered id`() {
        assertNull(SpaceRegistry.get(SpaceId.FromModule("missing")))
    }

    @Test
    fun `getOrCreate creates a fresh SpaceImpl when absent`() {
        val id = SpaceId.FromModule("lazy")
        val first = SpaceRegistry.getOrCreate(id)
        val second = SpaceRegistry.getOrCreate(id)
        assertSame(first, second, "subsequent calls return the same instance")
    }

    @Test
    fun `reset clears all registrations`() {
        SpaceRegistry.register(SpaceId.FromModule("a"), SpaceImpl())
        SpaceRegistry.register(SpaceId.FromModule("b"), SpaceImpl())
        SpaceRegistry.reset()
        assertNull(SpaceRegistry.get(SpaceId.FromModule("a")))
        assertNull(SpaceRegistry.get(SpaceId.FromModule("b")))
    }

    @Test
    fun `distinct SpaceIds map to distinct spaces`() {
        val a = SpaceRegistry.getOrCreate(SpaceId.FromModule("alpha"))
        val b = SpaceRegistry.getOrCreate(SpaceId.FromModule("beta"))
        assertNotSame(a, b)
    }

    @Test
    fun `register overwrites existing entry`() {
        val id = SpaceId.FromModule("foo")
        val first = SpaceImpl()
        val second = SpaceImpl()
        SpaceRegistry.register(id, first)
        SpaceRegistry.register(id, second)
        assertSame(second, SpaceRegistry.get(id))
        assertEquals(2, 2)  // sanity
    }
}
