package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultivaluedFunctionRewriteTest : BaseFrontendTest() {
    private val mapImpl = JvmMethod(
        owner = "",
        name = "",
        descriptor = "",
        signature = null,
    )
    private val flatMapImpl = JvmMethod(
        owner = "",
        name = "",
        descriptor = "",
        signature = null,
    )
    private fun List<Atom>.findFunctionDefinition(name: String): FunctionDefinition? =
        find { it is FunctionDefinition && it.name == name } as FunctionDefinition?

    private fun Atom.assertCallWithNoArgs(name: String) {
        assertTrue { this is Expression }
        (this as Expression).let { expr ->
            assertEquals(1, expr.atoms.size)
            assertTrue(expr.atoms[0] is Symbol)
            assertEquals(name, (expr.atoms[0] as Symbol).name)
        }
    }

    @Test
    fun rewriteCall1Arg() =
        resolve(
            "RewriteCall1Arg.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int))
            (= (f _x) (+ _x 1))
            
            (@ bar multivalued)
            (: bar (-> Int Int))
            (= (bar _x) (f (foo)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            // (map (\ ($__var0) ; λ: Int -> Int
            //     (f $__var0)) (foo))
            val def = result.code.findFunctionDefinition("bar")
            assertNotNull(def)
            assertEquals(3, def.body.atoms.size)
            assertEquals(PredefinedAtoms.MAP_, def.body.atoms[0])
            assertTrue { def.body.atoms[1] is Lambda }
            (def.body.atoms[1] as Lambda).let { lambda ->
                assertNotNull(lambda.arrowType)
                assertEquals(listOf(GroundedType.INT, GroundedType.INT), lambda.arrowType!!.types)
                assertEquals(1, lambda.params.size)
                assertEquals("__var0", lambda.params[0].name)
                lambda.body.let { body ->
                    assertEquals(2, body.atoms.size)
                    assertTrue(body.atoms[0] is Symbol)
                    assertEquals("f", (body.atoms[0] as Symbol).name)
                    assertTrue(body.atoms[1] is Variable)
                    assertEquals("__var0", (body.atoms[1] as Variable).name)
                    assertNotNull(body.resolved)
                }
            }
            def.body.atoms[2].assertCallWithNoArgs("foo")
        }

    @Test
    fun rewriteCall2Args() =
        resolve(
            "RewriteCall2Args.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int Int))
            (= (f _x _y) (+ _x _y))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo) (foo)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            // (flat-map? (\ ($__var1)   ; λ: Int -> Int
            //     (map? (\ ($__var0) ; λ: Int -> Int
            //         (f $__var0 $__var1)) (foo))) (foo))
            println(result)
            val def = result.code.findFunctionDefinition("bar")
            assertNotNull(def)
            assertEquals(3, def.body.atoms.size)
            assertEquals(PredefinedAtoms.FLAT_MAP_, def.body.atoms[0])
            assertTrue { def.body.atoms[1] is Lambda }
            (def.body.atoms[1] as Lambda).let { lambda ->
                assertNotNull(lambda.arrowType)
                assertEquals(listOf(GroundedType.INT, SeqType(GroundedType.INT)), lambda.arrowType!!.types)
                assertEquals(1, lambda.params.size)
                assertEquals("__var1", lambda.params[0].name)
                assertEquals(PredefinedAtoms.MAP_, lambda.body.atoms[0])
                assertTrue { lambda.body.atoms[1] is Lambda }
                (lambda.body.atoms[1] as Lambda).let { lambda1 ->
                    assertNotNull(lambda1.arrowType)
                    assertEquals(listOf(GroundedType.INT, GroundedType.INT), lambda1.arrowType!!.types)
                    assertEquals("__var0", lambda1.params[0].name)
                    lambda1.body.let { body ->
                        assertEquals(3, body.atoms.size)
                        assertTrue(body.atoms[0] is Symbol)
                        assertEquals("f", (body.atoms[0] as Symbol).name)
                        assertTrue(body.atoms[1] is Variable)
                        assertTrue(body.atoms[2] is Variable)
                        assertEquals("__var0", (body.atoms[1] as Variable).name)
                        assertEquals("__var1", (body.atoms[2] as Variable).name)
                        assertNotNull(body.resolved)
                    }
                }
                lambda.body.atoms[2].assertCallWithNoArgs("foo")
            }
            def.body.atoms[2].assertCallWithNoArgs("foo")
        }

    @Test
    fun rewriteExpression() =
        resolve(
            "RewriteExpression.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int Int))
            (= (bar _x) (+ (foo) _x))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            // (map? (\ ($__var0) ; λ: Int -> Int
            //     (+ $__var0 $x)) (foo))
            println(result)
            val def = result.code.findFunctionDefinition("bar")
            assertNotNull(def)
            assertEquals(3, def.body.atoms.size)
            assertEquals(PredefinedAtoms.MAP_, def.body.atoms[0])
            assertTrue { def.body.atoms[1] is Lambda }
            (def.body.atoms[1] as Lambda).let { lambda ->
                assertNotNull(lambda.arrowType)
                assertEquals(listOf(GroundedType.INT, GroundedType.INT), lambda.arrowType!!.types)
                assertEquals(1, lambda.params.size)
                assertEquals("__var0", lambda.params[0].name)
                lambda.body.let { body ->
                    assertEquals(3, body.atoms.size)
                    assertTrue(body.atoms[0] is Special)
                    assertEquals("+", (body.atoms[0] as Special).value)
                    assertTrue(body.atoms[1] is Variable)
                    assertEquals("__var0", (body.atoms[1] as Variable).name)
                    assertEquals("x", (body.atoms[2] as Variable).name)
                }
            }
            def.body.atoms[2].assertCallWithNoArgs("foo")
        }


    @Test
    fun rewriteNestedExpression() =
        resolve(
            "RewriteExpression.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int Int))
            (= (bar _x) (+ (foo) (* _x (foo))))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            // (flat-map? (\ ($__var1)
            //      (map? (\ ($__var0)
            //            (+ $__var0 (* $x $__var1)) (foo)))
            println(result)
            val def = result.code.findFunctionDefinition("bar")
            assertNotNull(def)
            assertEquals(3, def.body.atoms.size)
            assertEquals(PredefinedAtoms.FLAT_MAP_, def.body.atoms[0])
            assertTrue { def.body.atoms[1] is Lambda }
            (def.body.atoms[1] as Lambda).let { lambda ->
                assertNotNull(lambda.arrowType)
                assertEquals(listOf(GroundedType.INT, SeqType(GroundedType.INT)), lambda.arrowType!!.types)
                assertEquals(1, lambda.params.size)
                assertEquals("__var1", lambda.params[0].name)
                assertEquals(PredefinedAtoms.MAP_, lambda.body.atoms[0])
                assertTrue { lambda.body.atoms[1] is Lambda }
                (lambda.body.atoms[1] as Lambda).let { lambda1 ->
                    assertNotNull(lambda1.arrowType)
                    assertEquals(listOf(GroundedType.INT, GroundedType.INT), lambda1.arrowType!!.types)
                    assertEquals("__var0", lambda1.params[0].name)
                    lambda1.body.let { body ->
                        assertEquals(3, body.atoms.size)
                        assertTrue(body.atoms[0] is Special)
                        assertEquals("+", (body.atoms[0] as Special).value)
                        assertTrue(body.atoms[1] is Variable)
                        assertTrue(body.atoms[2] is Expression)
                        assertEquals("__var0", (body.atoms[1] as Variable).name)
                        (body.atoms[2] as Expression).let { expression ->
                            assertEquals("*", (expression.atoms[0] as Special).value)
                            assertEquals("x", (expression.atoms[1] as Variable).name)
                            assertEquals("__var1", (expression.atoms[2] as Variable).name)
                        }
                    }
                }
                lambda.body.atoms[2].assertCallWithNoArgs("foo")
            }
            def.body.atoms[2].assertCallWithNoArgs("foo")
        }

    @Test
    fun rewriteIfCond() =
        resolve(
            "RewriteIf.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) 1 0)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            println(result)
            // FIXME: check all things
        }

    @Test
    fun rewriteIfCondWithBranch() =
        resolve(
            "RewriteIfWithBranch.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) (+ 1 (foo)) 0)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, _) ->
            println(result)
            // FIXME: check all things
        }
}