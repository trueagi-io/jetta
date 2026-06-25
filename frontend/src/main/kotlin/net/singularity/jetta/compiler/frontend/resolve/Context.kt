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

class Context(
    private val messageCollector: MessageCollector,
    mapImpl: JvmMethod? = null,
    flatMapImpl: JvmMethod? = null,
    private val space: Space
) {
    private val logger = Logger.getLogger(Context::class.java)
    val definedFunctions = mutableMapOf<String, SymbolDef>()
    private val resolvedFunctions = mutableMapOf<String, SymbolDef>()
    private val functions = mutableMapOf<String, FunctionDefinition>()
    private val systemFunctions = mutableMapOf<String, ResolvedSymbol>()
    private val unresolvedElements = mutableMapOf<Int, AtomWithTypeInfo>()
    private val nodesToReplace = mutableMapOf<Atom, Atom>()
    private var main: FunctionDefinition? = null
    private var typeInferenceDone = false
    private val mapSymbol = mapImpl?.let { ResolvedSymbol(it, null, false) }
    private val flatMapSymbol = flatMapImpl?.let { ResolvedSymbol(it, null, false) }
    private val matchPatterns = mutableSetOf<Expression>()

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
                                    if (arg.type != null && !isAssignableFrom(type, arg.type!!)) {
                                        TODO()
                                    }
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
                    expression.arguments().forEach {
                        inferType(it, scope)
                    }
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

    private fun refineFunctionArrowTypes() {
        resolvedFunctions.forEach { (_, def) ->
            val arrowType = def.func.arrowType ?: return@forEach
            val paramTypes = arrowType.types.dropLast(1)
            if (paramTypes.none { it == GroundedType.ATOM }) return@forEach

            val inferredParamTypes = mutableMapOf<String, Atom>()
            collectVariableTypes(def.func.body, inferredParamTypes)

            val refinedTypes = def.func.params.mapIndexed { index, param ->
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
            if (refinedTypes != paramTypes) {
                def.func.arrowType = ArrowType(refinedTypes + arrowType.types.last())
                addResolvedFunction(def.owner, def.func)
            }
        }
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
        refineFunctionArrowTypes()
        resolveSource(source)
        val normalizedMain = normalizeMainForCodegen(source)
        val postprocessed = applyPostResolveRewriters(normalizedMain)
        messageCollector.clear()
        resolveSource(postprocessed)
        validateExecutableCalls(postprocessed)
        defaultUntypedToAtom(postprocessed)
        return postprocessed
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
        resolveAtom(
            functionDefinition.body,
            Scope(functionDefinition)
        )
        // If arrowType is still null after resolving, try to infer it.
        // This handles purely symbolic functions like (= (And T T) T)
        // where body type is known but no explicit type annotation exists.
        if (functionDefinition.arrowType == null && functionDefinition.name != FunctionRewriter.MAIN) {
            val bodyType = functionDefinition.body.type
                ?: inferBodyTypeFromMatch(functionDefinition.body)
                ?: inferBodyType(functionDefinition.body)
            if (bodyType != null) {
                functionDefinition.body.type = bodyType
                val types = functionDefinition.params.map { it.type ?: GroundedType.ATOM } + bodyType
                functionDefinition.arrowType = ArrowType(types)
                addResolvedFunction(owner, functionDefinition)
            }
        }
        definedFunctions[functionDefinition.name] = SymbolDef(owner, functionDefinition)
    }

    private fun isAssignableFrom(left: Atom, right: Atom): Boolean {
        if (left == GroundedType.ANY) return true
        return left == right
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
                        resolveAtom(arg, scope, arrowType.types[index])
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
                    expression.type = unifyType(thenT, elseT)
                }

                Predefined.COND_EQ,
                Predefined.COND_NEQ,
                Predefined.COND_LT,
                Predefined.COND_GT,
                Predefined.COND_LE,
                Predefined.COND_GE -> {
                    val (_, lhs, rhs) = expression.atoms
                    resolveAtom(lhs, scope)
                    resolveAtom(rhs, scope)
                    expression.type = GroundedType.BOOLEAN
                }

                Predefined.TIMES, Predefined.MINUS, Predefined.PLUS -> {
                    var hasDouble = false
                    expression.atoms.drop(1).forEach {
                        resolveAtom(it, scope)
                        if (it.type == GroundedType.DOUBLE) hasDouble = true
                    }
                    expression.type = if (hasDouble) GroundedType.DOUBLE else GroundedType.INT
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
                    expression.type = SeqType(bodyType, lambda.body.position)
                    expression.resolved = mapSymbol
                }

                Predefined.FLAT_MAP_ -> {
                    val lambda = expression.arguments()[0] as Lambda
                    resolveAtom(lambda, scope)
                    expression.arguments().drop(1).forEach { resolveAtom(it, scope) }
                    val bodyType = lambda.body.type ?: GroundedType.ATOM
                    expression.type = SeqType(bodyType, lambda.body.position)
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
                        param.type ?: args[index].type ?: GroundedType.ATOM
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
            elementType = unifyType(elementType, it.type!! /* FIXME */)
        }
        return SeqType(elementType!!, expression.position)
    }

    private fun unifyType(lhsType: Atom?, rhsType: Atom): Atom {
        if (lhsType == null || lhsType == rhsType) return rhsType
        return GroundedType.ANY // FIXME: too narrow, please introduce NUMBER
    }

    fun resolve(name: String): ResolvedSymbol? =
        systemFunctions[name] ?: resolvedFunctions[name]
            ?.let { ResolvedSymbol(it.toJvm(), it.func.arrowType, it.func.isMultivalued()) }

    private fun ResolvedSymbol.arrowType(): ArrowType = arrowType ?: jvmMethod.arrowType()

    private fun ResolvedSymbol.paramTypes(): List<Atom> =
        arrowType().types.dropLast(1)

    private fun Expression.arguments() = atoms.drop(1)
}