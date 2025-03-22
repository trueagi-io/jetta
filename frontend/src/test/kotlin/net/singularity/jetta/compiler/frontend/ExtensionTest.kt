package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.resolve.parseDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue



class ExtensionTest {
    @Test
    fun `parse ()V`() {
        val desc = "()V"
        val res = desc.parseDescriptor()
        assertTrue(res.isNotEmpty())
        assertEquals("V", res[0])
    }

    @Test
    fun `parse (ID)V`() {
        val desc = "(ID)V"
        val res = desc.parseDescriptor()
        assertEquals(3, res.size)
        assertEquals("I", res[0])
        assertEquals("D", res[1])
        assertEquals("V", res[2])
    }

    @Test
    fun `parse (ILObject)String`() {
        val desc = "(ILjava/lang/Object;)Ljava/lang/String;"
        val res = desc.parseDescriptor()
        assertEquals(3, res.size)
        assertEquals("I", res[0])
        assertEquals("Ljava/lang/Object;", res[1])
        assertEquals("Ljava/lang/String;", res[2])
    }

    @Test
    fun `parse map signature`() {
//        val desc = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;TR;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;"
//        desc.parseDescriptor()
    }
}