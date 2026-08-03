package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued

class CanonicalFormRewriter(
    val messageCollector: MessageCollector,
    private val context: Context,
) : Rewriter {
    companion object {
        // Grounded functions that consume the whole bag of results of each argument
        // (a non-determinism barrier). A bare multivalued call in their argument
        // position is passed as a List rather than lifted into a map/flat-map.
        //
        // `assertEqual`/`assertEqualToResult` are barriers: they compare the FULL bag
        // produced by each argument (multiset compare in Assertions.kt). Lifting a
        // multivalued arg into a map/flat-map would instead call the barrier once per
        // value, yielding a singleton on the actual side — see b4_nondeterm. They
        // depend on the non-reduction fallback (commit 4ebe9d0) so a non-matching call
        // like `(eq Green Blue)` reduces to itself rather than to an empty bag.
        private val BARRIER_FUNCTIONS = setOf("collapse", "assertEqual", "assertEqualToResult", "msort", "once", "unique")
    }

    private var variableCount = 0
    private var functionCount = 0
    private val multivaluedCalls: MutableMap<Int, Int> = mutableMapOf()
    private val multivaluedCallsInverse: MutableMap<Int, MutableList<Pair<Int, Expression>>> = mutableMapOf()
    private val multivaluedAtoms: MutableSet<Int> = mutableSetOf()
    private val functions = mutableListOf<FunctionDefinition>()

    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.map { functions.add(it as FunctionDefinition) }
        var i = 0
        while (i < functions.size) {
            val def = functions[i]
            val expression = def.copy(body = rewriteFunction(source.getJvmClassName(), def), position = def.position)
            result.add(expression)
            i++
        }

        // clean up
        multivaluedCalls.clear()
        multivaluedCallsInverse.clear()
        multivaluedAtoms.clear()
        functions.clear()

        return ParsedSource(source.filename, result)
    }

    private fun rewriteFunction(owner: String, functionDefinition: FunctionLike): Atom =
        if (functionDefinition is FunctionDefinition && functionDefinition.name != FunctionRewriter.MAIN) {
            when (val body = functionDefinition.body) {
                is Match -> {
                    Match(
                        branches = body.branches.map { branch ->
                            val newBody = extractIfStatementsIfNeeded(owner, branch.body, root = true)
                            collectNonDeterministicAtomsRecursively(newBody, functionDefinition, newBody.id)
                            val rewritten = rewriteAtom(newBody)
                            // Reset state for next branch
                            multivaluedCalls.clear()
                            multivaluedCallsInverse.clear()
                            multivaluedAtoms.clear()
                            MatchBranch(branch.cond, rewritten, branch.destructuredBindings, branch.sourceOrdinal)
                        },
                        returnType = body.returnType,
                        position = body.position
                    )
                }
                else -> {
                    val newBody = extractIfStatementsIfNeeded(owner, body, root = true)
                    // Pass newBody.id as reducedScopeId — same as the Match branch
                    // path above. Without this, when the body is a single bare
                    // multivalued call like `(= (green $x) (frog $x))`, the scope
                    // registration uses the parameter variable's scope.id (the
                    // function scope), while rewriteExpression looks up by the
                    // call's expression.id (the body itself). Those keys
                    // disagreed, so multivaluedCallsInverse came back null and
                    // the map/flat-map wrapper was never generated — the body
                    // collapsed to a free `$__varN` whose at-runtime value is a
                    // fresh Variable atom (mis-cast as List by the caller's
                    // simpleMap). Pinning the scope to the body's own id keeps
                    // both sides consulting the same bucket.
                    collectNonDeterministicAtomsRecursively(newBody, functionDefinition, newBody.id)
                    rewriteAtom(newBody)
                }
            }
        } else functionDefinition.body

    private fun extractIfStatementsIfNeeded(owner: String, atom: Atom, root: Boolean): Atom {
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) {
                    return atom
                }
                if (!root && atom.atoms[0].isIf() && atom.checkIsNonDeterministicRecursively()) {
                    val functionName = mkFunctionName(functionCount++)
                    val (params, arrowType) = extractVariables(atom)
                    val def = FunctionDefinition(
                        functionName,
                        params,
                        arrowType,
                        atom,
                        annotations = mutableListOf(PredefinedAtoms.MULTIVALUED),
                    )
                    functions.add(def)
                    context.resolveFunctionDefinition(owner, def)
                    val result = Expression(Symbol(functionName), *params.toTypedArray(), position = atom.position)
                    result.resolved = context.resolve(functionName)
                    // return function call
                    return result
                } else {
                    return Expression(
                        atoms = atom.atoms.map { extractIfStatementsIfNeeded(owner, it, false) },
                        type = atom.type,
                        id = atom.id,
                        resolved = atom.resolved,
                        position = atom.position
                    )
                }
            }

            else -> return atom
        }
    }

    private fun extractVariables(atom: Atom): Pair<List<Variable>, ArrowType> {
        fun extractVariablesRecursively(atom: Atom, variables: MutableMap<String, Variable>) {
            when (atom) {
                is Variable -> {
                    variables[atom.name] = atom
                }

                is Expression -> atom.atoms.forEach { extractVariablesRecursively(it, variables) }
                else -> {}
            }
        }

        val variables = mutableMapOf<String, Variable>()
        extractVariablesRecursively(atom, variables)
        val variablesList = variables.values.toList()
        return variablesList to ArrowType(variablesList.map { it.type!! } + atom.type!!)
    }

    private fun mkFunctionName(i: Int): String = "__f$i"

    private fun Atom.isIf(): Boolean = (this is Special && this.value == Predefined.IF)

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) atom
                else if (atom.atoms[0].isIf()) rewriteIf(atom)
                else rewriteExpression(atom)
            }

            // Descend into a lambda body (the `(\ $e …)` a `let` desugars to, or an explicit
            // `\`): its `if`s are homogenized and its multivalued calls wrapped, using the
            // registrations `collect` populated for the lambda's scope.
            is Lambda -> atom.copy(body = rewriteAtom(atom.body))

            else -> atom
        }

    /*
    Consider following program:

    ```
    (@ foo multivalued)
    (= (foo) (seq 1 2 3))

    (: f (-> Int Int))
    (= (f $x) (+ $x 1))

    (@ bar multivalued)
    (= (bar $x) (f (foo)))
    ```

    `(f (foo))` will be rewritten to
    `(map? (\ ($x1) (f $x1)) (foo))`


    in the case of
    ```
    (: f (-> Int Int Int))
    (= (f $x $y) (+ $x $y))
    ```
    `(f (foo) (foo))` will be rewritten to
    `(flat-map? (\ ($x1) -> (map? (\ ($y1) (f $x1 $y1)) (foo))) (foo))`

    but
    `(f 2 (foo))` will be rewritten to
    `(map? (\ ($x1) (f 2 $x1)) (foo))`
     */
    private fun rewriteExpression(expression: Expression): Atom {
        if (expression.atoms.isEmpty()) return expression

        fun createMaps(
            replacement: List<Pair<Int, Expression>>,
            expression: Atom,
            op: Atom = PredefinedAtoms.MAP_
        ): Atom {
            if (replacement.isEmpty()) return expression
            val funcName = (replacement[0].second.atoms[0] as Symbol).name
            return createMaps(
                replacement.drop(1), Expression(
                    op,
                    Lambda(
                        listOf(mkVariable(replacement[0].first, expression.position)),
                        getArrayTypeForFunc(op, funcName),
                        expression,
                        position = expression.position
                    ),
                    replacement[0].second,
                    position = expression.position
                ),
                op = PredefinedAtoms.FLAT_MAP_
            )
        }
        if (!expression.isNonDeterministic()) return expression

        // A multivalued COMPOUND argument — e.g. `(And C1 C2)` where C1/C2 are
        // themselves multivalued calls — is a non-deterministic function call, not a
        // simple registered leaf call. Its parent must be lifted OVER its result bag:
        // `(ift (And …) T)` → `flat-map?(\$c. (ift $c T), (And …))`. Without this the
        // parent receives the whole bag as a single argument (b4's
        // `(ift <bag> (stop $z))` → `toAtom(List)` crash). Such args are collected
        // here, replaced by a fresh variable in the body, and lifted out below.
        // A barrier (assertEqual/assertEqualToResult/collapse) consumes the WHOLE bag
        // of each argument, so a multivalued compound argument here must be inlined
        // (handed over as its full List result), NOT lifted — same rule the simple-call
        // path already follows via the `barrierArg` flag in collection.
        val isBarrierParent = (expression.atoms.getOrNull(0) as? Symbol)?.name in BARRIER_FUNCTIONS
        // Parameters declared inert-ATOM (currently only `get-type`, JvmMethod.inertAtomParams)
        // must reach the callee as the UN-reduced term. A multivalued-headed argument like
        // `(Mortal Plato)` (Mortal has `=` rules -> nondeterministic) would otherwise be lifted
        // into a `map?` here and EVALUATED before `get-type` sees it -> get-type infers the type
        // of the reduced result (`%Undefined%`/`()`) instead of `(Mortal Plato)` -> `Type`
        // (d4 line 18). Keep such an argument raw so codegen inert-quotes it (the scalar case,
        // e.g. d3's `(drop (Cons 1 Nil))`, already stays raw because it is not nondeterministic).
        val inertParams = (expression.atoms.getOrNull(0) as? Symbol)?.name
            ?.let { context.resolve(it)?.jvmMethod?.inertAtomParams } ?: emptySet()
        val compoundLifts = mutableListOf<Triple<Int, Atom, Atom?>>()
        fun rewriteOrLift(atom: Atom): Atom {
            multivaluedCalls[atom.id]?.let { return mkVariable(it, expression.position) }
            // Lift a non-deterministic ARGUMENT out of this call and apply the call per
            // element of the argument's result bag. Two shapes qualify:
            //  • a multivalued-head COMPOUND, e.g. `(And (prevents …) (makes …))` — the head
            //    is itself multivalued; the parent must run over each result (b4).
            //  • a SCALAR-headed argument that is multivalued by COMPOSITION, e.g.
            //    `(s-tv (TV $a))` where `TV` is multivalued — rewriteAtom turns it into a
            //    `map?`/bag, so a scalar consumer like `(min …)`/`(* …)` must map over it
            //    instead of receiving the whole List as one argument (c3's ArrayList→Grounded
            //    ClassCastException). Both are already flagged in `multivaluedAtoms`
            //    (isNonDeterministic); a registered leaf call is handled above via
            //    `multivaluedCalls`, so it never reaches here — no double lift.
            if (!isBarrierParent
                && atom is Expression
                && atom.isNonDeterministic()
                && atom.atoms.isNotEmpty()
                && atom.atoms[0] is Symbol
            ) {
                val v = variableCount++
                compoundLifts.add(Triple(v, rewriteAtom(atom), atom.type))
                return mkVariable(v, expression.position)
            }
            return rewriteAtom(atom)
        }

        // A self-registered call (identity-lift at its own scope) is normally collapsed to
        // its bound variable so an enclosing map/flat-map binds it (the tail-call case, e.g.
        // lookup's `(lookup $key $rest)` → `map?(\$v. $v) (lookup …)`). BUT if this call ALSO
        // has NESTED multivalued calls scoped under it — the inner `(ev $e $env)` buried in a
        // data-constructor argument of `(ev $body (Cons (Bind $x (ev $e $env)) $env))` — those
        // must be lifted OUT and their result vars substituted INTO this call's arguments, with
        // the (substituted) call as the innermost body. Collapsing to a variable would leave
        // the inner call inline as an unevaluated thunk (later cast Expression→Grounded → CCE).
        // So force the substituted-body branch and drop the self-entry from the lift list.
        val selfVar = multivaluedCalls[expression.id]
        val scopedLifts = multivaluedCallsInverse[expression.id]
        val hasNestedLifts = scopedLifts != null && scopedLifts.any { it.second.id != expression.id }
        val body = if (selfVar != null && !hasNestedLifts) {
            mkVariable(selfVar, expression.position)
        } else Expression(
            atoms = expression.atoms.mapIndexed { i, a ->
                // Leave an inert-ATOM argument raw (no lift, no map?-rewrite) — see [inertParams].
                if (i >= 1 && (i - 1) in inertParams) a else rewriteOrLift(a)
            },
            position = expression.position
        )
        // Whether the (substituted) body itself yields a bag — a call to a multivalued user
        // function, or an applied lambda (a `let` body) whose own body is non-deterministic.
        // When it does, even the innermost lift must FLAT_MAP_ (flatten List<List>); when the
        // body is a single value (a scalar op like `min`/`*`/`s-tv`), the innermost lift is
        // MAP_ (collect one value per element). Consumed both by the compound-lift loop below
        // and by the `replacement`/createMaps path further down.
        val bodyCallsMultivalued = body is Expression && body.atoms.isNotEmpty() && when (val h = body.atoms[0]) {
            // The body is a call to a multivalued user function …
            is Symbol -> context.definedFunctions[h.name]?.func?.isMultivalued() == true
            // … or an APPLIED lambda (a `let` body) whose own body yields a bag — the
            // application returns a List, so the wrap must flatten, not nest (List<List>).
            is Lambda -> h.body.isNonDeterministic()
            else -> false
        }
        // Apply compound-argument lifts INNERMOST: the compound (e.g. `(And $v1 $v2)`)
        // and the enclosing call over it must sit where the compound's leaf-call
        // variables are bound, so a variable bound inside the compound (`$z` from the
        // `makes` flat-map) is in scope for the enclosing call (`(stop $z)` in `ift`).
        // `flat-map?(\$v3. (ift $v3 (stop $z)), (And $v1 $v2))`. The simple leaf calls
        // (prevents, makes) — bubbled to THIS scope in collection — then wrap OUTSIDE
        // via createMaps, binding $v1/$v2 the compound source references.
        var inner: Atom = body
        compoundLifts.forEachIndexed { idx, (v, source, type) ->
            // The innermost wrap (idx == 0) MAP_s when the body is a single value and
            // FLAT_MAP_s when it is a bag; every outer wrap flat-maps (`inner` is now a
            // List). A scalar consumer over multiple multivalued args thus becomes a
            // cartesian product: `flat-map?(\$b. map?(\$a. (min $a $b), A), B)`.
            val op = if (idx == 0 && !bodyCallsMultivalued) PredefinedAtoms.MAP_ else PredefinedAtoms.FLAT_MAP_
            inner = Expression(
                op,
                Lambda(
                    listOf(mkVariable(v, expression.position)),
                    liftLambdaType(op, type),
                    inner,
                    position = expression.position
                ),
                source,
                position = expression.position
            )
        }
        // check the expression is a scope
        var result: Atom = inner
        // When the substituted-body branch fired for a self-registered call with nested lifts,
        // its own identity-lift is subsumed by the substituted body — drop the self-entry so
        // only the genuinely nested calls are lifted around it.
        val replacement = if (selfVar != null && hasNestedLifts)
            scopedLifts!!.filter { it.second.id != expression.id }
        else scopedLifts
        if (!replacement.isNullOrEmpty()) {
            // Innermost op: if `inner` yields a List (a compound lift, or the body
            // calls a multivalued function), the innermost wrap must FLAT_MAP_ so its
            // results are flattened rather than nested.
            val innermostOp = if (compoundLifts.isNotEmpty() || bodyCallsMultivalued)
                PredefinedAtoms.FLAT_MAP_ else PredefinedAtoms.MAP_
            // Reversed so the FIRST-registered (left-most) multivalued call is the
            // OUTERMOST loop — left-to-right evaluation, matching both the reference
            // interpreter (b4 `(pair (bin) (bin))` → `((A A)(A B)(B A)(B B))`) and
            // PeTTa (Prolog left-to-right goal order). `createMaps` wraps
            // `replacement[0]` innermost. Result ORDER is invisible to the compat
            // suite (assertEqual/assertEqualToResult compare multisets), but the
            // SET matters for cross-binding deductions: a wrongly-outer conjunct
            // over-binds a shared variable and leaks non-reducing branches (b4 #11/#12).
            result = createMaps(replacement.reversed(), inner, innermostOp)
        }
        return result
    }

    // Lambda arrow type for a map?/flat-map? that lifts a multivalued argument: the lambda
    // receives ONE element of the argument's result bag (its element type). A FLAT_MAP_
    // lambda returns a bag (SeqType); a MAP_ lambda returns a single element. (Provisional —
    // the post-rewrite resolveSource re-types these lambdas from their bodies.)
    private fun liftLambdaType(op: Atom, type: Atom?): ArrowType {
        val t = type ?: GroundedType.ATOM
        val elementType = if (t is SeqType) t.elementType else t
        return ArrowType(elementType, if (op == PredefinedAtoms.FLAT_MAP_) SeqType(elementType) else elementType)
    }

    /*
    `(if (c $x1 $x2) (b1 $x1 $x3) (b2 $x2 $x3))`
    will be transformed in two folds:
    1. Extract function
       (= (__f1 $x1 $x2 $x3)
          (flat-map? (\ ($c1) (if $c1 (b1 $x1 $x3) (b2 $x2 $x3))
       )
    2. Replace if to non-deterministic function call
     */
    private fun rewriteIf(expression: Expression): Atom {
        fun rewriteAndMkSeqIfNeeded(expression: Expression, ind: Int): Atom {
            val other = if (ind == 2) 3 else 2
            // Seq-wrap this arm only if it is genuinely SCALAR. An arm whose type is already a
            // SeqType is a List (e.g. a system multivalued call like `(empty)` / `(superpose …)`
            // sitting at a result position, which returns its bag directly and is never
            // registered in multivaluedAtoms) — wrapping it in `(seq …)` would nest it to
            // List<List> (`[emptyList]` instead of `emptyList`) and break the map/flat-map.
            if (!expression.atoms[ind].isNonDeterministic() &&
                expression.atoms[ind].type !is SeqType &&
                expression.atoms[other].isNonDeterministic()
            ) {
                val inner = rewriteAtom(expression.atoms[ind])
                // Element type is provisional here (inner is often a destructured Atom); the
                // authoritative element type is pinned when the enclosing `if` is re-resolved
                // (resolveExpression's IF case homogenizes the two arms' element types), so
                // generateSeq coerces this scalar to match the other arm's List elements.
                // The element type may be genuinely unknown when the scalar arm is an untyped
                // constructor symbol (e.g. `nil` opposite a `(:: (bin) …)` arm) that never
                // homogenized against the non-det arm — fall back to the generic ATOM element
                // type so the seq-wrap still fires; re-resolution pins the real type later.
                return Expression(
                    Special(Predefined.SEQ), inner,
                    type = SeqType(inner.type ?: GroundedType.ATOM)
                )
            } else {
                return rewriteAtom(expression.atoms[ind])
            }
        }

        val replacement = multivaluedCallsInverse[expression.id]
        if (replacement != null) {
            val funcName = (replacement[0].second.atoms[0] as Symbol).name
            val op = if (
                expression.atoms[2].isNonDeterministic() ||
                expression.atoms[3].isNonDeterministic()
            ) PredefinedAtoms.FLAT_MAP_ else PredefinedAtoms.MAP_
            val newExpression = Expression(
                atoms = listOf(
                    expression.atoms[0],
                    rewriteAtom(expression.atoms[1]),
                    rewriteAndMkSeqIfNeeded(expression, 2),
                    rewriteAndMkSeqIfNeeded(expression, 3)
                ),
                type = expression.type,
                id = expression.id
            )
            return Expression(
                op,
                Lambda(
                    listOf(mkVariable(replacement[0].first, expression.position)),
                    getArrayTypeForFunc(op, funcName),
                    newExpression,
                    position = expression.position
                ),
                replacement[0].second
            )
        }

        // The `if` is not itself a lifted multivalued CALL (replacement == null), yet it
        // can still be HETEROGENEOUS: one arm scalar, the other a multivalued (List) call
        // — e.g. `(if (== …) $v (lookup … $rest))` as the body of a multivalued function,
        // where the else arm is a tail recursive call registered at its OWN scope (so it
        // never lands in multivaluedCallsInverse[if.id]). The compiled result must uphold
        // the invariant "a multivalued `-> T` function is physically `List<T>`": both arms
        // must yield a List. Seq-wrap the scalar arm so the `if`'s type becomes SeqType and
        // generateMatchBranch takes the addAll (flatten) path — instead of coercing a List
        // as if it were a single Grounded value (ClassCastException at runtime).
        if (expression.isNonDeterministic()) {
            val thenArm = rewriteAndMkSeqIfNeeded(expression, 2)
            val elseArm = rewriteAndMkSeqIfNeeded(expression, 3)
            val seqType = (thenArm.type as? SeqType)
                ?: (elseArm.type as? SeqType)
                ?: SeqType(expression.type ?: GroundedType.ATOM)
            return Expression(
                atoms = listOf(
                    expression.atoms[0],
                    rewriteAtom(expression.atoms[1]),
                    thenArm,
                    elseArm
                ),
                type = seqType,
                id = expression.id
            )
        }
        return expression
    }

    private fun collectNonDeterministicAtomsRecursively(
        atom: Atom,
        functionDefinition: FunctionLike,
        reducedScopeId: Int? = null,
        // True when [atom] is a direct argument of a non-determinism barrier
        // (`assertEqual`/`assertEqualToResult`/`collapse`). Such a barrier consumes the
        // WHOLE bag of results of each argument, so a bare multivalued call here must be
        // handed over as a List rather than lifted into a map at the parent scope.
        barrierArg: Boolean = false
    ): Boolean {
        // returns closest scope according call parameters
        fun getScopeId(call: Expression): Int =
            call.atoms.drop(1)
                .mapNotNull { it as? Variable }
                .mapNotNull { it.scope?.id }
                .minOrNull()
                ?: functionDefinition.body.id

        var isMultivalued = false
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) return false

                // `quote` is a hard boundary: its contents are inert DATA, never
                // evaluated, so a multivalued call inside a quote — e.g. the `superpose`
                // in `(eval '(superpose …))` — must NOT be lifted out to the enclosing
                // scope. Same rule as `match` below, which owns its own result scope.
                if (atom.atoms[0] == PredefinedAtoms.QUOTE) return false

                when (val f = atom.atoms[0]) {
                    is Symbol -> {
                        // match expressions have their own variable scope —
                        // the result template is evaluated per match result,
                        // so multivalued calls inside should not be lifted out
                        if (f.name == "match") {
                            return false
                        }
                        val def = context.definedFunctions[f.name]
                        val userMultivalued = def != null && def.func.isMultivalued()
                        // A system multivalued function (e.g. `superpose`, `generate`):
                        // present in systemFunctions with isMultiValued, absent from
                        // definedFunctions. `match` is already excluded above.
                        val systemMultivalued = def == null &&
                                context.resolve(f.name)?.isMultiValued == true
                        if (userMultivalued || systemMultivalued) {
                            // Check if arguments contain multivalued sub-calls.
                            // If so, don't register this call at the parent scope —
                            // instead, let the children be scoped to this call.
                            val hasMultivaluedArgs = atom.atoms.drop(1).any { arg ->
                                arg is Expression && arg.atoms.isNotEmpty() &&
                                        arg.atoms[0] is Symbol &&
                                        isMultivaluedHead((arg.atoms[0] as Symbol).name)
                            }
                            // A bare multivalued call that is a direct barrier argument is
                            // handed over whole (as its List result) — skip lifting it so
                            // the barrier receives the full bag instead of being mapped
                            // once per element.
                            if (!hasMultivaluedArgs && !barrierArg) {
                                val scopeId = reducedScopeId ?: getScopeId(atom)
                                // User @multivalued functions are lifted everywhere — even at
                                // the tail (scopeId == own id), because match-branch
                                // destructuring depends on that registration. System
                                // multivalued calls are lifted ONLY in argument position; at
                                // the tail/result position they return their bag directly,
                                // matching the prior behaviour where system calls were never
                                // lifted (a tail `!(superpose …)` / `!(generate …)` must not
                                // gain a spurious identity `(map? (\ ($v) $v) call)`, which
                                // would also need a mapImpl pure-symbolic programs lack).
                                if (userMultivalued || scopeId != atom.id) {
                                    multivaluedCalls[atom.id] = variableCount
                                    multivaluedCallsInverse.getOrPut(scopeId) { mutableListOf() }
                                        .add(variableCount to atom)
                                    variableCount++
                                }
                                // The call IS non-deterministic regardless of whether it also
                                // gets an identity-map lift above. Propagate that upward (the
                                // `multivaluedAtoms.add` below keys off this flag) so an enclosing
                                // `if`/consumer treats both it and itself as a List — e.g.
                                // `(if … (quote …) (empty))` must homogenize (seq-wrap the scalar
                                // `(quote …)` arm) instead of returning a bare List from one arm
                                // and a scalar from the other. A system call at its own result
                                // scope still takes NO lift (the identity-map guard is unchanged),
                                // so a tail `!(superpose …)` gains no spurious `(map? (\$v $v) …)`.
                                isMultivalued = true
                            }
                        }
                    }

                    is Lambda -> {
                        // An APPLIED lambda — e.g. the `(\ $e …)` a `let` desugars to. Its body
                        // is a nested scope (Lambda is FunctionLike): analyze it so multivalued
                        // calls and `if`-homogenization INSIDE the lambda are handled, and so we
                        // learn whether the APPLICATION is itself multivalued (its result is the
                        // lambda body's result). That flag makes the lift of a multivalued
                        // argument (`(gen $d)` in `(let $e (gen $d) …)`) flatten rather than nest.
                        if (collectNonDeterministicAtomsRecursively(f.body, f, f.body.id)) {
                            isMultivalued = true
                        }
                    }

                    else -> {}
                }

                if (atom.atoms[0].isIf()) {
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[1],
                        functionDefinition,
                        atom.id
                    )
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[2],
                        functionDefinition,
                        atom.atoms[2].id
                    )
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[3],
                        functionDefinition,
                        atom.atoms[3].id
                    )
                } else {
                    // When the current expression is a function call,
                    // scope any multivalued children to THIS expression
                    // so the flatMap chain wraps this call.
                    val headName = (atom.atoms[0] as? Symbol)?.name
                    // A multivalued COMPOUND call — multivalued head with a multivalued
                    // argument, e.g. `(And (prevents …) (makes …))` — is NOT registered
                    // as a leaf (see hasMultivaluedArgs above); it gets compound-lifted in
                    // rewriteExpression with the enclosing call applied per result. For the
                    // lift to thread bindings, a variable bound inside the compound (e.g.
                    // `$z` from `makes`) must be in scope for the enclosing call (`(stop $z)`
                    // in `ift`). That requires the compound's leaf calls to lift at the
                    // PARENT scope (around the enclosing call), not nested under the
                    // compound — so bubble its children to reducedScopeId.
                    // …unless this compound is itself a direct barrier argument: a
                    // barrier (assertEqual/collapse) consumes the WHOLE bag, so the
                    // compound stays self-contained (children scoped to it) and is NOT
                    // compound-lifted — bubbling here would desync with rewriteExpression,
                    // which leaves barrier args inlined.
                    val isMvCompound = !barrierArg && headName != null && isMultivaluedHead(headName) &&
                            atom.atoms.drop(1).any { arg ->
                                arg is Expression && arg.atoms.isNotEmpty() &&
                                        arg.atoms[0] is Symbol &&
                                        isMultivaluedHead((arg.atoms[0] as Symbol).name)
                            }
                    val childScope = if (isMvCompound) {
                        reducedScopeId
                    } else if (headName != null && context.definedFunctions[headName] != null) {
                        atom.id
                    } else reducedScopeId

                    // Direct arguments of a barrier (assertEqual/collapse/…) are tagged
                    // so a bare multivalued call among them is handed over as a whole bag.
                    val childIsBarrierArg = (atom.atoms[0] as? Symbol)?.name in BARRIER_FUNCTIONS

                    // A parameter declared inert-ATOM (`get-type`) receives the UN-reduced term:
                    // rewriteExpression leaves such an argument raw, so registering a lift for a
                    // multivalued call inside it produces a wrap with nothing to bind — the wrap
                    // lands on whatever scope the registration bubbled to and re-evaluates the
                    // term (`(get-type (Mortal Plato))` → a map? over `(Mortal Plato)`). Skip it.
                    val headInertParams = headName
                        ?.let { context.resolve(it)?.jvmMethod?.inertAtomParams } ?: emptySet()

                    // other specials and symbols
                    atom.atoms.drop(1).forEachIndexed { i, it ->
                        if (i in headInertParams) return@forEachIndexed
                        if (collectNonDeterministicAtomsRecursively(
                                it,
                                functionDefinition,
                                childScope,
                                childIsBarrierArg
                            )
                        ) isMultivalued = true
                    }
                }
            }

            is Lambda -> {
                // A lambda in a non-head position (an explicit `\` passed as an argument):
                // analyze its body as a nested scope so multivalued calls / `if`s inside are
                // handled. The lambda VALUE itself is not a bag, so this does not mark the
                // enclosing atom multivalued.
                collectNonDeterministicAtomsRecursively(atom.body, atom, atom.body.id)
                return false
            }

            else -> return false
        }
        if (isMultivalued) multivaluedAtoms.add(atom.id)
        return isMultivalued
    }

    private fun Expression.checkIsNonDeterministicRecursively(): Boolean {
        if (atoms.isEmpty()) return false
        // Safe lookup: a Symbol head need not be a user-defined function — it can be a data
        // constructor or a system builtin, absent from definedFunctions. Treat those as not
        // multivalued rather than asserting (`!!`) and NPE-ing (crashed if2/smartdispatch/…).
        val head = atoms[0]
        if (head is Symbol && context.definedFunctions[head.name]?.func?.isMultivalued() == true) return true
        atoms.drop(1).forEach {
            if (it is Expression && it.checkIsNonDeterministicRecursively()) return true
        }
        return false
    }

    // True for a multivalued call — a user-defined function annotated @multivalued,
    // OR a system function (e.g. `superpose`) whose resolved symbol reports
    // isMultiValued. `match` is excluded: it owns its own result scope and is
    // special-cased throughout this pass, so a `(match …)` in argument position is
    // never lifted.
    private fun isMultivaluedHead(name: String): Boolean {
        if (name == "match") return false
        return context.definedFunctions[name]?.func?.isMultivalued()
            ?: (context.resolve(name)?.isMultiValued == true)
    }

    private fun getArrayTypeForFunc(op: Atom, name: String): ArrowType? =
        (context.definedFunctions[name]?.func?.arrowType?.types
            ?: context.resolve(name)?.arrowType?.types)?.let { types ->
            val returnType = types.last()
            // If the function is multivalued (returns SeqType), the map/flatMap
            // lambda receives individual elements, not the whole list.
            val elementType = if (returnType is SeqType) returnType.elementType else returnType
            ArrowType(
                elementType,
                if (op == PredefinedAtoms.FLAT_MAP_) SeqType(elementType) else elementType
            )
        }

    private fun mkVariable(i: Int, position: SourcePosition?): Variable =
        Variable("__var$i", position = position)

    private fun Atom.isNonDeterministic(): Boolean = multivaluedAtoms.contains(id)
}