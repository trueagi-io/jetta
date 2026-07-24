package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
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
        // A free-variable head reaches dispatch as an UNRESOLVED Variable object: `($x arg…)`
        // where `$x` was bound by an enclosing non-deterministic branch (b3_direct's
        // `($x (green $x))` binds `$x`=Fritz through `(green $x)`'s upward-propagated foliation).
        // Resolve it against the Matcher bindings BEFORE anything else — otherwise the inert form
        // `($x arg…)` is later fed to `reduceToFixedPoint`, where the still-free `$x` makes it a
        // *pattern* that unifies against the space `=` rules (pattern-var `$x` matches ANY rule
        // head → reduces down the wrong rule: `(green $x)` with rule-var `$x`=arg). With the head
        // resolved to its binding (Fritz — a non-function symbol), `(Fritz arg…)` is inert data,
        // its own normal form. A genuinely-free head is returned unchanged (Variable), preserving
        // the prior behaviour. Bindings are `Map<String, Atom>`, so this never yields a
        // JettaFunction; the compiled-lambda case is already handled above.
        val resolvedHead = if (head is Atom) Matcher.resolveBinding(head) else head
        // Compiled-binary variable-head dispatch (P1): when `head` is a Symbol naming a user
        // function of matching arity, LINK against its compiled method via the registry loaded
        // from `.jctx`. This is the AOT counterpart to the JIT-eval path below — it lets
        // `($f x)` reduce (grounded ops run, user fns invoke) in a `jetta` run that has no
        // JitEnv. Registry is empty in the REPL (JitEnv path handles it there), so this is a
        // no-op miss and the existing behaviour is unchanged. Arity mismatch (e.g. currying)
        // is not a P1 case — fall through.
        // Registry dispatch (P1 user fns + P3 grounded ops): resolve the head — a Symbol
        // (user function) or a Special/Symbol operator (`+`/`-`/`*`, applied through a variable
        // head by an interpreter that carries operators as data, `(ev (Bin $op …)) → ($op …)`)
        // — to a MethodHandle and invoke it. This is one indirect call, NOT a per-call operator
        // switch: P2's polymorphic-inline-cache then relinks the site to the resolved handle so
        // HotSpot inlines the hot op near-natively. A grounded op over non-numeric operands
        // returns null (not computable) → fall through to the inert form.
        opHeadName(resolvedHead)?.let { name ->
            JettaLinkRegistry.lookup(name)?.let { entry ->
                if (entry.paramTypes.size == args.size) {
                    val result = JettaLinkRegistry.invoke(entry, args)
                    if (result != null || !entry.groundedOp) return result
                }
            }
        }
        val callExpr = buildInertExpression(resolvedHead, args)
        // When a JIT env is installed (same-JVM: REPL / in-process run), EVALUATE the
        // application by compiling+running it. This is what makes higher-order code like
        // `(= (apply $f $x) ($f $x))` yield a value: `$f` bound to a function-naming Symbol
        // gives `(inc 5)`, which JIT-eval compiles to `INVOKESTATIC inc` and runs — so
        // grounded ops (+, *) evaluate and user functions LINK against their compiled
        // classes, neither of which the space-rule tree-walk below can do. Falls back to
        // rule reduction when no env is present (cross-JVM AOT binary).
        if (JitEnvRegistry.current() != null) {
            val results = JettaJit.eval(callExpr)
            return when (results.size) {
                0 -> callExpr          // produced nothing — leave the application inert
                1 -> results[0]        // the usual single-valued application
                else -> results        // non-deterministic application — return the bag
            }
        }
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
        val first = results.firstOrNull()
        if (first != null) {
            return if (first is BoundAtom) {
                Matcher.installBindings(first.bindings)
                first.atom
            } else first
        }
        // D3 increment C — `=`-as-reducible-head. A binary `(= a b)` that no direct `(= (= a b)
        // $r)` rule rewrote may still be a redex under a reflective rule `(= (= $x $x) T)`, which
        // needs its OPERANDS reduced applicatively first (`(= S (HumansAreMortal SocratesIsHuman))`
        // → `(= S S)` → T). Try that here. The reduced form is committed ONLY when it matches a
        // rule; otherwise this returns null and the expression stays inert UNCHANGED (its own
        // normal form) — so a program without such a rule is byte-identical.
        reduceEqOperands(spaceName, expr)?.let { return it }
        // D3 increment A — unified reducer: when NO space `(= expr $r)` rule applies, fall
        // back to the registry (grounded op / compiled user fn), exactly as [dispatch] does
        // for the TOP head. This is what makes a term PRODUCED mid-reduction reduce further:
        // `(((curry +) 2) 3)` rewrites (via the space rule) to `(+ 2 3)`, which has no `(=
        // (+ 2 3) $r)` fact — here the registry computes the grounded `+` → `5`. Reached only
        // on the space-rule miss, so the hot symbolic/backchain path (where a rule always
        // matches) stays byte-identical; the previously-dead-end miss now evaluates.
        return reduceViaRegistry(expr)
    }

    /**
     * Evaluate `expr` = `(head args…)` by resolving `head` to a registry entry (a grounded
     * operator such as `+`, or a compiled user function) and invoking it — the recursion-step
     * counterpart to [dispatch]'s top-head registry lookup. Returns null (leave inert) when the
     * head is not a registered op/function, the arity mismatches (a partial application like
     * `((curry +) 2)` — no matching entry, stays inert as MeTTa requires), the op is not
     * computable over its operands (a grounded op over non-numbers), or the result is a
     * non-determinism bag (which a single reduction step cannot represent — left inert here).
     */
    private fun reduceViaRegistry(expr: Expression): Atom? {
        val atoms = expr.atoms
        if (atoms.isEmpty()) return null
        val name = opHeadName(Matcher.resolveBinding(atoms[0])) ?: return null
        val entry = JettaLinkRegistry.lookup(name) ?: return null
        if (entry.multivalued) return null
        if (entry.paramTypes.size != atoms.size - 1) return null
        val args = arrayOfNulls<Any?>(atoms.size - 1)
        for (i in 1 until atoms.size) args[i - 1] = atoms[i]
        return JettaLinkRegistry.invoke(entry, args) as? Atom
    }

    /**
     * Increment C — reduce the operands of a binary `(= a b)` applicatively and, if the reduced
     * `(= a' b')` matches an `=`-rule (typically the reflective `(= (= $x $x) T)`), return that
     * rewrite. Only fires for a `=`-headed 2-operand expression; returns null (leave `(= a b)`
     * inert UNCHANGED) when nothing reduces or the reduced form matches no rule, so a program
     * without a reflective `=`-rule is byte-identical. Termination: operands are reduced to a
     * fixed point (its own budget + cycle guard) and the `unchanged` guard prevents re-entry.
     */
    private fun reduceEqOperands(spaceName: String, expr: Expression): Atom? {
        val atoms = expr.atoms
        if (atoms.size != 3) return null
        val head = atoms[0]
        if (opHeadName(head) != PATTERN_EQ) return null
        val a = reduceToFixedPoint(spaceName, atoms[1])
        val b = reduceToFixedPoint(spaceName, atoms[2])
        if (a == atoms[1] && b == atoms[2]) return null // neither operand reduced — nothing new
        val reduced = Expression(listOf(head, a, b))
        val r = Variable(REDUCE_VAR)
        val pattern = Expression(listOf(Special(PATTERN_EQ), reduced, r))
        val match = JettaProgram.match(spaceName, pattern, r).firstOrNull() ?: return null
        return if (match is BoundAtom) {
            Matcher.installBindings(match.bindings)
            match.atom
        } else match
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

    /** The name a dispatch head denotes — a Symbol's name or an operator Special's value. */
    private fun opHeadName(head: Any?): String? = when (val h = if (head is BoundAtom) head.atom else head) {
        is Symbol -> h.name
        is Special -> h.value
        else -> null
    }

    private fun buildInertExpression(head: Any?, args: Array<Any?>): Expression {
        val atoms = ArrayList<Atom>(args.size + 1)
        atoms.add(toAtom(head))
        args.forEach { atoms.add(toAtom(it)) }
        return Expression(atoms)
    }

    /**
     * Build the inert (non-reduced) form `(name args…)` for the **non-reduction
     * fallback** emitted by `FunctionGenerator.generateMatch`: when a compiled
     * equality-dispatch function is called and *no clause head matched* (every
     * guarded branch's guard failed), MeTTa leaves the expression unreduced as
     * its own normal form rather than yielding an empty result. The head is a
     * named [Symbol] (the [net.singularity.jetta.compiler.frontend.ir.FunctionDefinition]);
     * args are the runtime parameter values, already `Matcher.resolveBinding`-resolved
     * (Atom params) or boxed (primitive params) at the call site — [toAtom] wraps
     * any still-unboxed value in [Grounded], exactly as the dynamic-dispatch
     * [buildInertExpression] does, so both inert forms are structurally identical.
     */
    @JvmStatic
    fun nonReduced(name: String, args: Array<Any?>): Expression {
        val atoms = ArrayList<Atom>(args.size + 1)
        atoms.add(Symbol(name))
        args.forEach { atoms.add(toAtom(it)) }
        return Expression(atoms)
    }

    /**
     * Tier-0 dynamic fallback for COMPILED equality dispatch (the partial-eval
     * residual the AOT `==`-path can't handle). Used by the non-reduction fallback
     * in `FunctionGenerator.generateMatch`: when every compiled clause guard fails,
     * before declaring `(name args…)` non-reducible we try the canonical MeTTa rule
     * lookup `(= (name args…) $r)` by *unification* against the space. This binds
     * free-variable arguments the boolean `==`-dispatch cannot bind backward (e.g.
     * `(prevents (making $y) (making (air wet)))` unifies `$y = (air dry)` against
     * the fact rule) and returns the FULL non-determinism bag of matching `$r`s —
     * each still a `BoundAtom` carrying its own bindings, so the surrounding
     * `flat-map?`/`map?` (simpleFlatMap/simpleMap) foliates them per branch exactly
     * as for any other match result. Only when no rule unifies do we emit the inert
     * `(name args…)` — true non-reduction (its own normal form).
     *
     * Shares one resolution semantics with [reduceOnce]/[dispatch]; a future JIT
     * tier specializes the hot lookups but plugs into this same seam.
     */
    @JvmStatic
    fun reduceOrInert(spaceName: String, name: String, args: Array<Any?>): List<Atom> {
        val atoms = ArrayList<Atom>(args.size + 1)
        atoms.add(Symbol(name))
        args.forEach { atoms.add(resolveDeep(toAtom(it))) }
        val callExpr = Expression(atoms)
        val r = Variable(REDUCE_VAR)
        val pattern = Expression(listOf(Special(PATTERN_EQ), callExpr, r))
        val results = JettaProgram.match(spaceName, pattern, r)
        return results.ifEmpty { listOf(callExpr) }
    }

    /**
     * Resolve every bound variable in [atom], recursing into sub-expressions.
     * [Matcher.resolveBinding] is shallow (a top-level [Variable] only), but a
     * call argument is typically a compound like `(making $y)` whose variable is
     * bound by an *enclosing* non-deterministic branch (the cross-conjunct binding
     * in a deduction: `$y` produced by the outer `(makes $z $y)`, consumed by the
     * inner `(prevents (making $y) …)`). Without deep resolution the unification
     * lookup in [reduceOrInert] would see `$y` still free and re-bind it freely,
     * dropping the cross-branch constraint. Free (unbound) variables are left as-is
     * so the lookup can bind them.
     */
    private fun resolveDeep(atom: Atom): Atom = when (val resolved = Matcher.resolveBinding(atom)) {
        is Expression -> Expression(resolved.atoms.map { resolveDeep(it) })
        else -> resolved
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
