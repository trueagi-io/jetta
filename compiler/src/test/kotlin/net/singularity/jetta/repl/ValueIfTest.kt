package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A value-producing `if` in argument position (not a return / not a statement). Before
 * the generateIf join-label fix this emitted fall-through bytecode — the then-branch fell
 * into the else, piling both values on the stack (ASM frame computation failed). Both
 * branches are homogeneous Ints here, isolating the control-flow fix.
 */
class ValueIfTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    @Test
    fun `if in argument position selects one branch`() {
        val r = repl()
        r.eval(
            """
            (: inc (-> Int Int))
            (= (inc _x) (+ _x 1))
            """.trimIndent().d()
        )
        r.eval("""!(inc (if (== 1 1) 10 20))""").let {
            assertTrue(it.isSuccess); assertEquals(11, it.result)
        }
        r.eval("""!(inc (if (== 1 2) 10 20))""").let {
            assertTrue(it.isSuccess); assertEquals(21, it.result)
        }
    }
}
