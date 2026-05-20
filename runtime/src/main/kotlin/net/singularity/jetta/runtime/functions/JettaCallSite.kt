package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Runtime dispatcher for `(head args...)` applications where `head` is not a
 * statically-known compiled-function Symbol — i.e. the cases JeTTa's static
 * codegen can't lower to a plain `INVOKESTATIC`. Covers variable-typed heads
 * (`($f x y)` in higher-order MeTTa code), Expression-typed heads (curried
 * `((curry +) 2)`), and any other call shape that escapes static dispatch.
 *
 * Resolution order at [dispatch]:
 *
 *  1. head is a [JettaFunction] (any compiled lambda — produced by the indy
 *     lambda machinery in [JettaLambdaMetafactory]). Invoke `head.apply(args)`.
 *  2. otherwise — head is not callable. Build a non-reduced expression
 *     `(head args...)` and return it as data. Matches the reference
 *     interpreter's behaviour: unresolvable applications stay inert until some
 *     other rule pattern-matches them.
 *
 * Planned extensions (not in Phase A.0):
 *  - Head is a [net.singularity.jetta.compiler.frontend.ir.Symbol] whose name
 *    has a compiled static method on the enclosing class → cached
 *    `MethodHandle` dispatch.
 *  - Head pattern matches a space-stored `(= <head-shape> ?)` rule → drive
 *    the rule via [net.singularity.jetta.runtime.JettaProgram.match] and recurse.
 *    Use `SwitchPoint` to invalidate compiled fast paths when space mutates.
 *
 * The bootstrap returns a [ConstantCallSite] for now — the same dispatcher
 * services every site. Per-site specialization is a later step.
 */
object JettaCallSite {

    @JvmStatic
    fun dispatch(head: Any?, args: Array<Any?>): Any? {
        return when (head) {
            is JettaFunction -> head.apply(args)
            else -> buildInertExpression(head, args)
        }
    }

    /**
     * Bootstrap method for `invokedynamic` JIT-eval sites. `invokedType` is
     * `(Object, Object[]) -> Object` — head plus arg array, result boxed.
     */
    @JvmStatic
    fun bootstrap(
        @Suppress("UNUSED_PARAMETER") caller: MethodHandles.Lookup,
        @Suppress("UNUSED_PARAMETER") name: String,
        invokedType: MethodType,
    ): CallSite {
        val target = INTERNAL_LOOKUP.findStatic(
            JettaCallSite::class.java,
            "dispatch",
            MethodType.methodType(Any::class.java, Any::class.java, Array<Any?>::class.java),
        )
        return ConstantCallSite(target.asType(invokedType))
    }

    private fun buildInertExpression(head: Any?, args: Array<Any?>): Expression {
        val atoms = ArrayList<Atom>(args.size + 1)
        atoms.add(toAtom(head))
        args.forEach { atoms.add(toAtom(it)) }
        return Expression(atoms)
    }

    private fun toAtom(value: Any?): Atom = when (value) {
        is Atom -> value
        null -> throw IllegalStateException(
            "JettaCallSite.dispatch received a null arg — call-site emission should always pre-box values"
        )
        else -> Grounded(value)
    }

    private val INTERNAL_LOOKUP: MethodHandles.Lookup = MethodHandles.lookup()
}
