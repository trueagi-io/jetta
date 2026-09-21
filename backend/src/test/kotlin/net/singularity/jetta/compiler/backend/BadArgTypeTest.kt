package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end regression tests for eval-time `BadArgType` errors (phase D2.2).
 *
 * D2.2 tier (i): a grounded arithmetic op applied to a concrete non-numeric operand (String) is a
 * type error — the compiler emits the inert `(Error <expr> (BadArgType <pos> Number String))` atom
 * (compile-time static-error strategy, keeping the inline `IADD`/`DADD` fast path for well-typed
 * operands). Previously `(+ 2 "String")` crashed codegen (`castIfNeeded` String→INT `TODO`).
 *
 * Each `!(assertEqualToResult …)` throws AssertionError from `__main` on a wrong answer, so a green
 * `invoke` IS the assertion.
 */
class BadArgTypeTest : GeneratorTestBase() {

    private fun run(name: String, code: String, allowWarnings: Boolean = false) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                if (!allowWarnings) assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private fun runLenient(name: String, code: String) = run(name, code, allowWarnings = true)

    /** A String operand to `+`/`*` yields `(Error … (BadArgType pos Number String))`. */
    @Test
    fun `grounded arithmetic with a string operand is a BadArgType error`() = run(
        "BadArgArith",
        """
            !(assertEqualToResult
              (+ 2 "String")
              ((Error (+ 2 "String") (BadArgType 2 Number String))))
            !(assertEqualToResult
              (* "x" 3)
              ((Error (* "x" 3) (BadArgType 1 Number String))))
        """.trimIndent()
    )

    /**
     * The error is identity-precise: an identical `(+ …)` appearing inside quoted expected data is
     * emitted verbatim, NOT turned into a nested error (guards the structural-replacement hazard).
     */
    @Test
    fun `an identical arithmetic term in quoted data is preserved`() = run(
        "BadArgQuoted",
        """
            !(assertEqualToResult
              (+ 1 "s")
              ((Error (+ 1 "s") (BadArgType 2 Number String))))
        """.trimIndent()
    )

    /** Well-typed numeric arithmetic stays on the fast path (no false positives). */
    @Test
    fun `well-typed arithmetic is unaffected`() = run(
        "BadArgOk",
        """
            !(assertEqual (+ 2 3) 5)
            !(assertEqual (* 2 (- 7 3)) 8)
        """.trimIndent()
    )

    /**
     * D2.4 (increment 1): `==`/`!=` are grounded `(-> $t $t Bool)`, so a concrete numeric-vs-String
     * operand mismatch is an eval-time type error — the node is stamped ATOM by the resolver and
     * codegen emits the `(Error … (BadArgType …))` VALUE instead of the integer-comparison path
     * (which would VerifyError over a String operand). `$t` binds to the first operand's type, so
     * the position/expected/actual mirror hyperon's left-to-right binding.
     */
    @Test
    fun `grounded comparison with a string operand is a BadArgType error`() = run(
        "BadArgCmp",
        """
            !(assertEqualToResult
              (== 5 "S")
              ((Error (== 5 "S") (BadArgType 2 Number String))))
            !(assertEqualToResult
              (== "S" 5)
              ((Error (== "S" 5) (BadArgType 2 String Number))))
            !(assertEqualToResult
              (!= 5 "S")
              ((Error (!= 5 "S") (BadArgType 2 Number String))))
        """.trimIndent()
    )

    /** Well-typed comparisons keep the Bool path — no false positives on same-type operands. */
    @Test
    fun `well-typed comparison is unaffected`() = run(
        "BadArgCmpOk",
        """
            !(assertEqual (== 5 5) True)
            !(assertEqual (== 5 6) False)
            !(assertEqual (== "a" "a") True)
            !(assertEqual (== "a" "b") False)
        """.trimIndent()
    )

    /**
     * D2.4 (increment 2): a structural `==` of two custom-typed operands whose declared `:` types
     * do not unify is an eval-time BadArgType. Since the types are `:` facts, this is a RUNTIME
     * check (`JettaProgram.typeCheckError`): `SocratesIsHuman : (Human Socrates)` and
     * `SamIsMortal : (Mortal Sam)` don't unify, so `$t` (bound to arg1's type) rejects arg2 →
     * `(BadArgType 2 (Human Socrates) (Mortal Sam))`.
     */
    @Test
    fun `custom-type comparison mismatch is a BadArgType error`() = runLenient(
        "BadArgCmpCustom",
        """
            (: Entity Type)
            (: Human (-> Entity Type))
            (: Mortal (-> Entity Type))
            (: SocratesIsHuman (Human Socrates))
            (: SamIsMortal (Mortal Sam))
            !(assertEqualToResult
              (== SocratesIsHuman SamIsMortal)
              ((Error (== SocratesIsHuman SamIsMortal) (BadArgType 2 (Human Socrates) (Mortal Sam)))))
        """.trimIndent()
    )

    /**
     * No false positives on the structural path: well-typed custom operands compare as Bool, and
     * undeclared (gradual) symbols unify with anything — neither errors.
     */
    @Test
    fun `well-typed and gradual custom comparisons are unaffected`() = runLenient(
        "BadArgCmpCustomOk",
        """
            (: Entity Type)
            (: Human (-> Entity Type))
            (: Mortal (-> Entity Type))
            (: SocratesIsHuman (Human Socrates))
            !(assertEqualToResult (== Mortal Human) (False))
            !(assertEqualToResult (== Human Human) (True))
            !(assertEqualToResult (== Foo Bar) (False))
            !(assertEqualToResult (== SocratesIsHuman SocratesIsHuman) (True))
        """.trimIndent()
    )

    /**
     * D2.3: a `(: f (-> …))`-declared user function type-checks its args before reducing. `(Add S
     * Z)` errors (S : (-> Nat Nat), param 1 wants Nat) instead of matching `(= (Add $x Z) $x)`;
     * a well-typed call reduces normally; a gradual/undeclared argument (`Something`) is allowed.
     * Types are declared before the runs (JeTTa loads all facts up front; b5's run-before-declare
     * ordering is a separate top-level-semantics concern).
     */
    @Test
    fun `declared user function errors on a mistyped argument`() = runLenient(
        "BadArgUserFn",
        $$"""
            (: Z Nat)
            (: S (-> Nat Nat))
            (: Add (-> Nat Nat Nat))
            (= (Add $x Z) $x)
            (= (Add $x (S $y)) (Add (S $x) $y))
            !(assertEqualToResult (Add S Z) ((Error (Add S Z) (BadArgType 1 Nat (-> Nat Nat)))))
            !(assertEqual (Add (S Z) Z) (S Z))
            !(assertEqual (Add Z (S Z)) (S Z))
            !(assertEqual (Add Something Z) Something)
        """.trimIndent()
    )

    /** An undeclared (untyped) function is NOT instrumented — reduces without a type check. */
    @Test
    fun `undeclared function is not type-checked`() = runLenient(
        "BadArgUntyped",
        $$"""
            (= (pick $x $y) $x)
            !(assertEqual (pick A B) A)
        """.trimIndent()
    )

    /**
     * D2.4 (increment 4): errors are absorbing — an inert/undefined application whose argument
     * reduces to an `(Error …)` yields that error, not `(f (Error …))`. `f` is declared but has no
     * `=` rule, so `(f (+ 5 "S"))` is an inert head applied to a reducible-error argument; reducing
     * the argument surfaces the arithmetic error as the whole application's value.
     */
    @Test
    fun `an inner grounded error surfaces through an undefined function`() = runLenient(
        "BadArgNested",
        $$"""
            (: f (-> $t Number))
            !(assertEqualToResult
              (f (+ 5 "S"))
              ((Error (+ 5 "S") (BadArgType 2 Number String))))
            !(assertEqualToResult
              (f (== 5 "S"))
              ((Error (== 5 "S") (BadArgType 2 Number String))))
        """.trimIndent()
    )

    /**
     * No over-firing: an inert application with NO reducible-error argument stays symbolic data
     * (an undefined constructor is not turned into an error just because it appears in an actual
     * position).
     */
    @Test
    fun `an inert application without an error argument is preserved`() = runLenient(
        "BadArgInert",
        """
            !(assertEqualToResult (Foo A B) ((Foo A B)))
        """.trimIndent()
    )

    // --- the check reaches a function whose rule does not destructure (b5 of-same-type) ------

    /**
     * b5's `of-same-type`. Its rule `(= (of-same-type $x $y) T)` binds its parameters and
     * destructures nothing, so the body carries no `Match` — and the type-check prologue used to
     * be emitted from `generateMatch` alone, which meant a linear-rule function was never checked
     * at all and answered `T` for every pair. Both operands share one tvar, so the second one is
     * the error position once the first has bound it.
     */
    @Test
    fun `a linear-rule declared function is type-checked`() = runLenient(
        "BadArgLinearRule",
        $$"""
            (: Color Property)
            (: Green Color)
            (: Red Color)
            (: Shape Property)
            (: Circle Shape)
            (: of-same-type (-> $t $t Type))
            (= (of-same-type $x $y) T)
            !(assertEqual (of-same-type Color Shape) T)
            !(assertEqual (of-same-type Green Red) T)
            !(assertEqualToResult
              (of-same-type Green Color)
              ((Error (of-same-type Green Color) (BadArgType 2 Color Property))))
            !(assertEqualToResult
              (of-same-type Green Circle)
              ((Error (of-same-type Green Circle) (BadArgType 2 Color Shape))))
        """.trimIndent()
    )

    /**
     * The destructuring flavour keeps working — the prologue moved from the top of `generateMatch`
     * to the top of the function body, which for a Match-bodied function is the same place.
     */
    // --- an INERT application is checked too (b5 Cons) -------------------------------------

    /**
     * b5's list. `Cons` has a declared arrow but no `=` rule, so it is data: no compiled
     * function, no type-check prologue, and until the check moved to where the term is BUILT
     * nothing held `(Cons S (Cons Z Nil))` against `(: Cons (-> $t (List $t) (List $t)))`.
     *
     * The second assertion is also the guard for the structural-replacement hazard: its
     * EXPECTED side is quoted data containing that same mistyped term inside `(Error …)`. The
     * check looks at the quoted term's own head and never descends, so the expected side is
     * emitted verbatim; were it to recurse, this would come out doubly wrapped and fail.
     */
    @Test
    fun `an inert application is type-checked against its declared arrow`() = runLenient(
        "BadArgCons",
        $$"""
            (: List (-> Type Type))
            (: Nil (List $t))
            (: Cons (-> $t (List $t) (List $t)))
            (: Z Nat)
            (: S (-> Nat Nat))
            !(assertEqualToResult
               (Cons (S Z) (Cons Z Nil))
              ((Cons (S Z) (Cons Z Nil))))
            !(assertEqualToResult
              (Cons S (Cons Z Nil))
              ((Error (Cons S (Cons Z Nil)) (BadArgType 2 (List (-> Nat Nat)) (List Nat)))))
        """.trimIndent()
    )

    /**
     * A name given a type that is NOT an arrow declares no application shape, so an expression
     * headed by it is ordinary data — `declaredArrowNames` excludes it and no check is emitted.
     */
    @Test
    fun `an application headed by a non-arrow declared name is left alone`() = runLenient(
        "BadArgNonArrow",
        """
            (: Color Property)
            (: Green Color)
            !(assertEqualToResult (Green Sam) ((Green Sam)))
            !(assertEqualToResult (Color Green) ((Color Green)))
        """.trimIndent()
    )

    @Test
    fun `a destructuring declared function is still type-checked`() = runLenient(
        "BadArgMatchRule",
        $$"""
            (: Z Nat)
            (: S (-> Nat Nat))
            (: eq (-> $t $t Type))
            (= (eq $x $x) T)
            !(assertEqual (eq Z Z) T)
            !(assertEqualToResult (eq Z (S Z)) ((eq Z (S Z))))
            !(assertEqualToResult (eq Z S) ((Error (eq Z S) (BadArgType 2 Nat (-> Nat Nat)))))
        """.trimIndent()
    )

    // --- …and NOT emitted where it provably cannot fire ------------------------------------

    /** The method names invoked from [method] of the compiled class. */
    private fun calleesOf(result: CompilationResult, method: String): Set<String> {
        val callees = mutableSetOf<String>()
        ClassReader(result.bytecode).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name != method) null
                    else object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            callees += name
                        }
                    }
            },
            0,
        )
        return callees
    }

    private fun compiledClass(name: String, code: String): CompilationResult =
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .first.first { it.className == name }

    /**
     * `bench/programs/diff.metta`'s `deriv`, declared with the META-type `Atom`: `TypeEngine.checkApp`
     * `continue`s past such a parameter without even inferring the argument, so the prologue cannot
     * answer an error on any input — and it cost 35% of that benchmark's run time proving it.
     *
     * The two programs differ in exactly ONE token, the declared parameter type, which is the
     * distinction the guard has to make and the one `GroundedType.ATOM` erases: `Nat` is unknown to
     * `asType()` and so is represented as `ATOM` just like the meta-type. The assertion has to read
     * the BYTECODE because the elided check is a tautology — nothing observable changes with it.
     */
    @Test
    fun `a meta-Atom-declared function gets no type-check prologue`() {
        val rules = $$"""
            (= (deriv (Num $c) $x) (Num 0))
            (= (deriv (Add $a $b) $x) (Add (deriv $a $x) (deriv $b $x)))
        """.trimIndent()

        val metaAtom = compiledClass("NoCheckMetaAtom", "(: deriv (-> Atom Atom Atom))\n$rules")
        assertFalse("typeCheckError" in calleesOf(metaAtom, "deriv"))

        val concrete = compiledClass("NoCheckConcrete", "(: deriv (-> Nat Nat Nat))\n$rules")
        assertTrue("typeCheckError" in calleesOf(concrete, "deriv"))
    }

    /** And such a function reduces exactly as it did — the elided check never fired. */
    @Test
    fun `a meta-Atom-declared function reduces unchanged`() = runLenient(
        "NoCheckAtomRun",
        $$"""
            (: deriv (-> Atom Atom Atom))
            (= (deriv (Num $c) $x) (Num 0))
            (= (deriv (Var $v) $x) (if (== $v $x) (Num 1) (Num 0)))
            (= (deriv (Add $a $b) $x) (Add (deriv $a $x) (deriv $b $x)))
            !(assertEqual (deriv (Add (Var x) (Num 3)) x) (Add (Num 1) (Num 0)))
            !(assertEqual (deriv (Var y) x) (Num 0))
        """.trimIndent()
    )
}
