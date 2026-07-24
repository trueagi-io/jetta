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
                                if (err != null) generateQuote(mv, err) else generateQuote(mv, atom)
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
                        Predefined.ANNOTATION,
                        Predefined.PATTERN,
                        Predefined.TYPE,
                        Predefined.ARROW -> {
                            // Data forms with a Special head reach codegen as inert
                            // values (see resolver counterpart). Emit as a quoted
                            // expression so the whole `(@ doc …)` / `(= … …)` /
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
                            generateIf(
                                mv,
                                listOf(atom, Grounded(true), Grounded(false)),
                                exit,
                                doReturn
                            )
                        } else TODO("func=$func")
                    }

                    is Symbol -> {
                        if (atom.resolved != null) {
                            generateCall(mv, func.name, arguments, atom.resolved)
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
                        // True free variable — create a raw Variable for match unification.
                        generateNewVariable(mv, atom.name)
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
                    if (evalCalls && sub is Expression && sub.resolved != null &&
                        sub.resolved?.isMultiValued != true
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
        arguments.forEachIndexed { index, arg ->
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
                if (arg is Expression) {
                    // An arithmetic/grounded APPLICATION reaching an Atom-typed parameter is
                    // DATA, not a computation — the ATOM meta-type suppresses reduction (hyperon).
                    // Quote it inert rather than evaluating it, even though its resolved type is a
                    // grounded value. This is what lets `(get-type (+ 5 "4"))` type-check the
                    // ill-typed expression (→ `()`) instead of evaluating `5 + "4"` and crashing.
                    // (A reducible user-function application — e.g. `(get-type (drop …))` — is not
                    // grounded-value-typed and so still reduces here; suppressing THAT needs a
                    // per-builtin meta-type distinction, out of scope for get-type's D0/D1.)
                    generateQuote(mv, arg)
                } else {
                    // A grounded VALUE LITERAL (Int/Double/String/…) passed to an Atom-typed
                    // parameter must be wrapped in a Grounded: a bare box (Integer) is not an Atom
                    // subtype, so the verifier rejects `Integer` where `Atom` is expected. Routine
                    // in higher-order code — `(apply inc 5)` where `apply`'s param is Atom. Load
                    // the raw value, box it, wrap in Grounded (which IS an Atom).
                    generateGroundedValueArg(mv, arg, argType)
                }
            } else {
                generateAtom(mv, arg, null, false, jvmSymbol.doesParameterHaveAnyType(index))
            }
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
     * Expression-headed application `((head…) args…)` — a curried call such as
     * `(((curry +) 2) 3)`, whose head is itself an Expression the static codegen can't lower
     * to a call. Mirrors [generateDispatchCall] but pushes the head as a quoted Atom (it is an
     * Expression, not a variable slot): the head is quoted inert with `evalCalls = true` so any
     * reducible scalar call nested in it keeps its value-position semantics, args are packed and
     * boxed exactly as for variable-head dispatch, and the whole `(head args…)` is dispatched
     * through the [net.singularity.jetta.runtime.functions.JettaCallSite] bootstrap. At runtime
     * the reducer rewrites it via space `(= …)` rules (+ the registry step) or, if nothing
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
            GroundedType.ATOM,
            GroundedType.LIST,
            GroundedType.STRING,
            GroundedType.EXPRESSION,
            GroundedType.SPACE,
            GroundedType.ANY,
            GroundedType.NOTHING,
            is SeqType,
            is ArrowType -> mv.visitInsn(Opcodes.ARETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
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
        left.type == GroundedType.DOUBLE || right.type == GroundedType.DOUBLE -> GroundedType.DOUBLE
        left.type == GroundedType.INT || right.type == GroundedType.INT -> GroundedType.INT
        left.type == GroundedType.BOOLEAN || right.type == GroundedType.BOOLEAN -> GroundedType.BOOLEAN
        else -> null
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
                                generateAtom(mv, left, null, false)
                                generateAtom(mv, right!!, null, false)
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
                            generateAtom(mv, left, null, false)
                            generateAtom(mv, right!!, null, false)
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
            unwrapGroundedToPrimitive(mv, requiredType)
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
        if (doReturn) generateReturn(mv)
    }

    private fun generateDiv(
        mv: LocalVariablesSorter,
        arguments: List<Atom>,
        doReturn: Boolean
    ) {
        generateAtom(mv, arguments[0], null, false)
        generateAtom(mv, arguments[1], null, false)
        mv.visitInsn(Opcodes.IDIV)
        if (doReturn) generateReturn(mv)
    }

    private fun generateMod(
        mv: LocalVariablesSorter,
        arguments: List<Atom>,
        doReturn: Boolean
    ) {
        generateAtom(mv, arguments[0], null, false)
        generateAtom(mv, arguments[1], null, false)
        mv.visitInsn(Opcodes.IREM)
        if (doReturn) generateReturn(mv)
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
        if (doReturn) generateReturn(mv)
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