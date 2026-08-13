package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.*
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

open class FunctionGenerator(
    private val mv: LocalVariablesSorter,
    private val function: FunctionLike,
    private val isStatic: Boolean,
    private val className: String?,
    /**
     * Name of the module being compiled — the same string the entry's `__main` passes
     * to `JettaProgram.init`. Baked into bytecode at every `&self` reference so each
     * `match` call carries the name of its owner space, and the runtime registry can
     * resolve it without a thread-local "current" space.
     */
    private val moduleSpaceName: String,
    /**
     * Internal name of the class that owns this method and any sibling lambda body
     * methods. Used at indy lambda creation sites to reference the static lambda body.
     */
    private val enclosingClassInternalName: String,
    /**
     * Names of top-level functions that carry an explicit `(: f (-> …))` type declaration in
     * this module's space (phase D2.3). Such a function gets an eval-time type-check prologue
     * ([maybeEmitTypeCheckPrologue]); undeclared functions are left untouched so hot untyped code
     * (backchaining, symbolic interpreters) pays no per-call type-check cost.
     */
    private val declaredTypeNames: Set<String> = emptySet(),
) {
    private val destructuredLocals = mutableMapOf<String, Int>()

    // Active only while emitting a scalar-Match branch body (and its nested if-arms). It
    // tells the `doReturn` path to coerce the value to the function return type before
    // returning (unwrap a Grounded to a primitive, or box a primitive into a Grounded).
    // Off elsewhere: other return sites (direct function bodies, typed lambda calls) already
    // leave a correctly-typed value, and coercing there would double-unwrap.
    private var scalarReturnCoercion = false

    // Whether this function's body actually touches the thread-local binding stack
    // (`Matcher`). If it doesn't, the per-call `Matcher.push()`/`pop()` frame is a pure
    // no-op — `push` adds an empty map and `pop` does `parent.putAll(emptyMap)` — so it
    // can be elided. This removes the per-call binding-stack overhead from purely
    // computational functions (e.g. compiled `fib`: primitive params, `if` + arithmetic +
    // self-recursion, no match/destructuring). See [usesMatcher] for the criterion.
    private val usesMatcher: Boolean = computeUsesMatcher()

    fun generate() {
        emitLineNumber(function)

        if (!usesMatcher) {
            // No binding-stack interaction: emit the body directly, no push/pop, no
            // exception-safe finally (there is no frame to unwind). generateReturn also
            // skips its pop when usesMatcher is false.
            generateAtom(mv, function.body, null, true)
            mv.visitMaxs(maxStack, maxLocals)
            return
        }

        val matcherType = RuntimeNames.MATCHER

        // Matcher.push()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, matcherType, "push", "()V", false)

        val tryStart = Label()
        val tryEnd = Label()
        val finallyHandler = Label()

        mv.visitTryCatchBlock(tryStart, tryEnd, finallyHandler, null)

        mv.visitLabel(tryStart)
        generateAtom(mv, function.body, null, true)
        mv.visitLabel(tryEnd)

        // finally handler: catch any throwable, call pop(), rethrow
        mv.visitLabel(finallyHandler)
        val exVar = mv.newLocal(Type.getObjectType("java/lang/Throwable"))
        mv.visitVarInsn(Opcodes.ASTORE, exVar)
        generatePop(mv)
        mv.visitVarInsn(Opcodes.ALOAD, exVar)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitMaxs(maxStack, maxLocals)
    }

    /**
     * A function needs its `Matcher` frame iff its body reads or writes the binding stack.
     * Conservative — any doubt returns true (keep the frame). It touches the Matcher when:
     *  - it is multivalued (composes via `map?`/`flat-map?` → per-branch push/pop + BoundAtom);
     *  - it has an `Atom`-typed parameter (resolved via `Matcher.resolveBinding` in dispatch);
     *  - its body contains a `Match` (destructuring dispatch → resolveBinding / non-reduction
     *    fallback), a `match` space query, a multivalued call, or a lambda.
     * Lambdas (non-FunctionDefinition) always keep their frame — they run inside `simpleMap`/
     * `simpleFlatMap`, whose per-branch bindings we must not disturb.
     */
    private fun computeUsesMatcher(): Boolean {
        val fn = function as? FunctionDefinition ?: return true
        if (fn.isMultivalued()) return true
        if (fn.params.any { it.type == GroundedType.ATOM }) return true
        return bodyTouchesMatcher(fn.body)
    }

    private fun bodyTouchesMatcher(atom: Atom): Boolean = when (atom) {
        is Match -> true
        is Lambda -> true
        is Expression -> {
            if (atom.atoms.isEmpty()) false
            else if ((atom.atoms[0] as? Symbol)?.name == "match") true
            else if (atom.resolved?.isMultiValued == true) true
            else atom.atoms.any { bodyTouchesMatcher(it) }
        }
        else -> false
    }

    protected var maxStack = 0
    protected var maxLocals = function.params.size

    private fun generateLoadInt(value: Int) = generateLoadInt(mv, value)

    private fun generateLoadBoolean(value: Boolean) {
        when (value) {
            false -> mv.visitInsn(Opcodes.ICONST_0)
            true -> mv.visitInsn(Opcodes.ICONST_1)
        }
    }

    private fun generateLoad(mv: LocalVariablesSorter, atom: Atom) {
        when (atom) {
            is Grounded<*> -> {
                when (atom.value) {
                    is Int -> generateLoadInt(atom.value as Int)
                    is Long -> mv.visitLdcInsn(atom.value)
                    is Boolean -> generateLoadBoolean(atom.value as Boolean)
                    is Double -> mv.visitLdcInsn(atom.value)
                    is String -> mv.visitLdcInsn(atom.value)
                    else -> TODO("Not implemented yet " + atom.value)
                }
            }

            is Symbol -> {
                when {
                    // A MeTTa boolean literal is a grounded Bool, not a plain symbol. hyperon
                    // registers `True`/`False` as grounded-Bool tokens, and every comparison /
                    // `and` / `or` yields that same grounded Bool — so `(assertEqual (> 2 1)
                    // True)` holds and `(get-type True)` is Bool. Emit `Grounded<Boolean>` here
                    // (a value position) so a `True`/`False` VALUE has the same representation as
                    // a boolean-op result and structurally equals it. Pattern, condition and
                    // Bool-return positions never reach generateLoad: they intercept the symbol
                    // earlier (pushComparisonOperand, generateBooleanExpr, the Bool-return case)
                    // and use the primitive. (A user's own truth token like b3's `T` is NOT a
                    // boolean — `(get-type T)` is %Undefined% in hyperon — so it stays a Symbol.)
                    atom.name == "True" || atom.name == "False" -> {
                        generateLoadBoolean(atom.name == "True")
                        wrapValueOnStackInGrounded(GroundedType.BOOLEAN)
                    }
                    // &self materialises as the owner module's space name. The runtime's
                    // match/matchEval take this string as their leading argument and
                    // look up the corresponding Space via SpaceRegistry.
                    atom.name == Predefined.SELF -> mv.visitLdcInsn(moduleSpaceName)
                    // Any other &-prefixed symbol is a reference to a sub-space registered
                    // (or to-be-registered) under that literal name. The runtime registry
                    // returns an empty space for unknown names, so unresolved sub-space
                    // references produce empty match results rather than crashing.
                    atom.name.startsWith("&") -> mv.visitLdcInsn(atom.name)
                    else -> {
                        // Create a Symbol object at runtime for comparison
                        mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Symbol::class.java))
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitLdcInsn(atom.name)
                        mv.visitInsn(Opcodes.ACONST_NULL)
                        generateLoadInt(mv, 2)
                        mv.visitInsn(Opcodes.ACONST_NULL)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            Type.getInternalName(Symbol::class.java),
                            "<init>",
                            "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                            false
                        )
                    }
                }
            }

            // A bare `Special` operator reaching a value/data position — e.g. `+` as the ATOM
            // argument of `(get-type +)`, quoted as data rather than applied. Materialize the
            // runtime `Special` object, mirroring the `Special` case in `generateQuote`.
            is Special -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Special::class.java))
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(atom.value)
                mv.visitInsn(Opcodes.ACONST_NULL)
                generateLoadInt(mv, 2)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Special::class.java),
                    "<init>",
                    "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
            }

            is ArrowType -> {
                // Same surface-form reification as in `generateQuote` — an arrow
                // type appearing as a direct value (e.g. RHS of `assertEqual`)
                // becomes the `(-> …)` Expression at runtime. Use `generateQuote`
                // (not the local recursive `generateLoad`) because Expressions
                // must be constructed via the quote machinery.
                generateQuote(mv, Expression(listOf(Special(Predefined.ARROW)) + atom.types))
            }

            else -> TODO("Not implemented yet $atom")
        }
    }

    protected fun emitLineNumber(atom: Atom) {
        atom.position?.let {
            val label = Label()
            mv.visitLabel(label)
            mv.visitLineNumber(it.start.line, label)
        }
    }

    protected fun generateAtom(
        mv: LocalVariablesSorter,
        atom: Atom,
        exit: Label?,
        doReturn: Boolean,
        needBoxing: Boolean = false
    ) {
        emitLineNumber(atom)
        // A bare `True`/`False` symbol RETURNED from a `Bool`-typed function must materialize as
        // the primitive boolean constant the descriptor's trailing `Z` expects — not the Symbol
        // object the generic atom path (generateLoad) would push, which the verifier rejects at
        // `ireturn` (a Symbol is not assignable to int). A MeTTa boolean is the bare symbol
        // True/False, and only a Bool RETURN slot demands the primitive — elsewhere True/False
        // stay Symbols (e.g. flowing into an Atom-typed sink like `(add-atom &kb (Green $x))`).
        // Multivalued Bool functions return a `List`, so exclude them (they take the ARETURN path).
        if (doReturn && atom is Symbol && (atom.name == "True" || atom.name == "False") &&
            function.returnType == GroundedType.BOOLEAN &&
            !((function as? FunctionDefinition)?.isMultivalued() ?: false)
        ) {
            generateLoadBoolean(atom.name == "True")
            generateReturn(mv)
            return
        }
        when (atom) {
            is Expression -> {
                // The empty tuple `()` is a value, not a call — it has no head to dispatch
                // on. Emit it as a runtime empty Expression (e.g. the else arm synthesized for
                // a 2-arg `(if cond then)`, or a bare `()` reached outside a quoted body).
                if (atom.atoms.isEmpty()) {
                    generateQuote(mv, atom)
                    if (doReturn) mv.visitInsn(Opcodes.ARETURN)
                    return
                }
                // A grounded operator short of its operands is a partial application, which is
                // DATA — the resolver already typed it Atom, and every operator branch below
                // destructures a fixed shape. Quote it, so it reaches the runtime intact and can
                // be completed by variable-head dispatch.
                if (atom.isMisappliedSpecial()) {
                    generateQuote(mv, atom)
                    if (doReturn) mv.visitInsn(Opcodes.ARETURN)
                    return
                }
                val func = atom.atoms[0]
                val arguments = atom.atoms.drop(1)

                when (func) {
                    is Special -> when (func.value) {
                        Predefined.PLUS, Predefined.TIMES, Predefined.MINUS ->
                            // operator-as-data: when the resolver stamped this arithmetic
                            // form inert ATOM (an operator tuple inside a data container like
                            // `(superpose (+ - *))`), it is DATA, not a computation — quote it
                            // like the other Special-headed data forms below. Genuine
                            // arithmetic always carries an INT/DOUBLE type, so this never
                            // suppresses a real `(+ …)`/`(* …)`/`(- …)` computation.
                            if (atom.type == GroundedType.ATOM) {
                                // D2.2 (tier i): a concrete non-numeric operand (String) makes this
                                // an eval-time type error — emit the inert `(Error <expr>
                                // (BadArgType pos Number <actual>))` atom for THIS instance instead
                                // of the inert operator tuple. Recomputed here (not carried from the
                                // resolver) so it stays identity-precise: an identical `(+ …)` inside
                                // quoted expected data is emitted verbatim by generateQuote, never
                                // reaching this arithmetic-dispatch branch.
                                val err = groundedArithmeticError(atom)
                                when {
                                    err != null -> generateQuote(mv, err)
                                    // A symbol operand may have a DECLARED type, and `:` facts live
                                    // in the space, so whether `(+ ln 2)` is merely unreduced or an
                                    // ill-typed application is only known at runtime — and depends
                                    // on the run's position relative to the declaration. Ask, and
                                    // fall back to the inert form. c1 has the same `(+ ln 2)` both
                                    // before `(: ln LN)` (unreduced) and after it (BadArgType).
                                    atom.atoms.drop(1).any { it is Symbol } ->
                                        generateInertArithmeticWithTypeCheck(mv, atom)

                                    else -> generateQuote(mv, atom)
                                }
                            } else generateArithmetics(mv, func, arguments, atom.type as GroundedType, doReturn)

                        Predefined.DIVIDE -> generateDivide(mv, arguments, doReturn)
                        Predefined.DIV -> generateDiv(mv, arguments, doReturn)
                        Predefined.MOD -> generateMod(mv, arguments, doReturn)

                        Predefined.IF -> generateIf(mv, arguments, exit, doReturn)
                        Predefined.RUN_SEQ -> {
                            // In __main, each argument is an independent top-level `!` run.
                            // Clear variable bindings between them so one run's bindings
                            // don't leak into the next (e.g. b4 (is (air dry)) → (is (air
                            // wet))). RUN_SEQ is also used for `let` desugaring inside
                            // ordinary functions — there bindings MUST persist, so only
                            // __main's top-level RUN_SEQ resets. ("__main" = FunctionRewriter.MAIN.)
                            val isMain = (function as? FunctionDefinition)?.name == "__main"
                            arguments.forEach {
                                if (isMain) {
                                    mv.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        RuntimeNames.MATCHER,
                                        "clearAll", "()V", false
                                    )
                                }
                                generateAtom(mv, it, null, false)
                            }
                        }

                        Predefined.SEQ -> generateSeq(mv, arguments, atom.type!!)
                        Predefined.MAP_ -> generateCall(mv, Predefined.MAP_, arguments, atom.resolved)
                        Predefined.FLAT_MAP_ -> generateCall(mv, Predefined.FLAT_MAP_, arguments, atom.resolved)
                        Predefined.QUOTE -> generateQuote(mv, arguments[0])
                        Predefined.PATTERN -> {
                            // D3 increment C — `=`-as-reducible-head. A binary `(= a b)` in a
                            // VALUE position (e.g. the argument of `assertEqual`, NOT a top-level
                            // rule declaration — those are partitioned out by FunctionRewriter, and
                            // NOT a `get-type`/`match` operand — those are quoted by their own
                            // meta-Atom / `Match`-node paths) is dispatched through JettaCallSite so
                            // the reducer can rewrite it via reflective-style rules such as
                            // `(= (= $x $x) T)`, reducing its operands applicatively first. When no
                            // such rule exists the reducer returns `(= a b)` unchanged (its own
                            // normal form), so this is 0-regression for programs without one.
                            if (arguments.size == 2) {
                                generateExpressionHeadDispatchCall(mv, atom)
                            } else {
                                generateQuote(mv, atom)
                            }
                        }
                        Predefined.ANNOTATION,
                        Predefined.TYPE,
                        Predefined.ARROW -> {
                            // Data forms with a Special head reach codegen as inert
                            // values (see resolver counterpart). Emit as a quoted
                            // expression so the whole `(@ doc …)` /
                            // `(: … …)` / `(-> …)` lives as a runtime Expression atom.
                            generateQuote(mv, atom)
                        }
                        Predefined.COND_EQ, Predefined.COND_NEQ -> when {
                            // D2.4 (increment 1): the resolver stamps a `==`/`!=` node ATOM when its
                            // operands are a concrete numeric-vs-String mismatch (an eval-time type
                            // error). Emit the inert `(Error <expr> (BadArgType …))` VALUE for THIS
                            // instance — a reference, not a Bool — so it never reaches the
                            // integer-comparison path (which would VerifyError over a String
                            // operand). Recomputed here so it stays identity-precise (an identical
                            // `(== …)` inside quoted expected data is emitted verbatim by
                            // generateQuote, never reaching this branch). Falls through to the
                            // doReturn/exit epilogue.
                            atom.type == GroundedType.ATOM ->
                                generateQuote(mv, comparisonBadArgType(atom) ?: atom)

                            // An operand that is an unreducible arithmetic form may itself be an
                            // ill-typed application once a `:` declaration is in scope. Errors are
                            // absorbing, so surface it as the comparison's value instead of
                            // comparing a `(Error …)` atom against the other operand.
                            !doReturn && runtimeErrorableOperands(atom).isNotEmpty() -> {
                                generateComparisonWithArgErrorCheck(
                                    mv, atom, runtimeErrorableOperands(atom), exit
                                )
                                return
                            }

                            // D2.4 (increment 2): a structural comparison of two custom-typed
                            // operands in a typed program may be an eval-time BadArgType (types are
                            // `:` facts, so it is a RUNTIME check — `(== SocratesIsHuman
                            // SamIsMortal)` errors when their declared types don't unify). Only in a
                            // value/argument position (`!doReturn`), where the result is consumed as
                            // an Atom, so an `(Error …)` and a `Grounded<Bool>` are interchangeable
                            // — the bare-run/tail path keeps the primitive-Bool return unchanged.
                            !doReturn && needsRuntimeComparisonTypeCheck(atom.atoms.drop(1)) -> {
                                generateComparisonWithTypeCheck(mv, atom, exit)
                                return
                            }

                            else -> generateIf(
                                mv,
                                listOf(atom, Grounded(true), Grounded(false)),
                                exit,
                                doReturn
                            )
                        }

                        else -> if (func.isBooleanExpression()) {
                            // An order comparison the resolver stamped ATOM has an operand it
                            // cannot compute — `(> 4 (+ ln 2))`. It has no Bool value, so emit the
                            // whole form as data (hyperon leaves it unreduced). Comparing would
                            // also CCE: the inert operand is an Expression at runtime, not a
                            // Grounded number.
                            if (atom.type == GroundedType.ATOM) {
                                generateQuote(mv, atom)
                            } else {
                                generateIf(
                                    mv,
                                    listOf(atom, Grounded(true), Grounded(false)),
                                    exit,
                                    doReturn
                                )
                            }
                        } else TODO("func=$func")
                    }

                    is Symbol -> {
                        if (atom.resolved != null) {
                            // A state write is type-checked against the SURFACE application, before
                            // its arguments reduce — that is how hyperon's error term can echo
                            // `(change-state! (new-state 1) "S")` rather than the state the first
                            // argument evaluates to. The runtime check inside `change-state!`
                            // still covers what only the reduced values reveal.
                            if (isStateWrite(atom)) {
                                generateStateWriteWithTypeCheck(mv, atom, func.name, arguments)
                            } else {
                                generateCall(mv, func.name, arguments, atom.resolved)
                            }
                        } else {
                            // D2.4 (increment 4): an inert/undefined application whose argument is
                            // a statically-known grounded type error (`(f (+ 5 "S"))`, f declared
                            // but unruled) reduces to that inner error — hyperon's errors are
                            // absorbing: reducing the argument first surfaces the `(Error …)` as
                            // the whole application's value. Propagate the FIRST such argument
                            // error instead of quoting `(f (Error …))`.
                            val argErr = firstStaticArgError(atom)
                            if (argErr != null) {
                                generateQuote(mv, argErr)
                            } else {
                                // Unresolved symbol in function position — a data constructor like
                                // (Cons …), (Pair $x $y). Quote it, but in this applicative
                                // position evaluate any reducible call nested in its arguments
                                // (`(Cons (Bind $x (ev $e $env)) …)` stores the VALUE of
                                // `(ev …)`, not a thunk) — canonical MeTTa: a data constructor
                                // does not suppress reduction of its arguments.
                                generateQuote(mv, atom, evalCalls = true)
                            }
                        }
                    }

                    is Variable -> {
                        if (func.type is ArrowType) {
                            generateLambdaCall(mv, func, arguments)
                        } else {
                            // Variable head without a static ArrowType — could be a
                            // compiled lambda, could be a non-callable data atom, could
                            // be anything. Defer the decision to runtime via the
                            // JettaCallSite dispatcher.
                            generateDispatchCall(mv, func, arguments)
                        }
                    }

                    is Lambda -> generateInlineLambdaCall(mv, func, arguments)

                    else -> {
                        // D3 increment B — Expression-headed form `((h …) a …)`. Two shapes
                        // arrive here and they need opposite treatment:
                        //
                        //  * a CURRIED APPLICATION `(((curry +) 2) 3)` — the head is an
                        //    *unresolved* Expression (`curry`/`lambda` are defined as space
                        //    `(= …)` facts, never resolved to a compiled function). Dispatch it
                        //    through [JettaCallSite] so the unified reducer rewrites it by the
                        //    space rule (`(= (((curry $f) $x) $y) ($f $x $y))`) and finishes the
                        //    produced `(+ 2 3)` via the registry step (increment A).
                        //  * an EFFECT/DATA TUPLE `((add-atom …) (add-atom …))` — the head is a
                        //    *resolved* reducible call. Its element calls must be EVALUATED for
                        //    their side effects (the hide idiom, match templates), so keep the
                        //    old applicative-order quote path.
                        //
                        // The head's resolution status is the discriminator: `resolved == null`
                        // ⇒ curried application ⇒ dispatch; otherwise the effect/data path. A
                        // genuinely-inert unresolved tuple (`((stop ventilation) …)`) also takes
                        // the dispatch path but matches no rule/op and returns unchanged, so this
                        // stays 0-regression for inert data.
                        val headExpr = func as? Expression
                        if (headExpr != null && headExpr.resolved == null) {
                            generateExpressionHeadDispatchCall(mv, atom)
                        } else {
                            generateQuote(mv, atom, evalCalls = true)
                        }
                    }
                }
            }

            is Variable -> {
                val slot = destructuredLocals[atom.name]
                if (slot != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                    // A destructured pattern variable is an Atom (Grounded) at runtime.
                    // When it is used as a primitive value (its inferred type is a
                    // grounded value type), unwrap the Grounded and unbox so arithmetic
                    // and primitive boxing see the raw primitive, not the Atom.
                    val t = atom.type
                    if (t is GroundedType && t.isGroundedValue()) {
                        unwrapGroundedToPrimitive(mv, t)
                    }
                } else {
                    val paramIndex = function.getParameterIndex(atom)
                    if (paramIndex >= 0) {
                        generateLoadVar(mv, atom, function.params, isStatic, className)
                    } else {
                        // True free variable — create a raw Variable for match unification, but
                        // resolve it against the live bindings first. This is a VALUE position
                        // (a quoted pattern's variables go through generateQuote/generateLoadVar
                        // instead), and by the time it is evaluated an earlier sub-expression may
                        // already have bound the name: e3's `(if (== (get-state (status (Goal
                        // $goal))) active) $goal …)` binds `$goal` while reducing the condition,
                        // and the then-branch must yield that value, not a free `$goal`. An
                        // unbound variable resolves to itself, so unification behaviour is
                        // unchanged.
                        generateNewVariable(mv, atom.name)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            RuntimeNames.MATCHER,
                            "resolveBinding",
                            "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
                            false
                        )
                    }
                }
            }

            is Lambda -> {
                val capturedVariables = atom.capturedVariables()
                capturedVariables.forEach {
                    // Captured variables may come from destructured pattern bindings
                    // (e.g., $destr_0_1 from a match branch like `(= (f (And $a $b)) ...)`).
                    // These are stored as local variable slots in the enclosing match branch,
                    // not as formal function parameters. When a lambda created by the
                    // flat-map? rewrite references such a variable (because its source
                    // expression like `(f $destr_0_1)` ended up inside the lambda body),
                    // we must load it from the destructuredLocals map rather than from
                    // the function's parameter list.
                    val destrSlot = destructuredLocals[it.name]
                    if (destrSlot != null) {
                        mv.visitVarInsn(Opcodes.ALOAD, destrSlot)
                    } else {
                        generateLoadVar(mv, it, function.params, isStatic, className)
                    }
                }
                val invokedTypeDesc = buildString {
                    append('(')
                    capturedVariables.forEach { append(it.type!!.toJvmType()) }
                    append(")L").append(JETTA_FUNCTION_INTERNAL_NAME).append(';')
                }
                val implDesc = buildString {
                    append('(')
                    capturedVariables.forEach { append(it.type!!.toJvmType()) }
                    atom.params.forEach { append(it.type!!.toJvmType()) }
                    append(')')
                    append(atom.returnType!!.toJvmType())
                }
                mv.visitInvokeDynamicInsn(
                    "apply",
                    invokedTypeDesc,
                    LAMBDA_BOOTSTRAP_HANDLE,
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        enclosingClassInternalName,
                        atom.resolvedMethodName!!,
                        implDesc,
                        false,
                    ),
                )
            }

            is Match -> generateMatch(mv, atom)

            else -> {
                generateLoad(mv, atom)
            }
        }
        // A multivalued call already yields a `List` (a reference) on the stack at runtime,
        // regardless of its scalar element `type` (e.g. a barrier argument like
        // `(assertEqual (pick) 2)` where `pick` is `@multivalued -> Int` but its descriptor
        // returns List). Boxing it as that primitive would emit `Integer.valueOf(I)` over a
        // reference → VerifyError. Skip boxing for such calls — the List is already an Object.
        val isMultivaluedCall = (atom as? Expression)?.resolved?.isMultiValued == true
        if (needBoxing && !isMultivaluedCall) {
            // A Bool value boxed into an Object/Atom slot (a `println`/data argument, a lambda
            // arg) becomes a Grounded<Boolean> — the canonical MeTTa boolean that renders as
            // True/False and IS an Atom — not a raw java.lang.Boolean (lowercase, not an Atom).
            // Other primitives box to their wrapper as before. A `True`/`False` literal reaches
            // here already as a Grounded<Boolean> (type ATOM), so it is not double-wrapped.
            if (atom.type == GroundedType.BOOLEAN) wrapValueOnStackInGrounded(GroundedType.BOOLEAN)
            else generateBoxingIfNeeded(atom.type!!)
        }
        if (doReturn) {
            coerceForReturn(atom)
            generateReturn(mv)
        } else {
            if (exit != null) {
                mv.visitJumpInsn(Opcodes.GOTO, exit)
            }
        }
    }

    /**
     * A grounded ARITHMETIC form that computes a scalar — `(+ 2 2)` inside `(ln (+ 2 2))`. Such a
     * form carries no `resolved` symbol (its head is a [Special], not a user function), so the
     * applicative-order branch of [generateQuote] used to miss it and quote it as a thunk, leaving
     * `(ln (+ 2 2))` where hyperon reduces the argument and yields `(ln 4)` (c1). The grounded-value
     * type is the discriminator: an arithmetic form over an unreducible operand is stamped `ATOM` by
     * the resolver and therefore stays inert here, as it must — `(> 4 (+ ln 2))` keeps its shape.
     */
    private fun isGroundedArithmetic(atom: Expression): Boolean {
        val head = atom.atoms.firstOrNull() as? Special ?: return false
        val type = atom.type
        if (head.value !in ARITHMETIC_OPS || type !is GroundedType || !type.isGroundedValue()) {
            return false
        }
        // Only when every operand is a grounded LITERAL (or nested arithmetic over such literals),
        // so the value is computable right here. An operand that is a variable or some other call
        // is deliberately excluded: `(stv (* $s (s-tv (TV $y))) …)` (c3) is a match TEMPLATE, and
        // forcing its `(* …)` hands the multiplication an unbound variable at runtime.
        return atom.atoms.drop(1).all { operand ->
            when (operand) {
                is Expression -> isGroundedArithmetic(operand)
                is Grounded<*> -> (operand.type as? GroundedType)?.isGroundedValue() == true
                else -> false
            }
        }
    }

    private fun generateQuote(mv: LocalVariablesSorter, atom: Atom, evalCalls: Boolean = false) {
        when (atom) {
            is Expression -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Expression::class.java))
                mv.visitInsn(Opcodes.DUP)
                generateLoadInt(atom.atoms.size)
                val atomType = Type.getInternalName(Atom::class.java)
                val arr = mv.newLocal(Type.getObjectType("[$atomType"))
                mv.visitTypeInsn(Opcodes.ANEWARRAY, atomType)
                mv.visitVarInsn(Opcodes.ASTORE, arr)
                atom.atoms.forEachIndexed { index, sub ->
                    mv.visitVarInsn(Opcodes.ALOAD, arr)
                    generateLoadInt(index)
                    // Applicative order: in a value position (evalCalls), a reducible
                    // SCALAR call nested in the constructor is evaluated and its value
                    // stored — not quoted as a thunk. A multivalued call is left to the
                    // CanonicalForm lifting; genuinely inert data (and `quote`d content,
                    // evalCalls=false) is quoted as before.
                    val subType = sub.type
                    if (evalCalls && sub is Expression &&
                        ((sub.resolved != null && sub.resolved?.isMultiValued != true) ||
                            isGroundedArithmetic(sub))
                    ) {
                        generateAtom(mv, sub, null, false)
                        if (subType is GroundedType && subType.isGroundedValue()) {
                            wrapValueOnStackInGrounded(subType)
                        }
                    } else {
                        generateQuote(mv, sub, evalCalls)
                    }
                    mv.visitInsn(Opcodes.AASTORE)
                }
                mv.visitVarInsn(Opcodes.ALOAD, arr)
                mv.visitInsn(Opcodes.ACONST_NULL) // type
                mv.visitInsn(Opcodes.ACONST_NULL) // resolved symbol
                generateLoadInt(6)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Expression::class.java),
                    "<init>",
                    "([Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/ResolvedSymbol;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
            }
            is Symbol -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Symbol::class.java))
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(atom.name)
                mv.visitInsn(Opcodes.ACONST_NULL)
                generateLoadInt(2)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Symbol::class.java),
                    "<init>",
                    "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
            }

            is Special -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Special::class.java))
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(atom.value)
                mv.visitInsn(Opcodes.ACONST_NULL)
                generateLoadInt(2)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Special::class.java),
                    "<init>",
                    "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
            }

            is Grounded<*> -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Grounded::class.java))
                mv.visitInsn(Opcodes.DUP)
                when (val value = atom.value) {
                    is Int -> {
                        generateLoadInt(value)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Integer",
                            "valueOf",
                            "(I)Ljava/lang/Integer;",
                            false
                        )
                    }
                    is Long -> {
                        mv.visitLdcInsn(value)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Long",
                            "valueOf",
                            "(J)Ljava/lang/Long;",
                            false
                        )
                    }
                    is Boolean -> {
                        if (value) {
                            mv.visitInsn(Opcodes.ICONST_1)
                        } else {
                            mv.visitInsn(Opcodes.ICONST_0)
                        }
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Boolean",
                            "valueOf",
                            "(Z)Ljava/lang/Boolean;",
                            false
                        )
                    }
                    is Double -> {
                        mv.visitLdcInsn(value)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Double",
                            "valueOf",
                            "(D)Ljava/lang/Double;",
                            false
                        )
                    }
                    is String -> {
                        mv.visitLdcInsn(value)
                    }
                    else -> TODO("Not implemented yet grounded quote for $value")
                }
                mv.visitInsn(Opcodes.ACONST_NULL)
                generateLoadInt(2)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Grounded::class.java),
                    "<init>",
                    "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
            }

            is Variable -> {
                val slot = destructuredLocals[atom.name]
                val param = function.params.find { it.name == atom.name }
                val paramType = param?.type
                if (slot != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                } else if (param != null && paramType is GroundedType && paramType.isGroundedValue()) {
                    // Capturing a primitive-valued parameter INTO quoted data: the
                    // quoted Expression is made of Atoms, but the param is compiled as a
                    // raw primitive (e.g. `int`). Load it by its REAL type (via the param
                    // node, whose type is correct — the in-quote occurrence is resolved
                    // only to Atom), box it, and wrap it in a Grounded so it is a valid
                    // Atom. This is what makes `(eval '(+ $x 1))` capture `$x`'s value.
                    mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Grounded::class.java))
                    mv.visitInsn(Opcodes.DUP)
                    generateLoadVar(mv, param, function.params, isStatic, className)
                    boxIfNeeded(mv, paramType)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    generateLoadInt(2)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        Type.getInternalName(Grounded::class.java),
                        "<init>",
                        "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                        false
                    )
                } else {
                    // Use generateLoadVar which handles function params, captured
                    // lambda fields, AND falls back to generateNewVariable for
                    // truly free pattern variables (when className == null).
                    generateLoadVar(mv, atom, function.params, isStatic, className)
                }
            }

            is Lambda -> {
                // A Lambda atom inside a quoted expression — typically reached
                // when an outer call's head is unresolved and the whole
                // expression gets quoted as data (e.g. e2's unresolved
                // `get-state`). At runtime the lambda creation yields a
                // `JettaFunction` value; embedding that into the quoted
                // expression keeps the call shape intact so the matcher / a
                // downstream reduction can still see it.
                generateAtom(mv, atom, null, false)
            }

            is ArrowType -> {
                // Reify an arrow-type atom as the equivalent surface Expression
                // `(-> t1 t2 … return)`. The rewriter folds `(-> …)` literals
                // into [ArrowType] IR nodes (FunctionRewriter:339); reversing the
                // fold here keeps quoted/data positions structurally comparable
                // to whatever `(get-type …)` produces at runtime, without
                // needing a separate runtime ArrowType-construction path.
                generateQuote(mv, Expression(listOf(Special(Predefined.ARROW)) + atom.types))
            }

            else -> TODO("Not implemented yet $atom")
        }
    }

    /**
     * D2.3 eval-time `BadArgType` prologue for a `(: f (-> …))`-declared user function. Before any
     * clause is matched, reconstruct `(f arg…)` from the parameter slots (Atom args resolved to
     * their caller bindings — the same reconstruction the non-reduction fallback uses) and ask
     * [net.singularity.jetta.runtime.JettaProgram.typeCheckError]; if it returns a non-null
     * `(Error … (BadArgType …))` atom, return that immediately, so an ill-typed call errors instead
     * of reducing (`(Add S Z)` → error, not `S`). A null (well-typed, or a gradual/undeclared
     * argument) falls through to normal reduction.
     *
     * Gated to names in [declaredTypeNames] with all-`Atom` params and a scalar `Atom` return:
     *  - undeclared functions are skipped so hot untyped code (backchaining, symbolic interpreters)
     *    pays no per-call type-check cost;
     *  - a primitive/List return cannot hold an Error atom in its return slot, so those are skipped
     *    too (their eval-time errors are a later phase — multivalued needs a singleton-List wrap).
     */
    private fun maybeEmitTypeCheckPrologue(mv: LocalVariablesSorter) {
        val fn = function as? FunctionDefinition ?: return
        if (fn.name !in declaredTypeNames) return
        if (fn.isMultivalued() || fn.returnType != GroundedType.ATOM) return
        if (!fn.params.all { it.type == GroundedType.ATOM }) return

        mv.visitLdcInsn(fn.name)
        emitParamArgsArray(mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "net/singularity/jetta/runtime/functions/JettaCallSite",
            "nonReduced",
            "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Expression;",
            false,
        )
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "net/singularity/jetta/runtime/JettaProgram",
            "typeCheckError",
            "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
            false,
        )
        val cont = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, cont)
        // Non-null: the `(Error …)` atom is this call's only result — pop the Matcher frame and
        // return it (generateReturn ARETURNs it for the scalar-Atom return type).
        generateReturn(mv)
        mv.visitLabel(cont)
        mv.visitInsn(Opcodes.POP)
    }

    /**
     * D3 increment (a) — relational-dispatch prologue for the wildcard-swallow shape. A multivalued
     * function whose Match mixes a GUARDED clause (a constant like `(Mortal Socrates)`) with a
     * WILDCARD clause (`(Mortal $x)`) mis-handles a FREE-variable argument: the free var fails the
     * guard's equality and is swallowed by the wildcard, dropping the specific clause that would
     * UNIFY with it. Emit, at entry: `reduceRelationalIfFree(space, fn, args)` — which returns the
     * relational bag when an argument is a bare unbound Variable, else null to proceed with the
     * normal functional dispatch. On a non-null result, pop the entry frame (balance the push in
     * [generate]) and return it. The narrow shape trigger keeps purely-relational functions
     * (`(green $x)`, needing applicative arg reduction) and constant-only functions (which already
     * fall through to `reduceOrInert`) on their existing paths.
     */
    private fun maybeEmitRelationalPrologue(mv: LocalVariablesSorter, match: Match) {
        val funcName = (function as? FunctionDefinition)?.name ?: return
        val hasGuarded = match.branches.any { it.cond != null }
        val hasWildcard = match.branches.any { it.cond == null }
        if (!hasGuarded || !hasWildcard) return

        mv.visitLdcInsn(moduleSpaceName)
        mv.visitLdcInsn(funcName)
        emitParamArgsArray(mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "net/singularity/jetta/runtime/functions/JettaCallSite",
            "reduceRelationalIfFree",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List;",
            false
        )
        val proceed = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, proceed)
        generatePop(mv) // balance the entry Matcher.push() before the early return
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(proceed)
        mv.visitInsn(Opcodes.POP) // discard the null; fall through to functional dispatch
    }

    private fun generateMatch(mv: LocalVariablesSorter, match: Match) {
        maybeEmitTypeCheckPrologue(mv)
        // A Match whose clauses are provably mutually exclusive (FunctionRewriter did not
        // mark the function multivalued) has at most one matching branch, so it compiles to
        // a scalar dispatch — an if-else chain returning each branch value directly, with no
        // ArrayList and no map?/flat-map? composition. Deterministic destructuring functions
        // (ev/d/lookup) take this path and thus participate in plain arithmetic / deep
        // composition instead of dragging the List machinery.
        if ((function as? FunctionDefinition)?.isMultivalued() == false) {
            generateScalarMatch(mv, match)
            return
        }
        maybeEmitRelationalPrologue(mv, match)
        emitLineNumber(match)
        val returnType = match.returnType
            ?: match.branches.firstNotNullOfOrNull { it.body.type }
            ?: GroundedType.ATOM
        generateLoadInt(match.branches.size)
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
        val resultVar = mv.newLocal(Type.getObjectType("java/util/ArrayList"))
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List")
        mv.visitVarInsn(Opcodes.ASTORE, resultVar)

        // Non-reduction fallback bookkeeping. MeTTa semantics: reducing `(f a…)` is
        // the query `(= (f a…) $r)`; if NO clause head unifies, the expression does
        // not reduce and stays itself (its own normal form) — which is distinct from
        // "reduced to the empty result". We track a `matched` flag (OR over branches:
        // set when any guard passes, and always for an unconditional branch) and,
        // only if no branch matched, append the inert `(f a…)`. The flag — NOT
        // result.isEmpty() — is the correct trigger: an unconditional branch whose
        // body legitimately yields empty must not fall back. Emitted only when (a)
        // this is a named function and (b) some branch is guarded; an all-unconditional
        // Match always matches, so the flag and fallback would be dead code.
        // A visibility-guarded branch (sourceOrdinal >= 0) can also fail to match (rule hidden by
        // the run watermark), so it needs the matched-flag + inert fallback just like a cond guard
        // — otherwise a fully-guarded-out Match would return the empty bag instead of staying inert.
        val funcName = (function as? FunctionDefinition)?.name
        val matchedVar = if (funcName != null &&
            match.branches.any { it.cond != null || it.sourceOrdinal >= 0 }
        ) {
            val v = mv.newLocal(Type.BOOLEAN_TYPE)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, v)
            v
        } else -1

        match.branches.forEach { branch -> generateMatchBranch(mv, branch, returnType, resultVar, matchedVar) }

        if (matchedVar >= 0) {
            val skip = Label()
            mv.visitVarInsn(Opcodes.ILOAD, matchedVar)
            mv.visitJumpInsn(Opcodes.IFNE, skip)
            generateNonReductionFallback(mv, funcName!!, resultVar)
            mv.visitLabel(skip)
        }

        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        generatePop(mv)
        mv.visitInsn(Opcodes.ARETURN)
    }

    /**
     * Scalar (single-valued) Match: an if-else chain over the branches, each returning its
     * body directly via the `doReturn` path (which coerces to the function return type and,
     * for an `if` body, fans out into a nested return-chain — no join, no List). The
     * counterpart to the multivalued [generateMatch] for functions FunctionRewriter proved
     * deterministic.
     */
    private fun generateScalarMatch(mv: LocalVariablesSorter, match: Match) {
        emitLineNumber(match)
        match.branches.forEach { branch -> generateScalarMatchBranch(mv, branch) }
        generateScalarNoMatchFallback(mv)
    }

    private fun generateScalarMatchBranch(mv: LocalVariablesSorter, branch: MatchBranch) {
        val elseLabel = Label()
        emitBranchGuard(mv, branch, elseLabel)
        val savedLocals = enterDestructuring(mv, branch)
        // doReturn=true: generateAtom coerces the value to the return type and emits the
        // return; an `if` body recurses per-arm (each arm coerces + returns), so a value-if
        // with heterogeneous arms never needs a join.
        val prev = scalarReturnCoercion
        scalarReturnCoercion = true
        generateAtom(mv, branch.body, null, doReturn = true)
        scalarReturnCoercion = prev
        restoreDestructuring(savedLocals)
        // elseLabel is a jump target when EITHER a cond guard OR a visibility guard was emitted.
        if (branch.cond != null || branch.sourceOrdinal >= 0) mv.visitLabel(elseLabel)
    }

    /**
     * No branch of a scalar Match matched. For a reference (Atom) return this is MeTTa's
     * non-reduction: the expression is its own normal form, so return the inert
     * `(funcName args…)` (an Expression IS an Atom). A primitive-typed function cannot
     * represent that inert form, so a non-matching input is a type error there — throw.
     */
    private fun generateScalarNoMatchFallback(mv: LocalVariablesSorter) {
        val returnType = function.returnType
        val funcName = (function as? FunctionDefinition)?.name
        if (funcName != null && !(returnType is GroundedType && returnType.isGroundedValue())) {
            mv.visitLdcInsn(funcName)
            emitParamArgsArray(mv)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "net/singularity/jetta/runtime/functions/JettaCallSite",
                "nonReduced",
                "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Expression;",
                false
            )
            generateReturn(mv)
        } else {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn("no matching clause for ${funcName ?: "<anonymous>"}")
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "(Ljava/lang/String;)V",
                false
            )
            mv.visitInsn(Opcodes.ATHROW)
        }
    }

    /**
     * Build an `Object[]` of the current call's argument values from the parameter slots:
     * primitives loaded by their typed opcode and boxed, Atom params run through
     * `Matcher.resolveBinding` (so caller free variables appear as their bound values),
     * other references loaded raw. Shared by the non-reduction fallbacks.
     */
    private fun emitParamArgsArray(mv: LocalVariablesSorter) {
        val paramOffset = if (isStatic) 0 else 1
        generateLoadInt(function.params.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        function.params.forEachIndexed { i, param ->
            mv.visitInsn(Opcodes.DUP)
            generateLoadInt(i)
            val slot = i + paramOffset
            when (val t = param.type) {
                GroundedType.INT, GroundedType.BOOLEAN -> {
                    mv.visitVarInsn(Opcodes.ILOAD, slot); generateBoxingIfNeeded(t)
                }
                GroundedType.LONG -> { mv.visitVarInsn(Opcodes.LLOAD, slot); generateBoxingIfNeeded(t) }
                GroundedType.DOUBLE -> { mv.visitVarInsn(Opcodes.DLOAD, slot); generateBoxingIfNeeded(t) }
                GroundedType.ATOM -> {
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        RuntimeNames.MATCHER,
                        "resolveBinding",
                        "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
                        false
                    )
                }
                else -> mv.visitVarInsn(Opcodes.ALOAD, slot)
            }
            mv.visitInsn(Opcodes.AASTORE)
        }
    }

    /**
     * Non-reduction fallback for equality dispatch when no compiled clause head
     * matched (see [generateMatch]). Reconstructs `(funcName param0 param1 …)` from
     * the local slots — Atom params run through [Matcher.resolveBinding] so caller
     * free variables appear as their bound values; primitive params loaded with
     * their typed opcode and boxed — then hands it to
     * [net.singularity.jetta.runtime.functions.JettaCallSite.reduceOrInert]. That
     * tier-0 dynamic step tries a space `(= (funcName args…) $r)` unification first
     * (binding free-variable args the compiled `==`-path can't, e.g.
     * `(prevents (making $y) …)`), returning the FULL match bag, and falls back to
     * the inert `(funcName args…)` only when no rule unifies. The bag is `addAll`-ed
     * into the result list — each element a `BoundAtom` the enclosing
     * `flat-map?`/`map?` foliates per branch.
     */
    private fun generateNonReductionFallback(mv: LocalVariablesSorter, funcName: String, resultVar: Int) {
        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        mv.visitLdcInsn(moduleSpaceName)
        mv.visitLdcInsn(funcName)
        emitParamArgsArray(mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "net/singularity/jetta/runtime/functions/JettaCallSite",
            "reduceOrInert",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List;",
            false
        )
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true)
        mv.visitInsn(Opcodes.POP)
    }

    /**
     * Emit a branch guard: resolve caller bindings on Atom params, evaluate the branch
     * condition, and jump to [elseLabel] if it fails. No-op for an unconditional branch.
     * Shared by the multivalued (List) and scalar Match code paths.
     */
    private fun emitBranchGuard(mv: LocalVariablesSorter, branch: MatchBranch, elseLabel: Label) {
        // Ordered-top-level reduction guard (step 2): skip this clause when its `=` rule is not
        // yet visible at the current run's watermark (declared below the running `!`-form). A
        // skipped branch falls through to the non-reduction fallback -> the expression stays inert
        // (hyperon interleaved semantics). Only emitted for rules preceded by a run (ordinal >= 0);
        // hot facts-then-runs code has ordinal == -1 and pays nothing. Runs BEFORE the cond guard
        // so a hidden rule never even evaluates its pattern. See JettaProgram.isRuleVisible.
        if (branch.sourceOrdinal >= 0) {
            mv.visitLdcInsn(branch.sourceOrdinal)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "net/singularity/jetta/runtime/JettaProgram",
                "isRuleVisible",
                "(I)Z",
                false
            )
            mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)
        }
        val cond = branch.cond ?: return
        emitLineNumber(cond)
        // Resolve bindings on parameters before evaluating conditions, so shared
        // Variables passed from callers have their bindings applied before matching.
        for (i in function.params.indices) {
            if (function.params[i].type != GroundedType.ATOM) continue
            val paramOffset = if (isStatic) 0 else 1
            mv.visitVarInsn(Opcodes.ALOAD, i + paramOffset)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RuntimeNames.MATCHER,
                "resolveBinding",
                "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
                false
            )
            mv.visitVarInsn(Opcodes.ASTORE, i + paramOffset)
        }
        val label = Label()
        generateBooleanExpr(mv, cond, label)
        mv.visitLabel(label)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, elseLabel)
    }

    /**
     * Extract this branch's destructured pattern bindings into fresh local slots and
     * register them in [destructuredLocals]. Returns the previous map so the caller can
     * restore it after the branch (via [restoreDestructuring]). Shared by both paths.
     */
    private fun enterDestructuring(mv: LocalVariablesSorter, branch: MatchBranch): Map<String, Int> {
        val savedLocals = destructuredLocals.toMap()
        for (binding in branch.destructuredBindings) {
            val localSlot = mv.newLocal(Type.getObjectType("net/singularity/jetta/compiler/frontend/ir/Atom"))
            val paramOffset = if (isStatic) 0 else 1
            mv.visitVarInsn(Opcodes.ALOAD, binding.paramIndex + paramOffset)
            for (pathIndex in binding.extractionPath) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "net/singularity/jetta/compiler/frontend/ir/Expression")
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "net/singularity/jetta/compiler/frontend/ir/Expression",
                    "getAtoms",
                    "()Ljava/util/List;",
                    false
                )
                generateLoadInt(mv, pathIndex)
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
                mv.visitTypeInsn(Opcodes.CHECKCAST, "net/singularity/jetta/compiler/frontend/ir/Atom")
            }
            mv.visitVarInsn(Opcodes.ASTORE, localSlot)
            destructuredLocals[binding.syntheticName] = localSlot
        }
        return savedLocals
    }

    private fun restoreDestructuring(savedLocals: Map<String, Int>) {
        destructuredLocals.clear()
        destructuredLocals.putAll(savedLocals)
    }

    private fun generateMatchBranch(mv: LocalVariablesSorter, branch: MatchBranch, resultType: Atom, resultVar: Int, matchedVar: Int) {
        val elseLabel = Label()
        emitBranchGuard(mv, branch, elseLabel)
        val savedLocals = enterDestructuring(mv, branch)

        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        generateAtom(mv, branch.body, null, false)

        // If the branch body produces a List (SeqType), flatten it into the result
        // using addAll instead of add. This handles match/flat-map branches that
        // return multiple results.
        if (branch.body.type is SeqType) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true)
        } else {
            val bodyType = branch.body.type
            if (resultType is GroundedType && resultType.isGroundedValue() &&
                !(bodyType is GroundedType && bodyType.isGroundedValue())
            ) {
                // The branch produced an Atom at runtime (e.g. the body is a destructured
                // pattern variable like `$n` in `(= (ev (Lit $n)) $n)`) but the result
                // bag holds a primitive value type. Unwrap the Grounded to its boxed
                // value rather than boxing the Atom reference as if it were a primitive.
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(Grounded::class.java))
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(Grounded::class.java),
                    "getValue",
                    "()Ljava/lang/Object;",
                    false
                )
            } else {
                generateBoxingIfNeeded(resultType)
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        }
        mv.visitInsn(Opcodes.POP)

        if (matchedVar >= 0) {
            // Reached only when this branch's guard passed (guarded branch) or
            // unconditionally (no guard) — record that a clause head matched so the
            // non-reduction fallback in generateMatch is suppressed. Set regardless
            // of how many results the body produced (the flag tracks guard passage,
            // not result count).
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitVarInsn(Opcodes.ISTORE, matchedVar)
        }

        restoreDestructuring(savedLocals)

        // elseLabel is a jump target when EITHER a cond guard OR a visibility guard was emitted;
        // a hidden rule jumps here, leaving `matched` false so generateMatch emits the inert form.
        if (branch.cond != null || branch.sourceOrdinal >= 0) {
            mv.visitLabel(elseLabel)
        }
    }


    private fun generateSeq(mv: LocalVariablesSorter, arguments: List<Atom>, type: Atom) {
        generateLoadInt(arguments.size)
        val elementType = (type as SeqType).elementType
        val arr = mv.newLocal(Type.getObjectType("[${elementType.toJvmType(true)};"))
        mv.visitTypeInsn(Opcodes.ANEWARRAY, elementType.toJvmType(true).drop(1).dropLast(1))
        mv.visitVarInsn(Opcodes.ASTORE, arr)
        mv.visitVarInsn(Opcodes.ALOAD, arr)
        arguments.forEachIndexed { index, arg ->
            generateLoadInt(index)
            val argType = arg.type
            if (elementType is GroundedType && elementType.isGroundedValue() &&
                !(argType is GroundedType && argType.isGroundedValue())
            ) {
                // The list holds a primitive (grounded value) element type, but this arg is
                // an Atom at runtime — e.g. a destructured `$v` in a seq-wrapped scalar `if`
                // arm, whose type is Atom while the seq's element type comes from the other
                // (List) arm. Unwrap the Grounded to its raw value and re-box to the element
                // type so the singleton list is element-compatible with the other arm's List
                // (mirrors generateMatchBranch's scalar-result coercion). Without this the
                // consuming map?/flat-map? lambda unboxes the element and hits a
                // Grounded→Number ClassCastException.
                generateAtom(mv, arg, null, false)
                unwrapGroundedToPrimitive(mv, elementType)
                generateBoxingIfNeeded(elementType)
            } else {
                // Route the element load through generateAtom (not the raw generateLoad) so a
                // destructured-pattern local loads from its slot rather than being looked up
                // as a function parameter. Literals still take the `else -> generateLoad`
                // path; needBoxing boxes primitives / leaves Atom references untouched.
                generateAtom(mv, arg, null, false, needBoxing = true)
            }
            mv.visitInsn(Opcodes.AASTORE)
            mv.visitVarInsn(Opcodes.ALOAD, arr)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/util/Arrays",
            "asList",
            "([Ljava/lang/Object;)Ljava/util/List;",
            false
        )
    }

    /**
     * Emit a grounded VALUE arg wrapped in a `Grounded` Atom: `new Grounded(box(value))`.
     * Used when a primitive/String arg reaches an Atom-typed parameter (see [generateCall]) —
     * mirrors the capture-into-quote path so both produce the same runtime Atom shape.
     */
    private fun generateGroundedValueArg(mv: LocalVariablesSorter, arg: Atom, type: GroundedType) {
        mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Grounded::class.java))
        mv.visitInsn(Opcodes.DUP)
        generateAtom(mv, arg, null, false)
        generateBoxingIfNeeded(type)
        mv.visitInsn(Opcodes.ACONST_NULL)
        generateLoadInt(mv, 2)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            Type.getInternalName(Grounded::class.java),
            "<init>",
            "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            false
        )
    }

    /**
     * Coerce the value on the stack (a body / if-arm about to be returned) from its own
     * type to this function's declared/inferred return type — the partial-eval bridge
     * between the primitive and Grounded worlds. Only for scalar (non-multivalued)
     * functions; the multivalued path returns a List and handles its own element coercion.
     *
     *  - return primitive, body already that primitive → nothing (fast path).
     *  - return primitive, body Int / Double mismatch → numeric widen (Int→Double).
     *  - return primitive, body an Atom (a destructured `Grounded`) → unwrap to the raw
     *    primitive. Justified by the declared/inferred primitive return; the Grounded
     *    fallback below covers the cases where the return type stays Atom.
     *  - return reference (Atom), body a grounded VALUE → box + wrap in a Grounded (an Atom).
     *  - both references → nothing.
     */
    private fun coerceForReturn(atom: Atom) {
        (function as? FunctionDefinition)?.let { if (it.isMultivalued()) return } // List path
        val rt = function.returnType ?: return
        val bt = atom.type ?: return
        val rtIsValue = rt is GroundedType && rt.isGroundedValue()
        val btIsValue = bt is GroundedType && bt.isGroundedValue()
        // Whether the body actually left a REFERENCE on the stack — the real JVM shape, not the
        // node's resolved type. A lambda-call typed Any/Atom is unboxed to its arrow-return
        // primitive by generateLambdaCall, so the node type can lie (see [stackShapeType]).
        val stackIsRef = stackShapeType(atom)?.isGroundedValue() != true
        when {
            // BOX: a grounded VALUE on the stack where a reference (Atom) is returned — wrap
            // it in a Grounded. Safe generally (`bt` being a value implies a primitive is on
            // the stack): a scalar call's `int` result flowing into an Atom-returning map?/
            // flat-map? lambda, e.g. `(\dv. (ev dv))` over a multivalued `(d …)`.
            !rtIsValue && btIsValue -> wrapValueOnStackInGrounded(bt as GroundedType)
            // UNWRAP: a value is returned but the body left a reference on the stack (e.g.
            // `contains`'s else arm is a `let`-lambda typed Any → an Object). Coerce down to the
            // primitive: BOOLEAN through isTruthy — the reference may be the symbol True/False, a
            // Grounded<Boolean>, or a boxed Boolean — numeric types through a Grounded unwrap.
            // Gated on the REAL stack shape, not scalarReturnCoercion/node type, so it fires for
            // any function (not just scalar-Match bodies) yet never double-unwraps a call that
            // already left a raw primitive.
            rtIsValue && stackIsRef ->
                if (rt == GroundedType.BOOLEAN) {
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC, RuntimeNames.JETTA_PROGRAM,
                        "isTruthy", "(Ljava/lang/Object;)Z", false
                    )
                } else {
                    unwrapGroundedToPrimitive(mv, rt as GroundedType)
                }
            // Numeric widen: both are primitives on the stack (int→double). Only meaningful in
            // a scalar-Match body, where the branch bodies are known to leave a raw primitive.
            scalarReturnCoercion && rtIsValue && btIsValue ->
                if (bt == GroundedType.INT && rt == GroundedType.DOUBLE) mv.visitInsn(Opcodes.I2D)
            else -> { /* representations already match — return as-is */ }
        }
    }

    /** Box the primitive on top of the stack and wrap it in a `Grounded` (a valid Atom). */
    private fun wrapValueOnStackInGrounded(type: GroundedType) {
        generateBoxingIfNeeded(type)
        val tmp = mv.newLocal(Type.getObjectType("java/lang/Object"))
        mv.visitVarInsn(Opcodes.ASTORE, tmp)
        mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Grounded::class.java))
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, tmp)
        mv.visitInsn(Opcodes.ACONST_NULL)
        generateLoadInt(mv, 2)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            Type.getInternalName(Grounded::class.java),
            "<init>",
            "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            false
        )
    }

    private fun generateBoxingIfNeeded(type: Atom) {
        val (owner, name, desc) = when (type) {
            GroundedType.INT -> Triple("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
            GroundedType.BOOLEAN -> Triple("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
            GroundedType.DOUBLE -> Triple("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;")
            GroundedType.LONG -> Triple("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")
            else -> return
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false)
    }

    private fun generateCall(
        mv: LocalVariablesSorter,
        functionName: String,
        arguments: List<Atom>,
        resolved: ResolvedSymbol?
    ) {
        val (jvmSymbol, _) = resolved ?: throw UnresolvedSymbolError(functionName)
        if (resolved.alternatives.isNotEmpty()) {
            generateMultiOwnerCall(mv, jvmSymbol, resolved.alternatives, arguments)
            return
        }
        arguments.forEachIndexed { index, arg ->
            generateArgument(mv, jvmSymbol, index, arg)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            jvmSymbol.owner,
            jvmSymbol.name,
            jvmSymbol.descriptor,
            false
        )
        // If the called function returns void but we're inside a context that
        // needs a value on the stack (e.g., a lambda body), push null as a
        // placeholder so the stack isn't empty for areturn.
        if (jvmSymbol.descriptor.endsWith(")V")) {
            mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    /** Emit argument [index] of a call to [jvmSymbol], coerced to that parameter's shape. */
    private fun generateArgument(
        mv: LocalVariablesSorter,
        jvmSymbol: JvmMethod,
        index: Int,
        arg: Atom,
    ) {
        val argType = arg.type
        if (jvmSymbol.isParameterBooleanType(index) && arg is Symbol &&
            (arg.name == "True" || arg.name == "False")
        ) {
            // A MeTTa boolean literal (`True`/`False`) passed to a `Bool` (primitive `Z`)
            // parameter — push the primitive constant, not the Symbol object, which the
            // verifier rejects at the INVOKESTATIC call against a `Z` parameter. Mirror of
            // the Bool-return case above; sibling to `pushComparisonOperand`'s literal path.
            generateLoadBoolean(arg.name == "True")
        } else if (jvmSymbol.isParameterInertAtom(index) && arg is Expression) {
            // A FULLY-INERT Atom parameter (e.g. `get-type`): the argument must reach the
            // method un-reduced, so quote the whole term structurally (evalCalls=false ⇒ no
            // nested sub-call is evaluated). This is what makes `(get-type (Cons 0 (Cons 1
            // Nil)))` and `(get-type (drop (Cons 1 Nil)))` type-check the un-reduced
            // application; the plain-`generateAtom` path below would instead take the
            // `evalCalls=true` quote path and evaluate any `resolved != null` sub-application
            // (in d3 `Cons` is `resolved` via the `drop` rule, collapsing the term to `Nil`).
            generateQuote(mv, arg)
        } else if (jvmSymbol.isParameterAtomType(index) && argType is GroundedType && argType.isGroundedValue()) {
                // A grounded VALUE reaching an `Atom`-typed parameter is evaluated, boxed and
                // wrapped in a `Grounded` — which IS an Atom, where a bare box (Integer) is not,
                // so the verifier needs the wrapper. Both a literal (`(apply inc 5)`) and a
                // computed application (`(g (+ 1 $x))`) take this path.
                //
                // A computed one used to be QUOTED here instead, on the grounds that hyperon's
                // meta-type `Atom` suppresses argument reduction. That reads the meta-ness off the
                // JVM DESCRIPTOR — and `FunctionRewriter.asType()` erases every type it does not
                // know to `Atom`, so the rule fired for `Number`, `Nat`, `Either`, … as well:
                // `(: g (-> Number Number))` was handed the un-reduced `(+ 1 2)` and its body's
                // `Grounded` unwrap threw (f1_imports :65, and in one file
                // `(: r (-> Number Number)) (= (r $x) (+ $x 100)) !(assertEqual (r (+ 1 2)) 103)`).
                // Reducing is right for every erased type and wrong only for a parameter the user
                // really declared `Atom` — and `get-type`, the one place inertness is load-bearing,
                // is already handled precisely above by [JvmMethod.inertAtomParams]. Recording
                // "declared literally Atom" as a fact of the DECLARATION, rather than inferring it
                // from the erased descriptor, is the fix that would serve both; until then this
            // side of the trade is the one that matches the reference interpreter more often.
            generateGroundedValueArg(mv, arg, argType)
        } else {
            generateAtom(mv, arg, null, false, jvmSymbol.doesParameterHaveAnyType(index))
            narrowArgumentToExpression(mv, jvmSymbol, index, argType)
        }
    }

    /**
     * An argument that is statically WIDER than an `Expression` parameter needs the cast the
     * verifier will not infer for us.
     *
     * The value on the stack really is an `Expression` at runtime — a destructured pattern
     * binding, a quoted term, an `Atom`-returning call — but its static type is the erased `Atom`
     * (or `Object`), and `INVOKESTATIC` against a `…/ir/Expression;` parameter is then rejected at
     * CLASS-LOAD time: nothing about it is recoverable later, the whole class fails verification.
     * That is what the reference `stdlib.metta` hit — `switch-internal`, declared
     * `(-> Atom Expression Atom)`, calls `(switch-minimal $atom $tail)` with the `$tail` it
     * destructured out of `(($pattern $template) $tail)`, which is an `Atom` local.
     *
     * Only the widening direction is bridged here, and only for `Expression`: the cast is a
     * statement that the value already has that type and the descriptor knows it better than the
     * inferred type does. A grounded VALUE (primitive or String) reaching the same parameter is a
     * genuine type error rather than an erasure artefact — `CHECKCAST` cannot rescue an `int` on
     * the stack — so it is left to fail as before, and a `SeqType` (a `List` of results) is a
     * different bridge entirely.
     */
    private fun narrowArgumentToExpression(
        mv: LocalVariablesSorter,
        jvmSymbol: JvmMethod,
        index: Int,
        argType: Atom?
    ) {
        if (!jvmSymbol.isParameterExpressionType(index)) return
        if (argType == GroundedType.EXPRESSION) return
        if (argType is SeqType) return
        if (argType is GroundedType && argType.isGroundedValue()) return
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/singularity/jetta/compiler/frontend/ir/Expression")
    }

    /**
     * A call whose name several visible modules define: invoke EVERY owner and collect the
     * results into one bag, which is how the reference interpreter answers a name carrying more
     * than one `(= …)` rule (f1_imports :114 — `dup` from two imported modules gives `(12 102)`).
     * The resolver only ever hands us owners that share [primary]'s descriptor and are scalar
     * (see `Context.visibleAlternativeOwners`), so one evaluation of the arguments serves them
     * all and each call contributes exactly one element.
     *
     * The arguments go into locals rather than being re-emitted per owner: they must be evaluated
     * ONCE, or an argument with an effect (`(dup (add-atom! …))`) would perform it N times.
     */
    private fun generateMultiOwnerCall(
        mv: LocalVariablesSorter,
        primary: JvmMethod,
        alternatives: List<JvmMethod>,
        arguments: List<Atom>,
    ) {
        val paramTypes = Type.getArgumentTypes(primary.descriptor)
        val argSlots = arguments.mapIndexed { index, arg ->
            generateArgument(mv, primary, index, arg)
            val type = paramTypes[index]
            val slot = mv.newLocal(type)
            mv.visitVarInsn(type.getOpcode(Opcodes.ISTORE), slot)
            slot to type
        }
        val owners = listOf(primary) + alternatives
        val returnType = Type.getReturnType(primary.descriptor)
        val bag = mv.newLocal(Type.getObjectType("[Ljava/lang/Object;"))
        generateLoadInt(owners.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        mv.visitVarInsn(Opcodes.ASTORE, bag)
        owners.forEachIndexed { index, owner ->
            mv.visitVarInsn(Opcodes.ALOAD, bag)
            generateLoadInt(index)
            argSlots.forEach { (slot, type) -> mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot) }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner.owner, owner.name, owner.descriptor, false)
            boxJvmPrimitive(returnType)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitVarInsn(Opcodes.ALOAD, bag)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/util/Arrays",
            "asList",
            "([Ljava/lang/Object;)Ljava/util/List;",
            false
        )
    }

    /**
     * Box the primitive on top of the stack for storage in an `Object[]`, driven by the JVM
     * [type] rather than a MeTTa type (the sibling [generateBoxingIfNeeded] works off a
     * `GroundedType`). A reference is already storable and is left alone.
     */
    private fun boxJvmPrimitive(type: Type) {
        val (owner, desc) = when (type.sort) {
            Type.INT -> "java/lang/Integer" to "(I)Ljava/lang/Integer;"
            Type.BOOLEAN -> "java/lang/Boolean" to "(Z)Ljava/lang/Boolean;"
            Type.DOUBLE -> "java/lang/Double" to "(D)Ljava/lang/Double;"
            Type.LONG -> "java/lang/Long" to "(J)Ljava/lang/Long;"
            Type.FLOAT -> "java/lang/Float" to "(F)Ljava/lang/Float;"
            Type.CHAR -> "java/lang/Character" to "(C)Ljava/lang/Character;"
            Type.SHORT -> "java/lang/Short" to "(S)Ljava/lang/Short;"
            Type.BYTE -> "java/lang/Byte" to "(B)Ljava/lang/Byte;"
            else -> return
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false)
    }

    /**
     * Inline lambda application: `((\\ params body) args)`. After [LetRewriter] +
     * [net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter], every `let`
     * desugars into this shape, so it shows up routinely. The Lambda head is itself
     * an atom — emitting it creates a `JettaFunction` instance at runtime (via the
     * indy lambda metafactory); the args are packed into an `Object[]`; then
     * `INVOKEINTERFACE JettaFunction.apply` invokes the body.
     */
    private fun generateInlineLambdaCall(mv: LocalVariablesSorter, lambda: Lambda, arguments: List<Atom>) {
        generateAtom(mv, lambda, null, false)
        generateLoadInt(arguments.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        arguments.forEachIndexed { i, arg ->
            mv.visitInsn(Opcodes.DUP)
            generateLoadInt(i)
            generateAtom(mv, arg, null, false)
            boxIfNeeded(mv, arg.type as? GroundedType)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            JETTA_FUNCTION_INTERNAL_NAME,
            "apply",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            true,
        )
        val returnType = lambda.returnType as? GroundedType
        if (returnType != null) {
            unboxIfNeeded(mv, returnType)
        }
    }

    /**
     * Variable-headed application where the head has no static ArrowType.
     * Loads head + boxed-arg-array and dispatches through the JIT-eval bootstrap
     * (see [net.singularity.jetta.runtime.functions.JettaCallSite]). At runtime
     * the dispatcher invokes `JettaFunction.apply` if the head is a compiled
     * lambda; otherwise it builds an inert `(head args…)` Expression.
     */
    private fun generateDispatchCall(mv: LocalVariablesSorter, variable: Variable, arguments: List<Atom>) {
        val slot = destructuredLocals[variable.name]
        if (slot != null) {
            mv.visitVarInsn(Opcodes.ALOAD, slot)
        } else {
            generateLoadVar(mv, variable, function.params, isStatic, className)
        }
        generateLoadInt(arguments.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        arguments.forEachIndexed { i, arg ->
            mv.visitInsn(Opcodes.DUP)
            generateLoadInt(i)
            generateAtom(mv, arg, null, false)
            boxIfNeeded(mv, arg.type as? GroundedType)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInvokeDynamicInsn(
            "apply",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            CALL_SITE_BOOTSTRAP_HANDLE,
            // Static bootstrap arg: the space name to query for `(= …)` rules.
            moduleSpaceName,
        )
    }

    /**
     * Dispatch a `(head args…)` application whose head is not a statically-callable Symbol/
     * Variable slot — either an Expression head (a curried call such as `(((curry +) 2) 3)`) or
     * a Special-`=` head in a value position (the increment-C `=`-redex `(= a b)`). Mirrors
     * [generateDispatchCall] but pushes the head as a quoted Atom rather than loading a variable
     * slot: the head is quoted with `evalCalls = true` so any reducible scalar call nested in it
     * keeps its value-position semantics, args are packed and boxed exactly as for variable-head
     * dispatch, and the whole `(head args…)` is dispatched through the
     * [net.singularity.jetta.runtime.functions.JettaCallSite] bootstrap. At runtime the reducer
     * rewrites it via space `(= …)` rules (+ the registry / `=`-operand steps) or, if nothing
     * applies, returns it inert.
     */
    private fun generateExpressionHeadDispatchCall(mv: LocalVariablesSorter, atom: Expression) {
        val head = atom.atoms.first()
        val arguments = atom.atoms.drop(1)
        generateQuote(mv, head, evalCalls = true)
        generateLoadInt(arguments.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        arguments.forEachIndexed { i, arg ->
            mv.visitInsn(Opcodes.DUP)
            generateLoadInt(i)
            generateAtom(mv, arg, null, false)
            boxIfNeeded(mv, arg.type as? GroundedType)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInvokeDynamicInsn(
            "apply",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            CALL_SITE_BOOTSTRAP_HANDLE,
            // Static bootstrap arg: the space name to query for `(= …)` rules.
            moduleSpaceName,
        )
    }

    private fun generateLambdaCall(mv: LocalVariablesSorter, variable: Variable, arguments: List<Atom>) {
        val index = function.getParameterIndex(variable)
        if (index < 0) throw IllegalArgumentException(variable.toString())
        val arrowType = variable.type as ArrowType
        mv.visitVarInsn(Opcodes.ALOAD, index)
        // Pack arguments into Object[] for the JettaFunction SAM.
        generateLoadInt(arguments.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        arguments.forEachIndexed { i, arg ->
            mv.visitInsn(Opcodes.DUP)
            generateLoadInt(i)
            generateAtom(mv, arg, null, false)
            boxIfNeeded(mv, arg.type as? GroundedType)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            arrowType.getJvmInterfaceName(),
            "apply",
            arrowType.getApplyJvmPlainDescriptor(),
            true
        )
        val returnType = arrowType.types.last() as? GroundedType
        if (returnType != null) {
            unboxIfNeeded(mv, returnType)
        }
    }

    private fun generateReturn(mv: MethodVisitor) {
        // Only balance the entry push when this function actually established a frame.
        if (usesMatcher) {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RuntimeNames.MATCHER,
                "pop",
                "()V",
                false
            )
        }
        if (function is FunctionDefinition && function.isMultivalued()) {
            mv.visitInsn(Opcodes.ARETURN)
            return
        }
        when (function.returnType) {
            GroundedType.INT, GroundedType.BOOLEAN -> mv.visitInsn(Opcodes.IRETURN)
            GroundedType.LONG -> mv.visitInsn(Opcodes.LRETURN)
            GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DRETURN)
            GroundedType.UNIT -> mv.visitInsn(Opcodes.RETURN)
            // All reference-typed returns share ARETURN. Lambdas returning
            // lambdas (ArrowType return — common in higher-order code like
            // d2_higherfunc's `curry`) end up here.
            GroundedType.EXPRESSION -> {
                narrowReturnToExpression(mv)
                mv.visitInsn(Opcodes.ARETURN)
            }
            GroundedType.ATOM,
            GroundedType.LIST,
            GroundedType.STRING,
            GroundedType.SPACE,
            GroundedType.ANY,
            GroundedType.NOTHING,
            is SeqType,
            is ArrowType -> mv.visitInsn(Opcodes.ARETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
    }

    /**
     * The return-side twin of [narrowArgumentToExpression]: a body whose static type is the erased
     * `Atom` (or `Any`, or nothing at all) under a declared `Expression` return needs the cast
     * `ARETURN` will not infer, or the method fails verification and takes its whole class with it.
     *
     * The reference `stdlib.metta` redefines `cdr-atom` as `(: cdr-atom (-> Expression Expression))`
     * over `decons-atom` + `unify`, and that body resolves to `Atom` — the value really is an
     * Expression, only the inferred type is the top. As on the argument side, a grounded VALUE
     * (primitive or String) is a genuine mismatch rather than an erasure artefact and is left to
     * fail, and a `SeqType` body belongs to the multivalued path that already returned above.
     */
    private fun narrowReturnToExpression(mv: MethodVisitor) {
        val bodyType = function.body.type
        if (bodyType == GroundedType.EXPRESSION) return
        if (bodyType is SeqType) return
        if (bodyType is GroundedType && bodyType.isGroundedValue()) return
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/singularity/jetta/compiler/frontend/ir/Expression")
    }

    private fun containsVariable(atom: Atom): Boolean = when (atom) {
        is Variable -> true
        is Expression -> atom.atoms.any { containsVariable(it) }
        else -> false
    }

    /**
     * The primitive type a comparison should operate on, or null for a reference
     * (symbol/structural) comparison. If either operand is statically a primitive
     * (Int/Double/Boolean) the comparison is numeric/primitive, and the OTHER operand —
     * an `Atom` at runtime, e.g. a destructured `Grounded` number — is unwrapped to that
     * primitive (see [coerceComparisonOperand]). Matches hyperon: `(== $k 0)` compares
     * grounded values, not object identity.
     */
    private fun numericCompareType(left: Atom, right: Atom): GroundedType? = when {
        // An operand that stays UNREDUCED is an `Expression` at runtime, never a grounded number,
        // so no numeric opcode can consume it — `(== 4 (+ ln 2))` must compare structurally and
        // yield False (c1), not cast an Expression to Grounded. Distinct from an Atom-typed CALL
        // (`(== (foo) 5)`), which does hold a grounded number at runtime and keeps the numeric path.
        isInertArithmetic(left) || isInertArithmetic(right) -> null
        left.type == GroundedType.DOUBLE || right.type == GroundedType.DOUBLE -> GroundedType.DOUBLE
        left.type == GroundedType.INT || right.type == GroundedType.INT -> GroundedType.INT
        left.type == GroundedType.BOOLEAN || right.type == GroundedType.BOOLEAN -> GroundedType.BOOLEAN
        else -> null
    }

    /** An arithmetic form the resolver could not compute (an unreducible operand), so it is data:
     *  arithmetic head, `ATOM` type and no resolved callee. Counterpart of [isGroundedArithmetic]. */
    private fun isInertArithmetic(atom: Atom): Boolean =
        atom is Expression && atom.type == GroundedType.ATOM && atom.resolved == null &&
            (atom.atoms.firstOrNull() as? Special)?.value in ARITHMETIC_OPS

    /**
     * Arguments of [atom] that may turn out to be an `(Error … (BadArgType …))` at run time: an
     * unreducible arithmetic form with a SYMBOL operand, whose verdict depends on a `:` declaration
     * in the space and therefore on the run's position (see
     * [generateInertArithmeticWithTypeCheck]).
     */
    private fun runtimeErrorableOperands(atom: Expression): List<Expression> =
        atom.atoms.drop(1).filterIsInstance<Expression>().filter { operand ->
            isInertArithmetic(operand) && operand.atoms.drop(1).any { it is Symbol }
        }

    /**
     * Whether an ORDERING comparison (`<`/`>`/`<=`/`>=`) should emit DOUBLE opcodes.
     * True when either operand is statically DOUBLE, OR when NEITHER is a statically-known
     * primitive (both `Atom`, e.g. `min`'s `$a`/`$b` bound to destructured `Grounded`
     * numbers): at runtime the values are grounded numbers, and `Number.doubleValue()` reads
     * an `Int` or a `Double` alike, so comparing as `double` is exact for both. Comparing
     * such operands as `int` (the old fall-through) truncated a `Double` to 0 — `(< 0.8 0.9)`
     * became `0 < 0` = false, so `min` always returned its second argument (c3). Statically
     * INT/BOOLEAN operands keep integer opcodes. Only for ordering: `==`/`!=` on two `Atom`s
     * is a STRUCTURAL comparison, handled separately.
     */
    private fun orderingUsesDouble(left: Atom, right: Atom): Boolean =
        numericCompareType(left, right).let { it == null || it == GroundedType.DOUBLE }

    /** If [actual] is a reference (Atom/Any) but a primitive [target] is required for the
     *  comparison, coerce it. For a BOOLEAN target the reference may be the *symbol* True/False
     *  (a MeTTa boolean is a Symbol, not a Grounded<Boolean>) — e.g. `(== (croaks $x) True)`
     *  where croaks yields the symbol True — so route through the runtime isTruthy, which
     *  accepts a Symbol or a Grounded. Numeric targets unwrap the Grounded and unbox. */
    private fun coerceComparisonOperand(mv: MethodVisitor, actual: Atom?, target: GroundedType) {
        if (actual == GroundedType.ATOM || actual == GroundedType.ANY) {
            if (target == GroundedType.BOOLEAN) {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    RuntimeNames.JETTA_PROGRAM,
                    "isTruthy",
                    "(Ljava/lang/Object;)Z",
                    false
                )
            } else if (actual == GroundedType.ANY) {
                // An `Any` (Object) slot may hold a bare box as well as a `Grounded` — see
                // [unwrapReferenceToPrimitive]. `(: f (-> Number Number))` erases its parameter to
                // Object, and `(< $x 0)` used to cast it straight to `Grounded` (f1_moduleA :13).
                unwrapReferenceToPrimitive(mv, target)
            } else {
                unwrapGroundedToPrimitive(mv, target)
            }
        }
    }

    /**
     * The [GroundedType] `generateAtom(operand, doReturn=false)` actually leaves on the JVM
     * stack — which is NOT always `operand.type`. A call whose head is a Variable carrying an
     * `ArrowType` is emitted by [generateLambdaCall], which unboxes the result to the arrow's
     * *return* type; an inline `((\ …) …)` is emitted by [generateInlineLambdaCall], which
     * unboxes to the Lambda's `returnType`. In both cases the call *node's* own resolved type
     * can be `Any`/`Atom` (a reference) while the value on the stack is a raw primitive.
     *
     * The condition/comparison sites need this to decide re-boxing: guessing the stack shape
     * from `operand.type` (the node type) disagrees with what the producer emitted, so a
     * `Bool`-returning `($cond $x)` under an `Any` node was unboxed to `int` and then handed to
     * `isTruthy(Object)` unboxed → VerifyError. This is the single authoritative mirror of the
     * value-shaping in [generateAtom]'s Expression dispatch (see :237-327).
     */
    private fun stackShapeType(operand: Atom): GroundedType? {
        if (operand is Expression) {
            // A MULTIVALUED call leaves a `List` — a reference — whatever its scalar element
            // type says. The node keeps that element type (`is-space` is `@multivalued` but
            // declared `(-> Atom Bool)`), so reading the node type here would claim a raw
            // `boolean` is on the stack and skip the coercion the consumer needs. Same reality
            // the boxing guard in [generateAtom] already accounts for.
            if (operand.resolved?.isMultiValued == true) return GroundedType.LIST
            val head = operand.atoms.firstOrNull()
            if (head is Variable && head.type is ArrowType)
                return (head.type as ArrowType).types.last() as? GroundedType
            if (head is Lambda) return head.returnType as? GroundedType
        }
        return operand.type as? GroundedType
    }

    private fun generateBooleanExpr(mv: LocalVariablesSorter, expr: Atom, exit: Label) {
        emitLineNumber(expr)
        // Push one operand of a primitive comparison onto the stack. A MeTTa boolean is the
        // bare symbol `True`/`False` (not a `Grounded<Boolean>`), so when it appears as a
        // literal operand of a BOOLEAN comparison — e.g. the `True` in a rule LHS
        // `(= (ift True $then) …)`, lowered to `(== $param True)` — load the primitive
        // constant directly. Otherwise it would go through generateAtom (pushing a Symbol)
        // + coerceComparisonOperand, which unwraps it as a Grounded and CHECKCASTs on a
        // Symbol at runtime (ClassCastException).
        fun pushComparisonOperand(operand: Atom, elemType: GroundedType) {
            if (elemType == GroundedType.BOOLEAN && operand is Symbol &&
                (operand.name == "True" || operand.name == "False")
            ) {
                generateLoadBoolean(operand.name == "True")
                return
            }
            val label = Label()
            generateAtom(mv, operand, label, false)
            mv.visitLabel(label)
            coerceComparisonOperand(mv, stackShapeType(operand), elemType)
        }

        // Reduce a VALUE operand (a Variable/Symbol/Grounded/call — anything that is not a
        // comparison or logical sub-expression) to the primitive boolean the surrounding
        // IF_ICMP expects. A MeTTa boolean is the bare symbol True/False (an Atom), most often
        // produced by a user function — e.g. `(and $__var0 $__var1)` in a rewritten
        // conjunction, where each var holds the *symbol* True a multivalued croaks/eat_flies
        // returned. Route through the runtime isTruthy, which accepts the True/False symbol, a
        // Grounded<Boolean>, or a boxed primitive. Leaves the int 0/1 on the stack and falls
        // through to the caller's `exit` label (placed immediately after, like the comparison
        // paths' explicit GOTO exit).
        fun pushValueAsCondition(operand: Atom) {
            generateAtom(mv, operand, null, false)
            // Re-box off the shape actually left on the stack, not the node type: a
            // Bool-returning `($cond $x)` is unboxed to a primitive by generateLambdaCall
            // even when its node resolved to Any — isTruthy(Object) needs the reference.
            stackShapeType(operand)?.takeIf { it.isGroundedValue() }
                ?.let { generateBoxingIfNeeded(it) }
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RuntimeNames.JETTA_PROGRAM,
                "isTruthy",
                "(Ljava/lang/Object;)Z",
                false
            )
        }

        // Push an operand for a STRUCTURAL (`Object.equals`) comparison, which needs a reference.
        // A grounded literal leaves a primitive on the stack — `(== 4 (+ ln 2))`, where the right
        // operand is unreduced so the comparison is structural — so box it into its Grounded atom
        // (an int on the stack would fail the verifier at `equals`).
        fun pushComparisonOperandAsAtom(operand: Atom) {
            generateAtom(mv, operand, null, false)
            stackShapeType(operand)?.takeIf { it.isGroundedValue() }
                ?.let { wrapValueOnStackInGrounded(it) }
        }

        fun generateIntComparison(left: Atom, right: Atom, inverseOp: Int, elemType: GroundedType = GroundedType.INT) {
            pushComparisonOperand(left, elemType)
            pushComparisonOperand(right, elemType)
            val jumpIfFalse = Label()
            mv.visitJumpInsn(inverseOp, jumpIfFalse)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitJumpInsn(Opcodes.GOTO, exit)
            mv.visitLabel(jumpIfFalse)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitJumpInsn(Opcodes.GOTO, exit)
        }

        fun generateDoubleGt(left: Atom, right: Atom, branchOpcode: Int) {
            val labelTrue = Label()

            // Push operands: left, then right
            generateAtom(mv, left, null, false)
            coerceComparisonOperand(mv, stackShapeType(left), GroundedType.DOUBLE)
            generateAtom(mv, right, null, false)
            coerceComparisonOperand(mv, stackShapeType(right), GroundedType.DOUBLE)

            // Compare the two doubles (result is int)
            mv.visitInsn(Opcodes.DCMPG)

            // Branch to true if comparison passes
            mv.visitJumpInsn(branchOpcode, labelTrue)

            // False case: push 0
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitJumpInsn(Opcodes.GOTO, exit)

            // True case: push 1
            mv.visitLabel(labelTrue)
            mv.visitInsn(Opcodes.ICONST_1)
            // Falls through to exit
        }


        when (expr) {
            is Expression -> {
                val (op, left) = expr.atoms
                val right = expr.atoms.getOrNull(2)
                when ((op as? Special)?.value) {
                    Predefined.NOT -> {
                        mv.visitInsn(Opcodes.ICONST_1)
                        val label = Label()
                        generateBooleanExpr(mv, left, label)
                        mv.visitLabel(label)
                        mv.visitInsn(Opcodes.ISUB)
                    }

                    Predefined.AND -> {
                        val label = Label()
                        generateBooleanExpr(mv, left, label) // true or false on stack
                        mv.visitLabel(label)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val next = Label()
                        mv.visitJumpInsn(Opcodes.IF_ICMPNE, next) // if true then check the right part
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, exit)
                        mv.visitLabel(next)
                        generateBooleanExpr(mv, right!!, exit)
                    }

                    Predefined.OR -> {
                        val label = Label()
                        generateBooleanExpr(mv, left, label)
                        mv.visitLabel(label)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val next = Label()
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, next)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, exit)
                        mv.visitLabel(next)
                        generateBooleanExpr(mv, right!!, exit)
                    }

                    Predefined.XOR -> {
                        val label1 = Label()
                        generateBooleanExpr(mv, left, label1)
                        mv.visitLabel(label1)
                        val label2 = Label()
                        generateBooleanExpr(mv, right!!, label2)
                        mv.visitLabel(label2)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.ICONST_2)
                        mv.visitInsn(Opcodes.IREM) // not the best way but simple
                    }

                    Predefined.COND_EQ -> {
                        val cmp = numericCompareType(left, right!!)
                        if (cmp == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right, Opcodes.IFEQ)
                        } else if (cmp == GroundedType.INT || cmp == GroundedType.BOOLEAN) {
                            generateIntComparison(left, right, Opcodes.IF_ICMPNE, cmp)
                        } else {
                            // Reference types (Atom, Symbol, etc.)
                            // If the right side is an Expression containing Variables,
                            // use Matcher.match for structural pattern matching
                            // (e.g., (== $var0 (And $a $b)) should match (And X Y))
                            if (right is Expression && containsVariable(right)) {
                                // Use Matcher.match(left, pattern) -> boolean
                                generateAtom(mv, left, null, false)
                                generateQuote(mv, right)
                                mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    RuntimeNames.MATCHER,
                                    "structuralMatch",
                                    "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Z",
                                    false
                                )
                            } else {
                                pushComparisonOperandAsAtom(left)
                                pushComparisonOperandAsAtom(right!!)
                                mv.visitMethodInsn(
                                    Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Object",
                                    "equals",
                                    "(Ljava/lang/Object;)Z",
                                    false
                                )
                            }
                            // equals()/structuralMatch() returns boolean (0 or 1)
                        }
                    }

                    Predefined.COND_NEQ -> {
                        val cmp = numericCompareType(left, right!!)
                        if (cmp == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right, Opcodes.IFNE)
                        } else if (cmp == GroundedType.INT || cmp == GroundedType.BOOLEAN) {
                            generateIntComparison(left, right, Opcodes.IF_ICMPEQ, cmp)
                        } else {
                            // Reference types — use !Object.equals()
                            pushComparisonOperandAsAtom(left)
                            pushComparisonOperandAsAtom(right!!)
                            mv.visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/lang/Object",
                                "equals",
                                "(Ljava/lang/Object;)Z",
                                false
                            )
                            // Invert: 1 - equals_result
                            mv.visitInsn(Opcodes.ICONST_1)
                            mv.visitInsn(Opcodes.SWAP)
                            mv.visitInsn(Opcodes.ISUB)
                        }
                    }

                    Predefined.COND_GT -> {
                        if (orderingUsesDouble(left, right!!)) {
                            generateDoubleGt(left, right, Opcodes.IFGT)
                        } else {
                            generateIntComparison(left, right, Opcodes.IF_ICMPLE)
                        }
                    }

                    Predefined.COND_LT -> {
                        if (orderingUsesDouble(left, right!!)) {
                            generateDoubleGt(left, right, Opcodes.IFLT)
                        } else {
                            generateIntComparison(left, right, Opcodes.IF_ICMPGE)
                        }
                    }

                    Predefined.COND_GE -> {
                        if (orderingUsesDouble(left, right!!)) {
                            generateDoubleGt(left, right, Opcodes.IFGE)
                        } else {
                            generateIntComparison(left, right, Opcodes.IF_ICMPLT)
                        }
                    }

                    Predefined.COND_LE -> {
                        if (orderingUsesDouble(left, right!!)) {
                            generateDoubleGt(left, right, Opcodes.IFLE)
                        } else {
                            generateIntComparison(left, right, Opcodes.IF_ICMPGT)
                        }
                    }

                    // A non-comparison expression used as a condition — a predicate call
                    // `(is-var $x)`, a nested `(if …)`, any boolean-returning form. Evaluate
                    // it to a value and test truthiness at runtime (hyperon booleans are the
                    // symbols True/False; a grounded Boolean also counts). Leaves the int 0/1
                    // the caller's branch test expects.
                    else -> pushValueAsCondition(expr)
                }
            }

            // A bare boolean literal used directly as a condition — `(if True …)` — must
            // leave the int 0/1 the caller's IF_ICMPNE expects, not the Symbol/Grounded
            // object that generateAtom would push (which the verifier rejects against int).
            // Ordinary conditions are comparison Expressions handled above; this covers the
            // constant-condition and boolean-valued-Grounded cases.
            is Symbol -> when (expr.name) {
                "True" -> mv.visitInsn(Opcodes.ICONST_1)
                "False" -> mv.visitInsn(Opcodes.ICONST_0)
                // Any other symbol used as a condition is a value — test its truthiness.
                else -> pushValueAsCondition(expr)
            }

            is Grounded<*> -> if (expr.value is Boolean) {
                generateLoadBoolean(expr.value as Boolean)
            } else {
                pushValueAsCondition(expr)
            }

            // A bare value operand (most often a Variable that is a boxed Atom at runtime —
            // e.g. an `(and $a $b)` conjunct holding the symbol True) reduced to a primitive.
            else -> pushValueAsCondition(expr)
        }
    }

    private fun generateIf(mv: LocalVariablesSorter, arguments: List<Atom>, exit: Label?, doReturn: Boolean) {
        val (cond, thenExpr, elseExpr) = arguments
        val label = Label()
        generateBooleanExpr(mv, cond, label)
        mv.visitLabel(label)
        mv.visitInsn(Opcodes.ICONST_1)
        val elseLabel = Label()
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, elseLabel)
        // Value-producing if (the body of a Match branch / an argument): neither branch
        // returns nor jumps to an outer exit, so the then-branch must jump PAST the else
        // to a join — otherwise it falls through and both branches' values pile on the
        // stack (ASM Frame.merge fails). For the doReturn / exit cases each branch already
        // leaves via areturn / GOTO exit, so no join is needed.
        val joinLabel = if (!doReturn && exit == null) Label() else null
        // When the two arms leave DIFFERENT JVM stack types — a grounded primitive vs an
        // object Atom, as in a 2-arg `(if cond then)` whose synthesized `()` else is an
        // Expression — the join (or the method return) merges `int` with a reference and the
        // verifier rejects it. Homogenize to Atom: wrap the primitive arm in Grounded (which
        // IS an Atom). Only in the value-producing path, where each arm leaves its value on
        // the stack right before the join so we can wrap in place.
        val thenPrim = (thenExpr.type as? GroundedType)?.takeIf { it.isGroundedValue() }
        val elsePrim = (elseExpr.type as? GroundedType)?.takeIf { it.isGroundedValue() }
        val homogenize = joinLabel != null && (thenPrim == null) != (elsePrim == null)
        generateAtom(mv, thenExpr, exit, doReturn)
        if (homogenize && thenPrim != null) wrapValueOnStackInGrounded(thenPrim)
        if (joinLabel != null) mv.visitJumpInsn(Opcodes.GOTO, joinLabel)
        mv.visitLabel(elseLabel)
        generateAtom(mv, elseExpr, exit, doReturn)
        if (homogenize && elsePrim != null) wrapValueOnStackInGrounded(elsePrim)
        if (joinLabel != null) mv.visitLabel(joinLabel)
    }

    // FIXME: controversial, consider other options e.g. Atom <- Typed
    private fun Atom.type(): Atom =
        when (this) {
            is GroundedType -> this.type!!
            is Variable -> this.type!!
            is Expression -> this.type!!
            is Grounded<*> -> this.type!!
            else -> TODO("$this")
        }

    private fun castIfNeeded(mv: MethodVisitor, type: Atom, requiredType: Atom) {
        if (type == requiredType) return
        if (type == GroundedType.INT && requiredType == GroundedType.DOUBLE) {
            mv.visitInsn(Opcodes.I2D)
            return
        }
        // Arithmetic over a value that is an Atom at runtime (an untyped function result
        // or a free/Atom-typed variable that is a Grounded number) — `(+ (foo) (bar))`.
        // Matches hyperon's grounded-op semantics: reduce the operand to its number.
        // Unwrap the Grounded and unbox to the required primitive (Number.doubleValue
        // copes when the Grounded holds an Int but a Double is required).
        if ((type == GroundedType.ATOM || type == GroundedType.ANY) &&
            requiredType is GroundedType && requiredType.isGroundedValue()
        ) {
            // An `Any` (Object) slot may hold the bare box a call site produced from a computed
            // primitive as well as a `Grounded` — `(: r (-> Any Any))` over `(r (+ 1 2))` passed
            // an `Integer` and the direct `Grounded` cast threw. `Atom` slots hold Atoms by
            // construction and keep the direct unwrap, so the hot path is unchanged.
            if (type == GroundedType.ANY) unwrapReferenceToPrimitive(mv, requiredType)
            else unwrapGroundedToPrimitive(mv, requiredType)
            return
        }
        TODO("castIfNeeded $type -> $requiredType")
    }

    private fun generateDivide(
        mv: LocalVariablesSorter,
        arguments: List<Atom>,
        doReturn: Boolean
    ) {
        generateAtom(mv, arguments[0], null, false)
        castIfNeeded(mv, arguments[0].type(), GroundedType.DOUBLE)
        generateAtom(mv, arguments[1], null, false)
        castIfNeeded(mv, arguments[1].type(), GroundedType.DOUBLE)
        mv.visitInsn(Opcodes.DDIV)
        if (doReturn) {
            boxPrimitiveForReferenceReturn(GroundedType.DOUBLE)
            generateReturn(mv)
        }
    }

    private fun generateDiv(
        mv: LocalVariablesSorter,
        arguments: List<Atom>,
        doReturn: Boolean
    ) {
        generateAtom(mv, arguments[0], null, false)
        castIfNeeded(mv, arguments[0].type(), GroundedType.INT)
        generateAtom(mv, arguments[1], null, false)
        castIfNeeded(mv, arguments[1].type(), GroundedType.INT)
        mv.visitInsn(Opcodes.IDIV)
        if (doReturn) {
            boxPrimitiveForReferenceReturn(GroundedType.INT)
            generateReturn(mv)
        }
    }

    private fun generateMod(
        mv: LocalVariablesSorter,
        arguments: List<Atom>,
        doReturn: Boolean
    ) {
        generateAtom(mv, arguments[0], null, false)
        castIfNeeded(mv, arguments[0].type(), GroundedType.INT)
        generateAtom(mv, arguments[1], null, false)
        castIfNeeded(mv, arguments[1].type(), GroundedType.INT)
        mv.visitInsn(Opcodes.IREM)
        if (doReturn) {
            boxPrimitiveForReferenceReturn(GroundedType.INT)
            generateReturn(mv)
        }
    }

    /**
     * D2.2 (tier i): if [atom] is a grounded arithmetic application with a concrete non-numeric
     * operand (today `String`), the `(Error <atom> (BadArgType <pos> Number <actual>))` atom to
     * emit in its place, else null. `pos` is 1-based over the operands. Mirrors the resolver's
     * [net.singularity.jetta.compiler.frontend.resolve.Context] `hasGroundedArithmeticTypeError`
     * detection, and is only consulted once the resolver has stamped the node `ATOM`.
     */
    private fun groundedArithmeticError(atom: Expression): Expression? {
        atom.atoms.drop(1).forEachIndexed { i, operand ->
            if (operand.type == GroundedType.STRING) {
                val badArg = Expression(Symbol("BadArgType"), Grounded(i + 1), Symbol("Number"), Symbol("String"))
                return Expression(Symbol("Error"), atom, badArg)
            }
        }
        return null
    }

    /** The MeTTa-level type name of a grounded value type (the name hyperon uses in a
     *  `BadArgType`): the numeric tower collapses to `Number`, the rest map by name. */
    private fun mettaTypeName(t: Atom?): String = when (t) {
        GroundedType.INT, GroundedType.LONG, GroundedType.DOUBLE -> "Number"
        GroundedType.STRING -> "String"
        GroundedType.BOOLEAN -> "Bool"
        else -> "%Undefined%"
    }

    /**
     * D2.4 (increment 1): build the `(Error <call> (BadArgType <pos> <exp> <act>))` atom for a
     * type-errored `==`/`!=`. `==` is grounded `(-> $t $t Bool)`, so `$t` binds to the first
     * operand's type and any later operand of a different type is the offender. Report its
     * 1-based position, the expected type (arg0's), and its actual type — mirroring hyperon's
     * left-to-right binding (`(== 5 "S")` → `(BadArgType 2 Number String)`; the reversed
     * `(== "S" 5)` → `(BadArgType 2 String Number)`). Returns null if no mismatch is found.
     */
    private fun comparisonBadArgType(atom: Expression): Expression? {
        val operands = atom.atoms.drop(1)
        if (operands.size < 2) return null
        val expected = mettaTypeName(operands[0].type)
        operands.drop(1).forEachIndexed { i, operand ->
            val actual = mettaTypeName(operand.type)
            if (actual != expected) {
                val badArg = Expression(Symbol("BadArgType"), Grounded(i + 2), Symbol(expected), Symbol(actual))
                return Expression(Symbol("Error"), atom, badArg)
            }
        }
        return null
    }

    /**
     * D2.4 (increment 4): the first argument of an inert/undefined application that is itself a
     * statically-known grounded type error, as its `(Error … (BadArgType …))` atom — or null if no
     * argument statically errors. Errors are absorbing in hyperon: reducing `(f (+ 5 "S"))` reduces
     * the argument first, surfaces its error, and that error becomes the whole application's value.
     * Only the compile-time-decidable grounded cases (a String operand of `+`/`-`/`*`, a
     * numeric-vs-String `==`/`!=`) are recognised here — the same shapes [generateArithmetics] /
     * the COND_EQ branch turn into errors in a value position. Not recursive (a direct argument
     * only), matching the `(f (+ 5 "S"))` shape.
     */
    private fun firstStaticArgError(atom: Expression): Expression? {
        atom.atoms.drop(1).forEach { arg ->
            if (arg is Expression && arg.type == GroundedType.ATOM) {
                when ((arg.atoms.firstOrNull() as? Special)?.value) {
                    Predefined.PLUS, Predefined.MINUS, Predefined.TIMES ->
                        groundedArithmeticError(arg)?.let { return it }
                    Predefined.COND_EQ, Predefined.COND_NEQ ->
                        comparisonBadArgType(arg)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * D2.4 (increment 2): whether a STRUCTURAL `==`/`!=` (reference operands) warrants a runtime
     * type check. The custom types being compared live as `:` facts, so the error can only be
     * decided at runtime ([net.singularity.jetta.runtime.JettaProgram.typeCheckError]). Gated
     * tightly: the program must declare types at all ([declaredTypeNames]), both operands must be
     * stable source terms (non-`Variable`), and the comparison must be structural
     * ([numericCompareType] `== null`) — a numeric/Bool comparison cannot carry a custom-type
     * mismatch, and hot untyped code (no `:` facts) pays nothing.
     */
    private fun needsRuntimeComparisonTypeCheck(operands: List<Atom>): Boolean =
        declaredTypeNames.isNotEmpty() &&
            operands.size == 2 &&
            operands.none { it is Variable } &&
            numericCompareType(operands[0], operands[1]) == null

    /**
     * Emit a value-position structural `==`/`!=` guarded by an eval-time type check (D2.4
     * increment 2). Reconstruct `(== a b)`, ask `JettaProgram.typeCheckError`: a non-null result is
     * the `(Error … (BadArgType …))` atom (the comparison's value); a null means well-typed /
     * gradual, so fall through to the ordinary Bool comparison. Both outcomes leave a single Atom
     * (an `Error` expression or a `Grounded<Bool>`) on the stack, so they merge cleanly. Only called
     * with `doReturn == false`; honours [exit] (jump) or leaves the value on the stack (join).
     */
    private fun generateComparisonWithTypeCheck(mv: LocalVariablesSorter, atom: Expression, exit: Label?) {
        generateQuote(mv, atom)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RuntimeNames.JETTA_PROGRAM,
            "typeCheckError",
            "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
            false,
        )
        val wellTyped = Label()
        val join = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, wellTyped)
        // Non-null: the Error atom on the stack IS the result — jump past the Bool path.
        mv.visitJumpInsn(Opcodes.GOTO, join)
        mv.visitLabel(wellTyped)
        mv.visitInsn(Opcodes.POP) // drop the null
        // Well-typed: the ordinary Bool comparison. generateIf(exit=null) leaves the raw primitive
        // boolean on the stack; box it into a Grounded<Bool> so BOTH edges reaching [join] carry an
        // Atom (an `Error` expression or a `Grounded`) — merging a primitive with a reference is a
        // VerifyError (`top` stack slot). The epilogue's boxing is bypassed (this branch `return`s
        // from generateAtom), so the boxing is done here explicitly.
        generateIf(mv, listOf(atom, Grounded(true), Grounded(false)), null, false)
        wrapValueOnStackInGrounded(GroundedType.BOOLEAN)
        mv.visitLabel(join)
        if (exit != null) mv.visitJumpInsn(Opcodes.GOTO, exit)
    }

    /**
     * An unreducible arithmetic form whose operand is a symbol: emit `(Error … (BadArgType …))` when
     * that symbol has a declared non-numeric type, else the inert form itself. The decision must be
     * made at runtime because `:` declarations are space facts read through
     * [net.singularity.jetta.runtime.JettaProgram.typeCheckError], which honours the run's watermark
     * — so the same `(+ ln 2)` is unreduced above `(: ln LN)` and an error below it. Both edges leave
     * an Atom on the stack, so no boxing is needed at the join.
     */
    /**
     * hyperon's errors are ABSORBING: when an argument reduces to `(Error …)`, the enclosing
     * application IS that error, not a comparison result. D2.4 increment 4 does this for
     * statically-known argument errors (`firstStaticArgError`); here the verdict is only knowable at
     * run time, since it depends on a `:` declaration in the space — c1 asserts the same
     * `(== 4 (+ ln 2))` as `False` above `(: ln LN)` and as the inner error below it.
     *
     * Each errorable operand is asked about in turn ([net.singularity.jetta.runtime.JettaProgram.typeCheckError]);
     * the first non-null answer IS the result of the comparison. Only reached in a value position,
     * where an `(Error …)` atom and a `Grounded<Bool>` are interchangeable to the consumer — hence
     * boxing the Bool edge so both edges into the join carry an Atom.
     */
    private fun generateComparisonWithArgErrorCheck(
        mv: LocalVariablesSorter,
        atom: Expression,
        operands: List<Expression>,
        exit: Label?
    ) {
        val join = Label()
        operands.forEach { operand ->
            generateQuote(mv, operand)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RuntimeNames.JETTA_PROGRAM,
                "typeCheckError",
                "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
                false,
            )
            val wellTyped = Label()
            mv.visitInsn(Opcodes.DUP)
            mv.visitJumpInsn(Opcodes.IFNULL, wellTyped)
            mv.visitJumpInsn(Opcodes.GOTO, join) // the Error atom on the stack IS the result
            mv.visitLabel(wellTyped)
            mv.visitInsn(Opcodes.POP) // drop the null and try the next operand
        }
        generateIf(mv, listOf(atom, Grounded(true), Grounded(false)), null, false)
        wrapValueOnStackInGrounded(GroundedType.BOOLEAN)
        mv.visitLabel(join)
        if (exit != null) mv.visitJumpInsn(Opcodes.GOTO, exit)
    }

    /** Is [atom] an application of the `change-state!` builtin (the one type-checked write)? */
    private fun isStateWrite(atom: Expression): Boolean =
        atom.resolved?.jvmMethod?.name == CHANGE_STATE

    /**
     * `(change-state! <state> <value>)` guarded by an eval-time type check of the SURFACE form:
     * a state is typed by what it was created with, so writing a value of another type is
     * `(Error <the application as written> (BadArgType 2 …))` and the write does not happen.
     * The quoted expression is what lets the error echo the original argument expressions rather
     * than the state they reduce to. Well-typed (or gradually typed) writes fall through to the
     * ordinary call, so nothing else changes.
     */
    private fun generateStateWriteWithTypeCheck(
        mv: LocalVariablesSorter,
        atom: Expression,
        name: String,
        arguments: List<Atom>,
    ) {
        generateQuote(mv, atom)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RuntimeNames.JETTA_PROGRAM,
            "typeCheckError",
            "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
            false,
        )
        val wellTyped = Label()
        val join = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, wellTyped)
        mv.visitJumpInsn(Opcodes.GOTO, join) // the Error atom on the stack IS the result
        mv.visitLabel(wellTyped)
        mv.visitInsn(Opcodes.POP) // drop the null
        generateCall(mv, name, arguments, atom.resolved)
        mv.visitLabel(join)
    }

    private fun generateInertArithmeticWithTypeCheck(mv: LocalVariablesSorter, atom: Expression) {
        generateQuote(mv, atom)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RuntimeNames.JETTA_PROGRAM,
            "typeCheckError",
            "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
            false,
        )
        val unreduced = Label()
        val join = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, unreduced)
        mv.visitJumpInsn(Opcodes.GOTO, join)
        mv.visitLabel(unreduced)
        mv.visitInsn(Opcodes.POP) // drop the null
        generateQuote(mv, atom)
        mv.visitLabel(join)
    }

    private fun generateArithmetics(
        mv: LocalVariablesSorter,
        op: Atom,
        arguments: List<Atom>,
        type: GroundedType,
        doReturn: Boolean
    ) {
        fun operation() {
            when ((op as? Special)?.value) {
                Predefined.PLUS -> when (type) {
                    GroundedType.INT -> mv.visitInsn(Opcodes.IADD)
                    GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DADD)
                    else -> TODO()
                }

                // `*` and `-` must honour the operand type just like `+`: when the inferred
                // result type is DOUBLE (e.g. `(* 3 5.5)`, `(- 8 …)` with a float operand) the
                // operands were promoted to double by castIfNeeded, so an integer IMUL/ISUB
                // would read a double off the stack → VerifyError. Dispatch on `type`.
                Predefined.TIMES -> when (type) {
                    GroundedType.INT -> mv.visitInsn(Opcodes.IMUL)
                    GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DMUL)
                    else -> TODO()
                }

                Predefined.MINUS -> when (type) {
                    GroundedType.INT -> mv.visitInsn(Opcodes.ISUB)
                    GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DSUB)
                    else -> TODO()
                }

                else -> TODO("Not implemented $op")
            }
        }
        generateAtom(mv, arguments[0], null, false)
        castIfNeeded(mv, arguments[0].type(), type)
        generateAtom(mv, arguments[1], null, false)
        castIfNeeded(mv, arguments[1].type(), type)
        operation()
        maxStack += 2
        for (i in 2..<arguments.size) {
            generateAtom(mv, arguments[i], null, false)
            castIfNeeded(mv, arguments[i].type(), type)
            operation()
            maxStack++
        }
        if (doReturn) {
            boxPrimitiveForReferenceReturn(type)
            generateReturn(mv)
        }
    }

    /**
     * A grounded-arithmetic body in RETURN position leaves a raw primitive on the stack; when
     * the enclosing function/lambda is declared to return a REFERENCE (Atom/Any), that primitive
     * must be boxed into a `Grounded` — otherwise ARETURN sees an int (VerifyError). The
     * arithmetic paths return early rather than falling through [generateAtom]'s tail, so they
     * never reach [coerceForReturn]; mirror its BOX branch here. First needed by the lift
     * `(\ ($v:Atom) (+ $v 1))` that a barrier argument like `(+ (superpose (1 2 3)) 1)` produces.
     */
    private fun boxPrimitiveForReferenceReturn(type: GroundedType) {
        (function as? FunctionDefinition)?.let { if (it.isMultivalued()) return } // List path
        val rt = function.returnType ?: return
        if (rt is GroundedType && rt.isGroundedValue()) return
        if (!type.isGroundedValue()) return
        wrapValueOnStackInGrounded(type)
    }

    companion object {
        /** Grounded arithmetic heads — the ops whose result is a number, so a non-numeric operand
         *  makes the whole form unreducible rather than a computation. */
        private val ARITHMETIC_OPS = setOf(
            Predefined.PLUS, Predefined.MINUS, Predefined.TIMES,
            Predefined.DIVIDE, Predefined.DIV, Predefined.MOD,
        )

        /** The `change-state!` builtin's runtime method name — the one type-checked state write. */
        private const val CHANGE_STATE = "change-state!"
    }
}

private fun generatePop(mv: LocalVariablesSorter) {
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        RuntimeNames.MATCHER,
        "pop",
        "()V",
        false
    )
}