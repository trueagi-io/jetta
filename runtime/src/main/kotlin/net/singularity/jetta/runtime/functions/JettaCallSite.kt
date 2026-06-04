package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.JettaProgram
import net.singularity.jetta.runtime.Matcher
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
 *  2. otherwise — treat `(head args...)` as an expression to *evaluate* via the
 *     canonical MeTTa rule `eval(e) = match &self (= e $r) $r`, recursively to a
 *     fixed point (see [reduceToFixedPoint]). This is the "eval-as-runtime" core:
 *     `(= …)` rules stored as space atoms (every `(= lhs rhs)` is also a space
 *     fact — see `FunctionRewriter`) drive reduction by *unification*, so even
 *     free-variable arguments the compiled boolean path can't bind reduce here.
 *  3. if no rule matches, the expression is its own normal form — return the
 *     inert `(head args...)`. Matches the reference interpreter: unresolvable
 *     applications stay data until some other rule pattern-matches them.
 *
 * The space to query is baked into each indy call site at codegen time as a
 * bootstrap static argument (the owning module's space name — the same string
 * convention `JettaProgram.match` already uses), then bound into the dispatch
 * MethodHandle by [bootstrap]. No thread-local "current space" is needed.
 *
 * The bootstrap returns a [ConstantCallSite] — the same dispatcher (specialized
 * only by space name) services every site. Per-site MethodHandle caching and
 * `SwitchPoint` epoch invalidation are later (Phase B) steps.
 */
object JettaCallSite {

    /** Reduction budget — guards against non-terminating rule cycles like `(= (foo) (foo))`. */
    private const val MAX_REDUCTION_STEPS = 1024

    @JvmStatic
    fun dispatch(spaceName: String, head: Any?, args: Array<Any?>): Any? {
        if (head is JettaFunction) return head.apply(args)
        val callExpr = buildInertExpression(head, args)
        return reduceToFixedPoint(spaceName, callExpr)
    }

    /**
     * Canonical MeTTa evaluation: repeatedly rewrite `current` via a space rule
     * `(= current $r)` until it no longer changes (its normal form) or the
     * reduction budget / a cycle is hit. Non-Expression atoms and empty
     * expressions are already normal forms.
     */
    private fun reduceToFixedPoint(spaceName: String, atom: Atom): Atom {
        var current = atom
        val seen = HashSet<Atom>()
        var steps = 0
        while (current is Expression && current.atoms.isNotEmpty()) {
            if (steps++ >= MAX_REDUCTION_STEPS) break
            if (!seen.add(current)) break // cycle — bail at this symbolic fixed point
            val reduced = reduceOnce(spaceName, current) ?: break
            if (reduced == current) break // rule matched but produced the same term
            current = reduced
        }
        return current
    }

    /**
     * One rewrite step: query the space for `(= expr $r)` and return the
     * substituted `$r` of the first matching rule, or null if no rule applies.
     * Bindings carried on a [BoundAtom] result are installed into the current
     * [Matcher] frame so they propagate upward (the same convention
     * `JettaProgram.matchEval` uses), then the raw atom is unwrapped.
     */
    private fun reduceOnce(spaceName: String, expr: Expression): Atom? {
        val r = Variable(REDUCE_VAR)
        val pattern = Expression(listOf(Special(PATTERN_EQ), expr, r))
        val results = JettaProgram.match(spaceName, pattern, r)
        val first = results.firstOrNull() ?: return null
        return if (first is BoundAtom) {
            Matcher.getBindings().putAll(first.bindings)
            first.atom
        } else first
    }

    /**
     * Bootstrap method for `invokedynamic` JIT-eval sites. `invokedType` is
     * `(Object, Object[]) -> Object` — head plus arg array, result boxed.
     * [spaceName] is a static bootstrap argument baked at the call site (the
     * owning module's space name); it is bound as `dispatch`'s leading argument.
     */
    @JvmStatic
    fun bootstrap(
        @Suppress("UNUSED_PARAMETER") caller: MethodHandles.Lookup,
        @Suppress("UNUSED_PARAMETER") name: String,
        invokedType: MethodType,
        spaceName: String,
    ): CallSite {
        val target = INTERNAL_LOOKUP.findStatic(
            JettaCallSite::class.java,
            "dispatch",
            MethodType.methodType(
                Any::class.java,
                String::class.java,
                Any::class.java,
                Array<Any?>::class.java,
            ),
        )
        val bound = MethodHandles.insertArguments(target, 0, spaceName)
        return ConstantCallSite(bound.asType(invokedType))
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

    /** Mirror of `Predefined.PATTERN` — kept local to avoid a frontend-resolve dependency. */
    private const val PATTERN_EQ = "="
    private const val REDUCE_VAR = "__reduce_r"

    private val INTERNAL_LOOKUP: MethodHandles.Lookup = MethodHandles.lookup()
}
