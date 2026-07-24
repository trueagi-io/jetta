package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotInferTypeMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.IncompatibleTypesMessage
import net.singularity.jetta.compiler.frontend.rewrite.CanonicalFormRewriter
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LowerAssertExpressionsRewriter
import net.singularity.jetta.compiler.frontend.rewrite.MarkMultivaluedFunctionsRewriter
import net.singularity.jetta.compiler.frontend.rewrite.QuotePureSymbolicBodiesRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ReplaceNodesRewriter
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.compiler.logger.Logger
import kotlin.collections.component1
import kotlin.collections.component2

class Context private constructor(
    private val messageCollector: MessageCollector,
    private val mapImpl: JvmMethod?,
    private val flatMapImpl: JvmMethod?,
    private val space: Space,
    // DURABLE symbol tables — the resolved output of AOT, shared (copy-on-write) by
    // [fork]. Default fresh for a top-level compile.
    val definedFunctions: MutableMap<String, SymbolDef>,
    private val resolvedFunctions: MutableMap<String, SymbolDef>,
    private val functions: MutableMap<String, FunctionDefinition>,
    private val systemFunctions: MutableMap<String, ResolvedSymbol>,
) {
    constructor(
        messageCollector: MessageCollector,
        mapImpl: JvmMethod? = null,
        flatMapImpl: JvmMethod? = null,
        space: Space,
    ) : this(
        messageCollector, mapImpl, flatMapImpl, space,
        mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(),
    )

    private val logger = Logger.getLogger(Context::class.java)

    /** System function whose argument is a data tuple to enumerate (operator-as-data boundary). */
    private val SUPERPOSE = "superpose"
    // TRANSIENT working state — what "comes out of a resolve/match pass". Always fresh
    // (never shared by [fork]): each eval gets its own.
    private val unresolvedElements = mutableMapOf<Int, AtomWithTypeInfo>()
    private val nodesToReplace = mutableMapOf<Atom, Atom>()
    private var main: FunctionDefinition? = null
    private var typeInferenceDone = false
    private val mapSymbol = mapImpl?.let { ResolvedSymbol(it, null, false) }
    private val flatMapSymbol = flatMapImpl?.let { ResolvedSymbol(it, null, false) }
    private val matchPatterns = mutableSetOf<Expression>()

    /**
     * Fork this resolved environment for JIT-eval (same-JVM). The durable symbol
     * tables are shared copy-on-write via [OverlayMap] — the fork's synthetic
     * `__evalN` lands in its own local layer and never pollutes the AOT tables, and
     * concurrent/repeated evals stay independent. Transient working state and the
     * space start fresh: [forkSpace] is a throwaway because FunctionRewriter writes the
     * synthetic `(= (__evalN) …)` rule back into it (it must never be the caller's
     * space), and [forkMessageCollector] isolates eval diagnostics.
     *
     * Because `resolvedFunctions` carries each program function's owner class, eval'd
     * code that calls a user function resolves to an `INVOKESTATIC` against the
     * already-compiled class — it LINKS, it does not recompile the rule body.
     */
    fun fork(forkSpace: Space, forkMessageCollector: MessageCollector): Context = Context(
        forkMessageCollector,
        mapImpl,
        flatMapImpl,
        forkSpace,
        OverlayMap(definedFunctions),
        OverlayMap(resolvedFunctions),
        OverlayMap(functions),
        OverlayMap(systemFunctions),
    )

    fun getSpace(): Space {
        if (matchPatterns.isNotEmpty()) {
            space.mkIndex(matchPatterns.distinct())
            matchPatterns.clear()
        }
        return space
    }

    private fun cleanUp() {
        unresolvedElements.clear()
        typeInferenceDone = false
    }

    fun clearMessages() {
        messageCollector.clear()
    }

    data class SymbolDef(val owner: String, val func: FunctionDefinition)

    /**
     * One entry of the cross-JVM linker table (the P1 `.jctx` artifact): a user
     * function's MeTTa name plus everything a runtime `findStatic` needs to LINK
     * against its already-compiled JVM method — [owner] (the internal class name),
     * [descriptor] (its JVM signature), and whether it returns a non-determinism bag.
     */
    data class LinkerSymbol(
        val name: String,
        val owner: String,
        val descriptor: String,
        val multivalued: Boolean,
    )

    /**
     * The linker table for variable-head dispatch in a COMPILED binary. Serialized to
     * `<program>.jctx` at compile time and loaded by `JettaProgram.init`; `JettaCallSite`
     * uses it to resolve `($f x)` when `$f` names a user function, so an AOT run links
     * against the compiled method instead of leaving the application inert. The resolved
     * table is the AOT-computed linker symbol table — recomputing it at runtime is exactly
     * the redundant work the partial-eval architecture exists to kill.
     *
     * Skips synthetic entries (`__eval*`, `__main*`) and `main`, and any function that never
     * got an arrow type (no JVM descriptor to link against).
     */
    fun linkerTable(): List<LinkerSymbol> =
        resolvedFunctions.entries
            .filter { (name, def) ->
                !name.startsWith("__") && name != "main" && def.func.arrowType != null
            }
            .map { (name, def) ->
                LinkerSymbol(name, def.owner, def.func.getJvmDescriptor(), def.func.isMultivalued())
            }

    private data class AtomWithTypeInfo(val atom: Atom, val info: Scope)

    private data class Scope(
        val functionDefinition: FunctionLike,
        val parent: Scope? = null
    ) {
        val isProvided = functionDefinition.arrowType != null
        val data = mutableMapOf<String, Atom?>()

        init {
            val arrowType = functionDefinition.arrowType
            if (arrowType != null) {
                data.putAll(functionDefinition.params.map { it.name }
                    .zip(arrowType.types.dropLast(1)).toMap())
            } else {
                functionDefinition.params.forEach {
                    data[it.name] = null
                }
            }
        }

        fun join(child: FunctionLike): Scope = Scope(child, parent = this)

        operator fun get(variableName: String): Pair<FunctionLike, Atom?>? {
            if (data.containsKey(variableName)) return functionDefinition to data[variableName]
            if (parent == null) return null
            return parent[variableName]
        }
    }

    private fun SymbolDef.toJvm() = JvmMethod(
        owner = owner,
        name = func.name,
        descriptor = func.getJvmDescriptor(),
        signature = func.getSignature()
    )

    private fun addResolvedFunction(owner: String, func: FunctionDefinition) {
        logger.debug { "Registered function: ${func.name} :: ${func.arrowType ?: "untyped"} (owner=$owner)" }
        resolvedFunctions[func.name] = SymbolDef(owner, func)
        main?.let {
            val lastCall = when (it.body) {
                is Expression -> (it.body as Expression).atoms.last()
                else -> it.body
            }
            if (lastCall is Expression &&
                (lastCall.atoms[0] as? Symbol)?.name == func.name
            ) {
                it.arrowType = if (func.isMultivalued()) {
                    it.annotations.add(PredefinedAtoms.MULTIVALUED)
                    ArrowType(listOf(SeqType(func.returnType!!)))
                } else {
                    ArrowType(listOf(func.returnType!!))
                }
                lastCall.resolved = resolve(func.name)
            }
        }
    }

    fun addExternalFunctions(source: ParsedSource) {
        val external =
            source.code.filter { it is FunctionDefinition && it.annotations.contains(PredefinedAtoms.EXPORT) }
                .map { it as FunctionDefinition }
        external.forEach {
            resolvedFunctions[it.name] = SymbolDef(source.getJvmClassName(), it)
        }
    }

    fun addSystemFunction(resolvedSymbol: ResolvedSymbol) {
        systemFunctions[resolvedSymbol.jvmMethod.name] = resolvedSymbol
    }

    private fun inferType(atom: Atom, scope: Scope, suggestedType: Atom? = null) {
        logger.trace { "Infer type for atom $atom" }
        when (atom) {
            is Expression -> inferTypeForExpression(atom, scope)
            is Variable -> {
                scope.data[atom.name]?.let {
                    atom.type = it
                }
            }

            is Grounded<*> -> {
                when (atom.value) {
                    is Int -> GroundedType.INT
                    is Double -> GroundedType.DOUBLE
                    else -> TODO("${atom.value}")
                }
            }

            is Symbol -> {
                // Plain symbol constants (e.g., T, F) — type is Atom
                atom.type = atom.type ?: GroundedType.ATOM
            }

            else -> TODO("atom=$atom")
        }
    }

    private fun inferTypeForExpression(expression: Expression, scope: Scope) {
        logger.trace { "Infer type for expression: $expression" }
        if (expression.atoms.isEmpty()) {
            expression.type = GroundedType.ATOM
            return
        }
        when (val atom = expression.atoms[0]) {
            is Symbol -> {
                val functionName = atom.name
                val resolvedSymbol = resolve(functionName)
                if (resolvedSymbol != null) {
                    expression.arguments().zip(resolvedSymbol.paramTypes())
                        .forEach { (arg, type) ->
                            when (arg) {
                                is Variable -> {
                                    // TODO: check previous value
                                    scope.data[arg.name] = type
                                }

                                is Grounded<*> -> {
                                    // A literal whose type doesn't match the param type
                                    // (an Int reaching a still-Atom param mid-inference,
                                    // or a genuine mismatch) is left for the authoritative
                                    // resolve pass to box or report via
                                    // IncompatibleTypesMessage — this heuristic pass must
                                    // not crash on it.
                                }

                                is Expression -> inferTypeForExpression(arg, scope)

                                is Lambda -> {
                                    // FIXME: do nothing for now
                                }

                                is Symbol -> {
                                    // FIXME: do nothing for now
                                }

                                else -> TODO("it=$arg")
                            }
                        }
                    expression.type = resolvedSymbol.arrowType().types.last()
                } else {
                    if (definedFunctions[functionName] == null) {
                        messageCollector.add(CannotResolveSymbolMessage(functionName, expression.position))
                        throw UndefinedSymbolException(functionName)
                    }
                }
            }

            is Special -> when (atom.value) {
                Predefined.IF -> {
                    val (_, cond, thenBranch, elseBranch) = expression.atoms
                    inferType(cond, scope)
                    inferType(thenBranch, scope)
                    inferType(elseBranch, scope, thenBranch.type)
                    inferType(thenBranch, scope, elseBranch.type)
                    // TODO: check types of then and else
                    // TODO: for simplicity now (should be unified)
                    expression.type = thenBranch.type ?: elseBranch.type
                }

                Predefined.COND_EQ,
                Predefined.COND_NEQ,
                Predefined.COND_LT,
                Predefined.COND_GT,
                Predefined.COND_LE,
                Predefined.COND_GE -> {
                    val (_, lhs, rhs) = expression.atoms
                    inferType(lhs, scope)
                    inferType(rhs, scope)
                    // Propagate a known side's type to an unknown side. This is how
                    // `(== $v 5)` infers `$v: Int` — the standard symmetry trick.
                    // Order-comparison operators (<, >, ≤, ≥) only need a Number-ish
                    // type but the side-propagation logic is the same.
                    if (lhs.type != null && rhs.type == null) {
                        rhs.type = lhs.type
                        if (rhs is Variable) scope.data[rhs.name] = lhs.type!!
                    }
                    if (lhs.type == null && rhs.type != null) {
                        lhs.type = rhs.type
                        if (lhs is Variable) scope.data[lhs.name] = rhs.type!!
                    }
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.TIMES, Predefined.MINUS, Predefined.PLUS,
                Predefined.DIVIDE, Predefined.DIV, Predefined.MOD -> {
                    val operands = expression.arguments()
                    operands.forEach { inferType(it, scope) }
                    inferArithmeticOperandTypes(atom.value, operands, scope)
                }

                Predefined.RUN_SEQ, Predefined.SEQ -> {
                    expression.arguments().forEach { inferType(it, scope) }
                    expression.type = expression.atoms.lastOrNull()?.type ?: GroundedType.ATOM
                }

                Predefined.MAP_, Predefined.FLAT_MAP_ -> { /* skip it */
                }

                Predefined.AND, Predefined.OR, Predefined.XOR, Predefined.NOT -> {
                    expression.arguments().forEach {
                        inferType(it, scope)
                    }
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.QUOTE -> {
                    expression.type = GroundedType.ATOM
                }

                else -> TODO("atom=$atom")
            }

            is Lambda -> {
                // Inline lambda application `((\ params body) args)` — common
                // shape after `let` desugaring. Type is the lambda's declared
                // return type when known, otherwise ATOM. Recurse into args
                // so their types are inferred too.
                expression.atoms.drop(1).forEach { inferType(it, scope) }
                expression.type = atom.returnType ?: GroundedType.ATOM
            }

            is Expression -> {
                expression.atoms.forEach { inferType(it, scope) }
                expression.type = GroundedType.ATOM
            }

            is Variable -> {
                // Variable-headed application — `($f x y)` in higher-order code.
                // The variable's own ArrowType (if any) determines the result type;
                // otherwise treat the call as inert/ATOM. JIT-eval dispatcher
                // ([net.singularity.jetta.runtime.functions.JettaCallSite]) handles
                // it at runtime.
                expression.atoms.drop(1).forEach { inferType(it, scope) }
                val arrow = atom.type as? ArrowType
                expression.type = arrow?.types?.lastOrNull() ?: GroundedType.ATOM
            }

            else -> TODO("atom=$atom")
        }
    }

    fun resolve(source: ParsedSource): ParsedSource {
        cleanUp()
        return resolveRecursively(source)
    }


    fun typeInferenceLoop(source: ParsedSource) {
        val postponedFunctions = mutableMapOf<String, Scope>()
        val owner = source.getJvmClassName()
        var iteration = 0
        try {
            do {
                iteration++
                val numElements = unresolvedElements.size
                logger.debug { "Type inference iteration $iteration: $numElements unresolved elements" }
                unresolvedElements.forEach { (id, data) ->
                    inferType(data.atom, data.info)
                    logger.debug { "  [$id] atom=${data.atom}, scope=${data.info}" }
                }
                unresolvedElements
                unresolvedElements
                    .toList()
                    .map { (_, data) -> data.info }
                    .toSet()
                    .forEach {
                        if (it.functionDefinition is FunctionDefinition) {
                            if (!updateFunction(owner, it)) {
                                postponedFunctions[it.functionDefinition.name] = it
                            }
                        }
                    }
                val resolved = mutableListOf<Pair<Int, Atom>>()
                HashMap(unresolvedElements).forEach { (id, data) ->
                    resolveAtom(data.atom, data.info)
                    if (data.atom.type != null) {
                        logger.debug { "  Resolved [$id] ${data.atom} -> type=${data.atom.type}" }
                        resolved.add(id to data.atom)
                    }
                }
                resolved.forEach { unresolvedElements.remove(it.first) }
                logger.debug { "  Resolved ${resolved.size} elements, ${unresolvedElements.size} remaining" }
                val updated = mutableListOf<String>()
                postponedFunctions.forEach { (name, info) ->
                    if (updateFunction(owner, info)) {
                        logger.debug { "  Updated postponed function: $name :: ${info.functionDefinition.arrowType}" }
                        updated.add(name)
                    }
                }
                updated.forEach { postponedFunctions.remove(it) }
            } while (unresolvedElements.isNotEmpty() && unresolvedElements.size != numElements)
        } catch (_: UndefinedSymbolException) {
        }
        if (unresolvedElements.isNotEmpty()) {
            unresolvedElements.forEach { (_, data) ->
                if (data.info.functionDefinition is FunctionDefinition) {
                    messageCollector.add(
                        CannotInferTypeMessage(
                            data.atom,
                            data.info.functionDefinition
                        )
                    )
                }
            }
        }
        typeInferenceDone = true
    }

    private fun refineFunctionArrowTypes(): Boolean {
        var changed = false
        resolvedFunctions.toList().forEach { (_, def) ->
            val arrowType = def.func.arrowType ?: return@forEach
            val paramTypes = arrowType.types.dropLast(1)
            val currentReturn = arrowType.types.last()

            // 1. Lift ATOM parameters to a concrete type collected from the body
            //    (e.g. `$n` typed Int by a comparison or an arithmetic operand).
            val refinedParams = if (paramTypes.any { it == GroundedType.ATOM }) {
                val inferredParamTypes = mutableMapOf<String, Atom>()
                collectVariableTypes(def.func.body, inferredParamTypes)
                def.func.params.mapIndexed { index, param ->
                    if (paramTypes[index] == GroundedType.ATOM) {
                        val inferred = inferredParamTypes[param.name]
                        if (inferred != null && inferred != GroundedType.ATOM) {
                            param.type = inferred
                            inferred
                        } else {
                            paramTypes[index]
                        }
                    } else {
                        paramTypes[index]
                    }
                }
            } else {
                paramTypes
            }

            // 2. Lift an ATOM/ANY return type to the resolved body type. An untyped
            //    `fib` resolves its body (`if`) to Int, but the arrow's return was
            //    frozen at Any on the first pass (it is only ever *set*, never
            //    refined). Codegen would then emit `ireturn` of a primitive against
            //    an Object descriptor → VerifyError. Only widen the dynamic top
            //    (Atom/Any) toward a concrete grounded type, never a SeqType (the
            //    multivalued List contract owns that), so this is monotonic and the
            //    enclosing fixpoint converges.
            val bodyType = inferReturnFromBody(def.func)
            val refinedReturn = if ((currentReturn == GroundedType.ATOM || currentReturn == GroundedType.ANY) &&
                bodyType != null && bodyType != GroundedType.ATOM && bodyType != GroundedType.ANY &&
                bodyType !is SeqType
            ) {
                bodyType
            } else {
                currentReturn
            }

            val refinedTypes = refinedParams + refinedReturn
            if (refinedTypes != arrowType.types) {
                def.func.arrowType = ArrowType(refinedTypes)
                addResolvedFunction(def.owner, def.func)
                changed = true
            }
        }
        return changed
    }

    /**
     * Arithmetic operands are numeric, so pin any still-untyped variable operand
     * to a numeric type. This is the mechanism that lets an untyped `(+ $x $x)`
     * infer `$x: Int` — the same symmetry the comparison operators already use to
     * type `(== $v 5)`. `+ - *` are Int unless a sibling operand is Double (float
     * contagion); `div`/`mod` are integer-only. Writing both the operand's own
     * `.type` and `scope.data` lets sibling occurrences resolve immediately and
     * lets [refineFunctionArrowTypes] lift the type onto the function's parameter.
     * Only genuinely untyped (null) operands are touched, never a concrete type.
     */
    private fun inferArithmeticOperandTypes(op: String, operands: List<Atom>, scope: Scope) {
        val numeric = when {
            op == Predefined.DIV || op == Predefined.MOD -> GroundedType.INT
            operands.any { it.type == GroundedType.DOUBLE } -> GroundedType.DOUBLE
            else -> GroundedType.INT
        }
        operands.forEach {
            if (it is Variable && it.type == null) {
                it.type = numeric
                scope.data[it.name] = numeric
            }
        }
    }

    /**
     * D2.2 (tier i): a grounded arithmetic op applied to a concrete non-numeric operand is an
     * eval-time type error (hyperon yields `(Error <expr> (BadArgType <pos> Number <actual>))`).
     * When an operand's type is a known non-numeric grounded scalar (today `String`), stamp the
     * node `ATOM` and return true — this routes codegen away from the inline `IADD`/`DADD` fast
     * path onto its ATOM branch, where `FunctionGenerator` emits the `(Error …)` atom for THIS
     * node instance (identity-precise — a structurally-identical `(+ …)` inside quoted expected
     * data is emitted verbatim, not turned into an error). Numeric operands (Int/Long/Double) and
     * gradual ones (`Atom`/`%Undefined%`/a bound `Variable`) are left on the normal path.
     */
    private fun hasGroundedArithmeticTypeError(operands: List<Atom>): Boolean =
        operands.any { it.type == GroundedType.STRING }

    /**
     * D2.4 (increment 1): `==`/`!=` are grounded `(-> $t $t Bool)`, so both operands must
     * share a type. Detect a statically-known numeric-vs-`String` mismatch — the one
     * concretely-decidable grounded case — which hyperon reports as
     * `(Error <expr> (BadArgType <pos> Number String))`. Scoped narrowly on purpose: two
     * numbers, two Strings, or any operand whose type is gradual (`Atom`/`Any`/`%Undefined%`/a
     * bound `Variable`) or a reference (Symbol, structural expression) is NOT flagged here —
     * those stay on the Bool/structural path. Custom-typed operand mismatches are increment 2.
     */
    private fun hasGroundedComparisonTypeError(lhs: Atom, rhs: Atom): Boolean {
        fun isNumeric(t: Atom?) =
            t == GroundedType.INT || t == GroundedType.LONG || t == GroundedType.DOUBLE
        val lt = lhs.type
        val rt = rhs.type
        return (lt == GroundedType.STRING && isNumeric(rt)) ||
            (rt == GroundedType.STRING && isNumeric(lt))
    }

    /**
     * Infer a function's return type from the result-position types in its body,
     * unifying the branches of an `if`/Match. A *self-recursive* call whose type is
     * still the unrefined dynamic top (Atom/Any/null) contributes no information —
     * this is a least-fixpoint from bottom, so a tail-recursive body such as gcd's
     * `(if (== $b 0) $a (gcd $b (mod $a $b)))` yields Int (from the base case)
     * rather than collapsing to Any when unified with the recursive call's
     * provisional Atom. A *genuinely* Atom leaf (a Symbol, or a call to some other
     * Atom-returning function) is kept, so heterogeneous functions still type as
     * Atom. Returns the plain body type when no branch is informative.
     */
    private fun inferReturnFromBody(func: FunctionDefinition): Atom? {
        val types = mutableListOf<Atom>()
        collectResultTypes(func.body, func.name, types)
        if (types.isEmpty()) return func.body.type
        return types.reduce { acc, t -> unifyType(acc, t) }
    }

    private fun collectResultTypes(atom: Atom, funcName: String, acc: MutableList<Atom>) {
        when (atom) {
            is Expression -> {
                val head = atom.atoms.firstOrNull()
                if ((head as? Special)?.value == Predefined.IF && atom.atoms.size == 4) {
                    collectResultTypes(atom.atoms[2], funcName, acc)
                    collectResultTypes(atom.atoms[3], funcName, acc)
                    return
                }
                val type = atom.type
                val isSelfRecursive = (head as? Symbol)?.name == funcName
                val isUnrefinedTop =
                    type == null || type == GroundedType.ATOM || type == GroundedType.ANY
                if (isSelfRecursive && isUnrefinedTop) return
                type?.let { acc.add(it) }
            }

            is Match -> atom.branches.forEach { collectResultTypes(it.body, funcName, acc) }
            else -> atom.type?.let { acc.add(it) }
        }
    }

    /**
     * A comparison forces both operands to a common type. When one side is a
     * concrete grounded type and the other is a still-untyped variable, pin the
     * variable — this is how `(== $m 0)` infers `$m: Int`, mirroring the symmetry
     * the [inferType] pass already performs and complementing the arithmetic
     * operand inference so a parameter used only in comparisons still gets a
     * concrete type. Only a genuinely untyped (null) variable is touched — an Atom
     * operand (e.g. a symbol or a destructured pattern var) is left alone so the
     * comparison-over-Atom codegen path is preserved, and a symbolic comparison
     * such as `(== $x Foo)` (concrete side is Atom) propagates nothing.
     */
    private fun propagateComparisonOperandType(lhs: Atom, rhs: Atom, scope: Scope) {
        fun pin(variable: Atom, other: Atom) {
            val t = other.type ?: return
            if (t == GroundedType.ATOM || t == GroundedType.ANY) return
            if (variable is Variable && variable.type == null) {
                variable.type = t
                scope.data[variable.name] = t
            }
        }
        pin(lhs, rhs)
        pin(rhs, lhs)
    }

    private fun collectVariableTypes(atom: Atom, result: MutableMap<String, Atom>) {
        when (atom) {
            is Variable -> {
                if (atom.type != null && atom.type != GroundedType.ATOM) {
                    result[atom.name] = atom.type!!
                }
            }

            is Expression -> atom.atoms.forEach { collectVariableTypes(it, result) }
            is Match -> atom.branches.forEach { branch ->
                branch.cond?.let { collectVariableTypes(it, result) }
                collectVariableTypes(branch.body, result)
            }

            is Lambda -> {
                atom.params.forEach { collectVariableTypes(it, result) }
                collectVariableTypes(atom.body, result)
            }

            else -> {}
        }
    }

    /**
     * Validate executable top-level runtime entrypoints after resolution.
     *
     * This pass is intentionally conservative:
     * - it checks only generated executable entrypoints (`__main` and `__main_*`)
     * - it reports unresolved symbol-head expressions only when they are used in
     *   executable position
     * - it does not attempt to reject symbolic/data-heavy user-defined functions yet
     *
     * The goal is to improve diagnostics for broken top-level `!expr` programs
     * without over-constraining MeTTa-style symbolic code.
     */
    private fun validateExecutableCalls(source: ParsedSource) {
        source.code.forEach { atom ->
            val function = atom as? FunctionDefinition ?: return@forEach
            if (!isExecutableEntryPoint(function.name)) return@forEach
            validateExecutableAtom(function.body)
        }
    }

    private fun isExecutableEntryPoint(functionName: String): Boolean =
        functionName == FunctionRewriter.MAIN || functionName.startsWith("${FunctionRewriter.MAIN}_")

    private fun validateExecutableAtom(atom: Atom) {
        when (atom) {
            is Expression -> validateExecutableExpression(atom)
            is Lambda -> validateExecutableAtom(atom.body)
            is Match -> atom.branches.forEach { branch ->
                branch.cond?.let { validateExecutableAtom(it) }
                validateExecutableAtom(branch.body)
            }
            else -> { /* literals, variables, symbols in value position are fine */ }
        }
    }

    private fun validateExecutableExpression(expression: Expression) {
        if (expression.atoms.isEmpty()) return

        when (val head = expression.atoms[0]) {
            is Special -> validateSpecialExpression(expression, head)
            is Variable -> expression.arguments().forEach { validateExecutableAtom(it) }
            is Lambda -> {
                validateExecutableAtom(head.body)
                expression.arguments().forEach { validateExecutableAtom(it) }
            }
            is Symbol -> {
                val resolved = expression.resolved
                if (resolved != null) {
                    validateResolvedCallArguments(expression, resolved)
                    return
                }

                if (expression.type == GroundedType.ATOM) {
                    return
                }

                messageCollector.add(CannotResolveSymbolMessage(head.name, head.position ?: expression.position))
                expression.arguments().forEach { validateExecutableAtom(it) }
            }
            else -> {
                expression.arguments().forEach { validateExecutableAtom(it) }
            }
        }
    }

    private fun validateResolvedCallArguments(expression: Expression, resolved: ResolvedSymbol) {
        val paramTypes = resolved.arrowType()?.types?.dropLast(1).orEmpty()
        expression.arguments().forEachIndexed { index, arg ->
            val paramType = paramTypes.getOrNull(index)
            if (paramType == GroundedType.ATOM) return@forEachIndexed
            validateExecutableAtom(arg)
        }
    }

    private fun validateSpecialExpression(expression: Expression, special: Special) {
        when (special.value) {
            Predefined.QUOTE -> {
                // Quoted content is data, not executable code.
            }

            Predefined.IF,
            Predefined.COND_EQ,
            Predefined.COND_NEQ,
            Predefined.COND_LT,
            Predefined.COND_GT,
            Predefined.COND_LE,
            Predefined.COND_GE,
            Predefined.TIMES,
            Predefined.MINUS,
            Predefined.PLUS,
            Predefined.DIVIDE,
            Predefined.DIV,
            Predefined.MOD,
            Predefined.RUN_SEQ,
            Predefined.NOT,
            Predefined.AND,
            Predefined.OR,
            Predefined.XOR,
            Predefined.SEQ,
            Predefined.MAP_,
            Predefined.FLAT_MAP_ -> {
                expression.arguments().forEach { validateExecutableAtom(it) }
            }

            else -> {
                expression.arguments().forEach { validateExecutableAtom(it) }
            }
        }
    }

    /**
     * Rewrites generated `__main` into a codegen-friendly form by extracting each
     * run step from the top-level `run-seq` body into a separate synthetic helper
     * function and replacing the original step with a call to that helper.
     *
     * This transformation is purely operational:
     * - it does not decide which expressions belong to space
     * - it does not change top-level MeTTa semantics
     * - it preserves run order and final result semantics
     *
     * After top-level semantics were split in the rewriter, `__main` contains only
     * executable top-level `!expr` forms. This method exists only to simplify later
     * frontend/backend stages that work better when each main step is represented as
     * an ordinary function call.
     */
    private fun normalizeMainForCodegen(source: ParsedSource): ParsedSource {
        val code = mutableListOf<Atom>()
        source.code.forEach {
            if (it is FunctionDefinition && it.name == FunctionRewriter.MAIN) {
                val atoms = (it.body as Expression).atoms
                if (atoms.size != 1) {
                    var count = 0

                    val calls = atoms.drop(1).map { atom ->
                        val fnName = "__main_${count++}"
                        val def = FunctionDefinition(
                            fnName,
                            listOf(),
                            ArrowType(atom.type ?: GroundedType.ATOM),
                            atom,
                            position = atom.position
                        )
                        resolveFunctionDefinition(source.getJvmClassName(), def)
                        code.add(def)
                        Expression(Symbol(fnName))
                    }
                    val def = FunctionDefinition(
                        FunctionRewriter.MAIN,
                        listOf(),
                        null,
                        Expression(
                            listOf(Special(Predefined.RUN_SEQ)) + calls,
                            position = calls.getOrNull(0)?.position
                        ),
                        position = calls.getOrNull(0)?.position
                    )
                    code.add(def)
                }
            } else {
                code.add(it)
            }
        }

        return ParsedSource(source.filename, code)
    }


    fun resolveRecursively(source: ParsedSource): ParsedSource {
        main = source.code.find { it is FunctionDefinition && it.name == FunctionRewriter.MAIN } as? FunctionDefinition
        resolveSource(source)
        typeInferenceLoop(source)
        // Refine ATOM params and ATOM/ANY return types from resolved body types,
        // re-resolving between rounds so a refined return type propagates to the
        // (recursive) call sites that consume it — which in turn can make another
        // body/param type concrete. Refinement only widens the dynamic top toward a
        // concrete type, so it is monotonic and converges; the bound is a safety net.
        var round = 0
        do {
            val changed = refineFunctionArrowTypes()
            resolveSource(source)
            round++
        } while (changed && round < 16)
        val normalizedMain = normalizeMainForCodegen(source)
        val postprocessed = applyPostResolveRewriters(normalizedMain)
        messageCollector.clear()
        registerUntypedFunctions(postprocessed)
        resolveSource(postprocessed)
        validateExecutableCalls(postprocessed)
        defaultUntypedToAtom(postprocessed)
        return postprocessed
    }

    /**
     * After type inference has converged and the post-resolve rewriters have run, any
     * user function still lacking an arrowType has a body whose type could not be
     * inferred: a bare-variable identity body (`(= (I $x) $x)`) or a body headed by an
     * unresolved symbol (`(= (hide $x) (empty))`). In MeTTa these are ordinary rewrite
     * rules — `(I 5)` and `(hide …)` must still reduce — so default their signature to
     * Atom and register them. Without this the final [resolveSource] below leaves such
     * calls inert: `resolve(name)` returns null, the call gets no `.resolved`, and
     * codegen quotes it as data (so an argument's applicative side effects are lost).
     * [defaultUntypedToAtom] fabricates the same arrowType for the method body later, but
     * it runs *after* call-site resolution — too late to turn the call into an
     * INVOKESTATIC. Only `=`-rule heads reach here as FunctionDefinitions (plain data
     * facts are space atoms, never functions), so genuine data constructors are untouched.
     */
    private fun registerUntypedFunctions(source: ParsedSource) {
        val owner = source.getJvmClassName()
        source.code.forEach { fd ->
            if (fd is FunctionDefinition && fd.name != FunctionRewriter.MAIN && fd.arrowType == null) {
                val paramTypes = fd.params.map { it.type ?: GroundedType.ATOM }
                val returnType = fd.body.type ?: GroundedType.ATOM
                fd.arrowType = ArrowType(paramTypes + returnType)
                addResolvedFunction(owner, fd)
            }
        }
    }

    /**
     * Walk the entire IR tree and default any remaining null types to Atom.
     * In MeTTa, untyped values are dynamically typed — Atom on the JVM.
     * This guarantees the backend never sees null types.
     */
    private fun defaultUntypedToAtom(source: ParsedSource) {
        source.code.forEach { defaultUntypedToAtom(it) }
    }

    private fun defaultUntypedToAtom(atom: Atom) {
        when (atom) {
            is FunctionDefinition -> {
                atom.params.forEach { it.type = it.type ?: GroundedType.ATOM }
                defaultUntypedToAtom(atom.body)
                // Symmetric with the Lambda branch below: when the earlier
                // inference passes left arrowType null (e.g. identity-style
                // `(= (I $x) $x)` has no constraint that pins a non-Atom
                // type), aggregate it from the now-defaulted params and body.
                // Without this the backend hits `arrowType!!` in
                // getJvmDescriptor and NPEs.
                if (atom.arrowType == null) {
                    val paramTypes = atom.params.map { it.type!! }
                    val returnType = atom.body.type ?: GroundedType.ATOM
                    atom.arrowType = ArrowType(paramTypes + returnType)
                }
            }

            is Lambda -> {
                atom.params.forEach { it.type = it.type ?: GroundedType.ATOM }
                if (atom.arrowType == null) {
                    val paramTypes = atom.params.map { it.type!! }
                    val returnType = atom.body.type ?: GroundedType.ATOM
                    atom.arrowType = ArrowType(paramTypes + returnType)
                }
                atom.type = atom.type ?: atom.arrowType
                defaultUntypedToAtom(atom.body)
            }

            is Expression -> {
                atom.atoms.forEach { defaultUntypedToAtom(it) }
                atom.type = atom.type ?: GroundedType.ATOM
            }

            is Variable -> {
                atom.type = atom.type ?: GroundedType.ATOM
            }

            is Match -> {
                atom.branches.forEach { branch ->
                    branch.cond?.let { defaultUntypedToAtom(it) }
                    defaultUntypedToAtom(branch.body)
                }
            }

            is Symbol -> {
                atom.type = atom.type ?: GroundedType.ATOM
            }

            else -> { /* Grounded literals, etc. — already typed */
            }
        }
    }

    private fun updateFunction(owner: String, scope: Scope): Boolean {
        logger.debug { "Update: $scope" }
        if (scope.functionDefinition.arrowType != null) return true

        var isCompleted = true
        val types = mutableListOf<Atom>()
        scope.functionDefinition.params.forEach {
            val type = scope.data[it.name]
            if (type != null) {
                types.add(type)
            } else {
                isCompleted = false
            }
        }
        if (isCompleted) {
            val body = scope.functionDefinition.body
            resolveAtom(body, scope)
            val bodyType = body.type ?: inferBodyTypeFromMatch(body)
            if (bodyType != null) {
                body.type = bodyType
                types.add(bodyType)
                scope.functionDefinition.arrowType = ArrowType(types)
            }
            addResolvedFunction(owner, scope.functionDefinition as FunctionDefinition)
        } else {
            // Body type is known but some params couldn't be inferred
            // (e.g., they only appear inside quote blocks or are purely symbolic).
            // Default unresolved params to Atom.
            val body = scope.functionDefinition.body
            resolveAtom(body, scope)
            val bodyType = body.type ?: inferBodyTypeFromMatch(body) ?: inferBodyType(body)
            if (bodyType != null) {
                body.type = bodyType
                val fallbackTypes = mutableListOf<Atom>()
                scope.functionDefinition.params.forEach {
                    val type = scope.data[it.name]
                    fallbackTypes.add(type ?: GroundedType.ATOM)
                }
                fallbackTypes.add(bodyType)
                scope.functionDefinition.arrowType = ArrowType(fallbackTypes)
                addResolvedFunction(owner, scope.functionDefinition as FunctionDefinition)
                isCompleted = true
            }
        }
        return isCompleted
    }

    /**
     * Try to determine the body type from simple expressions.
     * For Symbol constants (like T, F), the type is Atom.
     */
    private fun inferBodyType(body: Atom): Atom? {
        return when (body) {
            is Symbol -> GroundedType.ATOM
            is Variable -> body.type
            is Expression -> body.type
            else -> null
        }
    }

    /**
     * For Match bodies, try to find a type from any branch that has a resolved type.
     * Branches with Symbol constants (like T, F) are treated as Atom type.
     */
    private fun inferBodyTypeFromMatch(body: Atom): Atom? {
        if (body !is Match) return null
        for (branch in body.branches) {
            if (branch.body.type != null) return branch.body.type
            // A Symbol constant in a branch body should be Atom type
            if (branch.body is Symbol) return GroundedType.ATOM
            // A Variable with no inferred type defaults to Atom
            if (branch.body is Variable) return GroundedType.ATOM
        }
        // If all branches are match calls returning SeqType, use that
        for (branch in body.branches) {
            if (branch.body is Expression && branch.body.type is SeqType) return branch.body.type
        }
        return null
    }

    private fun resolveSource(source: ParsedSource) {
        source.code.forEach {
            when (it) {
                is FunctionDefinition -> resolveFunctionDefinition(source.getJvmClassName(), it)
                else -> TODO("it=$it")
            }
        }
    }

    fun resolveFunctionDefinition(owner: String, functionDefinition: FunctionDefinition) {
        if (functionDefinition.returnType != null) addResolvedFunction(owner, functionDefinition)
        functionDefinition.typedParameters?.forEach {
            functionDefinition.params.find { v -> v.name == it.name }?.type = it.type
        }
        val scope = Scope(functionDefinition)
        resolveAtom(functionDefinition.body, scope)
        // If arrowType is still null after resolving, try to infer it.
        // This handles purely symbolic functions like (= (And T T) T)
        // where body type is known but no explicit type annotation exists.
        if (functionDefinition.arrowType == null && functionDefinition.name != FunctionRewriter.MAIN) {
            val bodyType = functionDefinition.body.type
                ?: inferBodyTypeFromMatch(functionDefinition.body)
                ?: inferBodyType(functionDefinition.body)
            if (bodyType != null) {
                functionDefinition.body.type = bodyType
                // Prefer a type inferred into the scope (e.g. `$m` pinned to Int by
                // an arithmetic operand or a comparison) over the head param
                // Variable's own type: the head's param instances are distinct
                // objects from the body occurrences and never receive the inferred
                // type directly, so reading `param.type` alone would freeze an
                // arithmetic-only parameter at Atom.
                val types = functionDefinition.params.map {
                    it.type ?: scope.data[it.name] ?: GroundedType.ATOM
                } + bodyType
                functionDefinition.arrowType = ArrowType(types)
                addResolvedFunction(owner, functionDefinition)
            }
        }
        definedFunctions[functionDefinition.name] = SymbolDef(owner, functionDefinition)
    }

    private fun isAssignableFrom(left: Atom, right: Atom): Boolean {
        // Atom and Any are the dynamic top: any value is assignable to them (the
        // call site boxes a primitive as needed). Without this, passing an Int to
        // an Atom-typed parameter — routine when a self-recursive callee's param
        // type is still the provisional Atom — is falsely reported as an
        // incompatibility instead of being boxed.
        if (left == GroundedType.ANY || left == GroundedType.ATOM) return true
        return left == right
    }

    /**
     * Within an inert data constructor, resolve reducible sub-calls (applicative order)
     * while leaving genuine data untouched. Descends through nested data constructors; when
     * a sub-expression's head is a KNOWN function it is resolved as a call (so it carries
     * `.resolved` + a concrete type and codegen evaluates it), otherwise the sub-atom stays
     * inert. Pure symbols/literals/variables are left as-is.
     */
    private fun resolveNestedCallsInData(atom: Atom, scope: Scope) {
        if (atom !is Expression) return
        val headName = (atom.atoms.firstOrNull() as? Symbol)?.name
        if (headName != null && resolve(headName) != null) {
            resolveExpression(atom, scope)
        } else {
            atom.atoms.forEach { resolveNestedCallsInData(it, scope) }
        }
    }

    private fun resolveAtom(atom: Atom, scope: Scope, suggestedType: Atom? = null) {
        logger.trace { "Resolving atom: $atom" }
        when (atom) {
            is Expression -> {
                // If the expected type is Atom, this expression is data (a constructor),
                // not a function call — don't try to resolve its head symbol.
                // Exception: a Special head (flat-map?/map?/if/arithmetic/…) is always
                // an executable form, even in an argument position with expected type
                // Atom — route it through resolveExpression so it receives its resolved
                // symbol and SeqType. Without this, a nested (flat-map? …) passed as a
                // call argument (e.g. inside `(ift (flat-map? …) …)`) would be stamped
                // Atom and reach codegen unresolved.
                val head = atom.atoms.firstOrNull()
                if (suggestedType == GroundedType.ATOM && head !is Special &&
                    resolve((head as? Symbol)?.name ?: "") == null
                ) {
                    atom.type = GroundedType.ATOM
                    // Applicative order: a data constructor does not suppress reduction of
                    // its arguments. Descend through the (data) constructor and resolve any
                    // reducible call nested inside — e.g. `(Cons (Bind $x (ev $e $env)) …)`,
                    // so codegen evaluates `(ev …)` and stores its VALUE, not an inert thunk.
                    // Pure data (symbols, literals, unknown sub-constructors) is left inert.
                    // Scan ALL elements including the head: for a plain tuple whose head is
                    // itself a call — `((add-atom …) (remove-atom …))`, the argument of
                    // `hide`/`superpose` — element 0 is a value too and must be reduced.
                    // A Symbol constructor head (`Cons`) is a no-op in resolveNestedCallsInData
                    // (not an Expression), so genuine constructors are unaffected.
                    atom.atoms.forEach { resolveNestedCallsInData(it, scope) }
                } else {
                    resolveExpression(atom, scope)
                }
            }

            is Variable -> {
                val data = scope[atom.name]
                if (data != null) {
                    atom.type = data.second
                    atom.scope = data.first.body as? Expression
                    if (suggestedType != null && atom.type != null && !isAssignableFrom(suggestedType, atom.type!!)) {
                        if (atom.type == GroundedType.ATOM) {
                            atom.type = suggestedType
                            scope.data[atom.name] = suggestedType
                        } else {
                            messageCollector.add(IncompatibleTypesMessage(suggestedType, atom.type!!, atom.position))
                        }
                    }
                } else {
                    // Variable not in scope — could be a match-time pattern variable
                    // (e.g., nested destructuring in Match branches).
                    // Default to Atom type; only report error if type was explicitly expected.
                    atom.type = atom.type ?: GroundedType.ATOM
                }
            }

            is Grounded<*> -> {
                if (suggestedType != null && !isAssignableFrom(suggestedType, atom.type!!)) {
                    messageCollector.add(IncompatibleTypesMessage(suggestedType, atom.type!!, atom.position))
                    return
                }
            }

            is Lambda -> {
                atom.arrowType = atom.arrowType ?: suggestedType as? ArrowType
                atom.type = atom.arrowType ?: suggestedType
                atom.params.forEachIndexed { index, variable ->
                    variable.type = atom.arrowType?.types?.get(index)
                }
                val lambdaTypeInfo = createLambdaTypeInfo(scope, atom)
                resolveAtom(atom.body, lambdaTypeInfo)
            }

            is Symbol -> {
                if (atom.name == Predefined.SELF) {
                    return
                }
                if (suggestedType == GroundedType.ATOM || suggestedType == GroundedType.ANY) {
                    atom.type = GroundedType.ATOM
                    return
                }
                val def = definedFunctions[atom.name]
                if (def == null) {
                    // If no suggested type, this is just a plain symbol constant (e.g., T, F)
                    // — not a function reference, so no error needed.
                    if (suggestedType != null) {
                        messageCollector.add(CannotResolveSymbolMessage(atom.name, atom.position))
                    }
                    return
                }
                if (suggestedType != def.func.arrowType) {
                    messageCollector.add(IncompatibleTypesMessage(suggestedType!!, def.func.arrowType!!, atom.position))
                    return
                }
                val wrapper = Lambda(
                    def.func.params,
                    def.func.arrowType,
                    Expression(listOf(atom) + def.func.params, def.func.returnType, null, atom.position),
                    position = atom.position
                )
                resolveAtom(wrapper, scope, suggestedType)
                replaceNode(atom, wrapper)
            }

            is Match -> {
                atom.branches.forEach { branch ->
                    if (branch.cond != null) resolveAtom(branch.cond!!, scope)
                    resolveAtom(branch.body, scope)
                }
            }

            is ArrowType -> {
                // An ArrowType atom in a value position (not a type annotation) — e.g.
                // the literal `(-> Number Number Number)` on the RHS of `assertEqual`,
                // or the result of evaluating `(get-type +)`. It's inert data; the
                // resolver just stamps its type. Codegen turns it into a runtime atom.
                atom.type = atom.type ?: GroundedType.ATOM
            }

            is Special -> {
                // A bare Special in a VALUE position — an operator used as data, e.g. an
                // element of a data tuple like `(superpose (+ - *))` where `+`/`-`/`*` are
                // selected as symbols and later applied via variable-head dispatch
                // (`(ev (Bin $op …)) → ($op …)`). A Special can only reach resolveAtom as an
                // operand/element (an operator that HEADS an expression is handled by the
                // Expression case), so stamping it inert ATOM here is narrow: it rescues a
                // case that previously crashed and never touches operator-as-head reduction.
                atom.type = atom.type ?: GroundedType.ATOM
            }

            else -> TODO("atom=$atom -> $scope -> ${atom.javaClass}")
        }
    }

    private fun replaceNode(from: Atom, to: Atom) {
        nodesToReplace[from] = to
    }

    private fun applyPostResolveRewriters(source: ParsedSource): ParsedSource {
        val rewriter = CompositeRewriter()
        rewriter.add { ReplaceNodesRewriter(nodesToReplace) }
        rewriter.add { MarkMultivaluedFunctionsRewriter(functions) }
        rewriter.add { LowerAssertExpressionsRewriter() }
        rewriter.add { QuotePureSymbolicBodiesRewriter() }
        rewriter.add { CanonicalFormRewriter(messageCollector, this) }
        val res = rewriter.rewrite(source)
        return res
    }

    private fun createLambdaTypeInfo(parentScope: Scope, lambda: Lambda): Scope = parentScope.join(lambda)

    private fun resolveExpression(expression: Expression, scope: Scope) {
        logger.trace { "Resolving expression: $expression" }
        if (!scope.isProvided &&
            scope.functionDefinition is FunctionDefinition &&
            scope.functionDefinition.name != FunctionRewriter.MAIN
        ) {
            logger.debug { "Add $expression >> $scope" }
            unresolvedElements[expression.id] = AtomWithTypeInfo(expression, scope)
        }
        if (expression.atoms.isEmpty()) {
            // Empty `()` — pure data. Used by `(quote ())` and similar.
            expression.type = GroundedType.ATOM
            return
        }
        when (val atom = expression.atoms[0]) {
            is Symbol -> {
                val resolved = resolve(atom.name)
                val expectedArity = resolved?.arrowType()?.let { it.types.size - 1 }
                if (resolved != null && expectedArity != null &&
                    definedFunctions[atom.name] != null &&
                    expression.arguments().size != expectedArity
                ) {
                    // Arity mismatch on a user-defined function: an under-application
                    // like `(S Z)` for 3-ary S, or a partial like `(K $x)`. This is NOT
                    // a JVM-invokable call — it's an inert MeTTa expression (data). Leave
                    // `resolved` null so codegen emits a quoted expression; runtime
                    // dispatch (JettaCallSite) can still reduce it via a matching `(= …)`
                    // space rule. Without this guard, codegen would emit an INVOKESTATIC
                    // with the function's full arity and the verifier/frame computation
                    // fails on the argument-count mismatch.
                    expression.type = GroundedType.ATOM
                    expression.arguments().forEach { resolveAtom(it, scope) }
                } else if (resolved != null) {
                    val arrowType = resolved.arrowType()
                    expression.arguments().mapIndexed { index, arg ->
                        if (atom.name == SUPERPOSE && arg is Expression) {
                            // operator-as-data: `superpose` enumerates its argument tuple as
                            // DATA, so a Special/operator head inside it — `(superpose (+ - *))`
                            // — is NOT executed as arithmetic; the operators pass through as
                            // symbols to be applied later by variable-head dispatch
                            // (`(ev (Bin $op …)) → ($op …)`). This is keyed on the CALLEE
                            // (superpose), never on `suggestedType == ATOM`: an untyped
                            // function's ATOM-defaulted param still reduces its arguments as
                            // before. Genuine Symbol-headed nested calls inside the tuple are
                            // still reduced (applicative order) by resolveNestedCallsInData.
                            arg.type = GroundedType.ATOM
                            arg.atoms.forEach { resolveNestedCallsInData(it, scope) }
                        } else {
                            resolveAtom(arg, scope, arrowType.types[index])
                        }
                    }
                    expression.resolved = resolved
                    expression.type = resolved.arrowType().types.last()
                    // Capture match patterns for pre-building indices
                    if (atom.name == "match" && expression.arguments().size >= 2) {
                        val quote = expression.arguments()[1]
                        if (quote is Expression) {
                            // (quote ...)
                            val pattern = quote.arguments()[0]
                            if (pattern is Expression) {
                                matchPatterns.add(pattern)
                            }
                        }
                    }
                } else {
                    // Always track as unresolved so subsequent passes can retry
                    unresolvedElements[expression.id] = AtomWithTypeInfo(expression, scope)
                    if (definedFunctions[atom.name] == null) {
                        // If the enclosing function has a Match body, unresolved symbols
                        // in expression-head position are data constructors (e.g., And, Pair)
                        // that should be quoted, not reported as errors.
                        // Also skip error if any param is typed Atom — the function accepts
                        // dynamic values so unresolved symbols are constructors.
                        if ((scope.functionDefinition is FunctionDefinition &&
                                    scope.functionDefinition.body is Match) ||
                            scope.functionDefinition.params.any { it.type == GroundedType.ATOM }
                        ) {
                            expression.type = GroundedType.ATOM
                            expression.arguments().forEach { resolveAtom(it, scope) }
                        } else {
                            messageCollector.add(CannotResolveSymbolMessage(atom.name, atom.position))
                        }
                    }
                }
            }

            is Special -> when (atom.value) {
                Predefined.IF -> {
                    val (_, cond, thenBranch, elseBranch) = expression.atoms
                    resolveAtom(cond, scope)
                    resolveAtom(thenBranch, scope)
                    resolveAtom(elseBranch, scope)
                    // Either branch may still be untyped if it contains an unresolved
                    // grounded call (e.g. `(superpose ())` before that built-in is
                    // implemented). Fall back to ATOM rather than crashing.
                    val thenT = thenBranch.type ?: GroundedType.ATOM
                    val elseT = elseBranch.type ?: GroundedType.ATOM
                    // A homogenized multivalued `if` stays a List. CanonicalFormRewriter
                    // seq-wraps the scalar arm of an `if` whose other arm is a multivalued
                    // (List) call, so both arms are Lists (upholding "multivalued `-> T` is
                    // physically `List<T>`"). If either arm is a SeqType, the `if` is a
                    // SeqType — and BOTH arms must carry the SAME element type so the
                    // consuming map?/flat-map? lambda (typed to that element) can unbox
                    // uniformly. A seq-wrapped scalar arm's element type came from the scalar
                    // (typically Atom); re-pin that seq artifact to the more specific element
                    // type (a grounded value beats Atom) so generateSeq unwraps its Grounded
                    // instead of leaving a raw Atom (Grounded→Number CCE downstream).
                    if (thenT is SeqType || elseT is SeqType) {
                        val thenE = (thenT as? SeqType)?.elementType ?: thenT
                        val elseE = (elseT as? SeqType)?.elementType ?: elseT
                        val elem = mostSpecificElementType(thenE, elseE)
                        val seqType = SeqType(elem)
                        if (thenBranch.isSeqArtifact()) thenBranch.type = seqType
                        if (elseBranch.isSeqArtifact()) elseBranch.type = seqType
                        expression.type = seqType
                    } else {
                        expression.type = unifyType(thenT, elseT)
                    }
                }

                Predefined.COND_EQ,
                Predefined.COND_NEQ -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    // D2.4 (increment 1): `==`/`!=` are grounded `(-> $t $t Bool)` — both
                    // operands must share a type. A concrete numeric-vs-String mismatch is an
                    // eval-time type error (hyperon yields `(Error <expr> (BadArgType …))`).
                    // Stamp the node ATOM so codegen routes it onto the value path (emitting the
                    // `(Error …)` atom) instead of the Bool comparison path — where an integer
                    // opcode over a String operand would VerifyError. Genuine Bool comparisons
                    // and structural `==` over two references are left untouched.
                    if (hasGroundedComparisonTypeError(lhs, rhs)) {
                        expression.type = GroundedType.ATOM
                    } else {
                        propagateComparisonOperandType(lhs, rhs, scope)
                        expression.type = GroundedType.BOOLEAN
                    }
                }

                Predefined.COND_LT,
                Predefined.COND_GT,
                Predefined.COND_LE,
                Predefined.COND_GE -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    propagateComparisonOperandType(lhs, rhs, scope)
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.TIMES, Predefined.MINUS, Predefined.PLUS -> {
                    val operands = expression.arguments()
                    operands.forEach { resolveAtom(it, scope) }
                    if (hasGroundedArithmeticTypeError(operands)) {
                        expression.type = GroundedType.ATOM
                    } else {
                        inferArithmeticOperandTypes(atom.value, operands, scope)
                        val hasDouble = operands.any { it.type == GroundedType.DOUBLE }
                        expression.type = if (hasDouble) GroundedType.DOUBLE else GroundedType.INT
                    }
                }

                Predefined.DIVIDE -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    expression.type = GroundedType.DOUBLE
                }

                Predefined.DIV, Predefined.MOD -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    inferArithmeticOperandTypes(atom.value, listOf(lhs, rhs), scope)
                    expression.type = GroundedType.INT
                }

                Predefined.RUN_SEQ -> {
                    expression.arguments().forEach {
                        resolveAtom(it, scope)
                    }
                    expression.type = expression.atoms.last().type
                    scope.functionDefinition.arrowType = expression.type?.let {
                        ArrowType(listOf(it))
                    }
                }

                Predefined.NOT -> {
                    resolveAtom(expression.atoms[1], scope)
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.AND, Predefined.OR, Predefined.XOR -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.SEQ -> {
                    expression.type = resolveList(expression, scope)
                }

                Predefined.MAP_ -> {
                    val lambda = expression.arguments()[0] as Lambda
                    resolveAtom(lambda, scope)
                    expression.arguments().drop(1).forEach { resolveAtom(it, scope) }
                    val bodyType = lambda.body.type ?: GroundedType.ATOM
                    // A map? lambda whose body is a void/Unit call — e.g. mapping `println`
                    // over a multivalued bag, `(map? (\ _x (println _x)) (pick))` — must
                    // still return a reference for the `JettaFunction` SAM: generateCall
                    // leaves a null placeholder on the stack after a void call, and the
                    // lambda ARETURNs it. A Unit/primitive return type would emit a void
                    // return / IRETURN of that null → VerifyError. Retype the lambda's
                    // return (and the map?'s element) as ANY so codegen ARETURNs; the map?
                    // then yields a bag of Units (side effects already happened).
                    val elementType = if (bodyType == GroundedType.UNIT) {
                        lambda.arrowType = lambda.arrowType?.let {
                            ArrowType(it.types.dropLast(1) + GroundedType.ANY)
                        }
                        GroundedType.ANY
                    } else bodyType
                    expression.type = SeqType(elementType, lambda.body.position)
                    expression.resolved = mapSymbol
                }

                Predefined.FLAT_MAP_ -> {
                    val lambda = expression.arguments()[0] as Lambda
                    resolveAtom(lambda, scope)
                    expression.arguments().drop(1).forEach { resolveAtom(it, scope) }
                    val bodyType = lambda.body.type ?: GroundedType.ATOM
                    // flat-map? FLATTENS: its lambda returns a List<B> and the result is
                    // List<B>, not List<List<B>> (runtime `simpleFlatMap` splices each
                    // sub-list's elements into one flat list). So when the body is already a
                    // Seq, that IS the result type — wrapping it again over-nests the SeqType
                    // (`Atom**`/`Atom***` for chained flat-maps like `gen`), which drives
                    // codegen to build a `List[]` and then ArrayStore a bare element into it.
                    // A non-Seq body (defensive / degenerate) still gets one Seq layer.
                    expression.type =
                        if (bodyType is SeqType) bodyType else SeqType(bodyType, lambda.body.position)
                    expression.resolved = flatMapSymbol
                }

                Predefined.QUOTE -> {
                    // A quoted form is inert data. Stamp it Atom: callers that pass a
                    // (quote …) as an argument with expected type Atom relied on the
                    // resolveAtom data-shortcut for this type; now that Special heads are
                    // routed here, set it explicitly.
                    expression.type = GroundedType.ATOM
                }

                Predefined.ANNOTATION,
                Predefined.PATTERN,
                Predefined.TYPE,
                Predefined.ARROW -> {
                    // Specials used as data heads (curried `(= ((K $x) $y) …)`,
                    // meta-rule `(= (= $x $x) T)`, tuple type tag `(: (A B) …)`,
                    // doc/annotation `(@ doc-string …)`, or a type form sitting in
                    // a value position). The rewriter-fallback (21bbee2) routes
                    // them to the space as facts; here in resolve they're inert
                    // data — type ATOM, recurse to resolve sub-atoms.
                    expression.type = GroundedType.ATOM
                    expression.atoms.drop(1).forEach { resolveAtom(it, scope) }
                }

                else -> TODO("atom=$atom")
            }

            is Variable -> {
                resolveAtom(atom, scope)
                expression.arguments().forEach {
                    resolveAtom(it, scope)
                }
            }

            is Lambda -> {
                // Inline lambda application `((\ ($x …) body) arg …)` — the shape
                // `let` desugars into. Resolve the argument(s) first so their types
                // are known, propagate them onto the bound parameters, then resolve
                // the lambda body (so grounded calls such as superpose/collapse inside
                // it are resolved, not left inert). The application's type is the
                // body's resolved type — which may be multivalued (Atom*) when the
                // body is e.g. `(superpose …)`.
                val args = expression.arguments()
                args.forEach { resolveAtom(it, scope) }
                if (atom.params.size == args.size) {
                    val paramTypes = atom.params.mapIndexed { index, param ->
                        param.type ?: args[index].type?.let { argType ->
                            // A multivalued argument (SeqType) is ITERATED: the map?/flat-map?
                            // lift feeds the lambda one element at a time, so the param is
                            // element-typed — e.g. `(let $e (gen $d) …)` binds $e to each of
                            // gen's results, not to the whole bag. Typing it as the SeqType
                            // makes the body treat the element as a List (Symbol→List CCE).
                            if (argType is SeqType) argType.elementType else argType
                        } ?: GroundedType.ATOM
                    }
                    // Provisional arrowType so resolveAtom(lambda) stamps the params;
                    // the return slot is refined from the resolved body below.
                    atom.arrowType = ArrowType(paramTypes + GroundedType.ATOM)
                }
                resolveAtom(atom, scope)
                val bodyType = atom.body.type ?: GroundedType.ATOM
                atom.arrowType = ArrowType(atom.params.map { it.type ?: GroundedType.ATOM } + bodyType)
                atom.type = atom.arrowType
                expression.type = bodyType
            }

            is Expression -> {
                expression.type = GroundedType.ATOM
                expression.atoms.forEach { resolveAtom(it, scope) }
            }

            is Grounded<*>, is ArrowType -> {
                // Expression with a non-functional head — a literal `(2 7)` tuple,
                // a `((-> A B) ...)` type form used as data, etc. The whole
                // expression is inert data; recurse to resolve sub-atoms.
                expression.type = GroundedType.ATOM
                expression.atoms.forEach { resolveAtom(it, scope) }
            }

            else -> TODO("atom=$atom")
        }
    }

    private fun resolveList(expression: Expression, scope: Scope): Atom {
        var elementType: Atom? = null
        expression.arguments().forEach {
            resolveAtom(it, scope)
            // An element may be an untyped constructor symbol (e.g. a seq-wrapped `nil`
            // arm of a multivalued `if`) whose type resolveAtom leaves null; treat it as
            // the generic ATOM element rather than crashing.
            elementType = unifyType(elementType, it.type ?: GroundedType.ATOM)
        }
        return SeqType(elementType ?: GroundedType.ATOM, expression.position)
    }

    private fun unifyType(lhsType: Atom?, rhsType: Atom): Atom {
        if (lhsType == null || lhsType == rhsType) return rhsType
        return GroundedType.ANY // FIXME: too narrow, please introduce NUMBER
    }

    // Pick the more specific of two element types when homogenizing the arms of a
    // multivalued `if`. Atom / ANY are the dynamic "top" — a concrete grounded value
    // (Int, Double, …) on the other side wins, so a seq-wrapped scalar arm inherits the
    // real element type of the other (List) arm. Genuinely conflicting concrete types
    // fall back to unifyType (ANY).
    private fun mostSpecificElementType(a: Atom, b: Atom): Atom = when {
        a == b -> a
        a == GroundedType.ATOM || a == GroundedType.ANY -> b
        b == GroundedType.ATOM || b == GroundedType.ANY -> a
        else -> unifyType(a, b)
    }

    // True for a `(seq …)` produced by rewriteIf's homogenization of a scalar `if` arm —
    // the arm whose element type should be re-pinned to match the other (List) arm.
    private fun Atom.isSeqArtifact(): Boolean =
        this is Expression && atoms.isNotEmpty() &&
                (atoms[0] as? Special)?.value == Predefined.SEQ

    fun resolve(name: String): ResolvedSymbol? =
        systemFunctions[name] ?: resolvedFunctions[name]
            ?.let { ResolvedSymbol(it.toJvm(), it.func.arrowType, it.func.isMultivalued()) }

    private fun ResolvedSymbol.arrowType(): ArrowType = arrowType ?: jvmMethod.arrowType()

    private fun ResolvedSymbol.paramTypes(): List<Atom> =
        arrowType().types.dropLast(1)

    private fun Expression.arguments() = atoms.drop(1)
}