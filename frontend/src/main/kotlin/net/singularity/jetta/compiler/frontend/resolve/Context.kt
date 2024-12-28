package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotInferTypeMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.IncompatibleTypesMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.UndefinedVariableMessage
import net.singularity.jetta.compiler.frontend.rewrite.CanonicalFormRewriter
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.MarkMultivaluedFunctionsRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ReplaceNodesRewriter
import net.singularity.jetta.compiler.logger.Logger

class Context(
    private val messageCollector: MessageCollector,
    mapImpl: JvmMethod? = null,
    flatMapImpl: JvmMethod? = null
) {
    private val logger = Logger.getLogger(Context::class.java)
    val definedFunctions = mutableMapOf<String, SymbolDef>()
    private val resolvedFunctions = mutableMapOf<String, SymbolDef>()
    private val systemFunctions = mutableMapOf<String, ResolvedSymbol>()
    private val unresolvedElements = mutableMapOf<Int, AtomWithTypeInfo>()
    private val nodesToReplace = mutableMapOf<Atom, Atom>()
    private var main: FunctionDefinition? = null
    private var postprocessingDone = false
    private val mapSymbol = mapImpl?.let { ResolvedSymbol(it, null, false) }
    private val flatMapSymbol = flatMapImpl?.let { ResolvedSymbol(it, null, false) }

    private fun cleanUp() {
        messageCollector.clear()
        unresolvedElements.clear()
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
        logger.debug("Add function ${func.name}")
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

    fun addSystemFunction(resolvedSymbol: ResolvedSymbol) {
        systemFunctions[resolvedSymbol.jvmMethod.name] = resolvedSymbol
    }

    private fun inferType(atom: Atom, scope: Scope, suggestedType: Atom? = null) {
        logger.debug("Infer type for atom $atom")
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
                    else -> TODO("${atom.value}")
                }
            }

            else -> TODO("atom=$atom")
        }
    }

    private fun inferTypeForExpression(expression: Expression, scope: Scope) {
        logger.debug("Infer type for expression: $expression")
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
                                    if (arg.type != type) {
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

                Predefined.COND_EQ -> {
                    val (_, lhs, rhs) = expression.atoms
                    inferType(lhs, scope)
                    inferType(rhs, scope)
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

                Predefined.TIMES, Predefined.MINUS, Predefined.PLUS -> {
                    expression.arguments().forEach {
                        inferType(it, scope)
                    }
                }

                else -> TODO("atom=$atom")
            }

            else -> TODO("atom=$atom")
        }
    }

    fun resolve(source: ParsedSource): ParsedSource {
        cleanUp()
        return resolveRecursively(source)
    }

    fun resolveRecursively(source: ParsedSource): ParsedSource {
        main = source.code.find { it is FunctionDefinition && it.name == FunctionRewriter.MAIN } as? FunctionDefinition
        resolveSource(source)
        val postponedFunctions = mutableMapOf<String, Scope>()
        val owner = source.getJvmClassName()
        try {
            do {
                var numElements = unresolvedElements.size
                unresolvedElements.forEach { (_, data) ->
                    inferType(data.atom, data.info)
                    logger.debug("----------------------------------")
                    logger.debug(data.atom.toString())
                    logger.debug(data.info.toString())
                    logger.debug("----------------------------------")
                }
                unresolvedElements
                    .toList()
                    .map { (_, data) -> data.info }
                    .toSet()
                    .forEach {
                        if (!updateFunction(owner, it)) {
                            postponedFunctions[(it.functionDefinition as FunctionDefinition).name] = it
                        }
                    }
                val resolved = mutableListOf<Pair<Int, Atom>>()
                HashMap(unresolvedElements).forEach { (id, data) ->
                    logger.debug("Resolving ${data.atom}")
                    resolveAtom(data.atom, data.info)
                    if (data.atom.type != null) resolved.add(id to data.atom)
                }
                resolved.forEach {
                    logger.debug("Remove resolved: ${it.second}")
                    unresolvedElements.remove(it.first)
                    logger.debug("Remaining unresolved elements: ${unresolvedElements.size}")
                }
                val updated = mutableListOf<String>()
                postponedFunctions.forEach { (name, info) ->
                    logger.debug("Try to update $name")
                    if (updateFunction(owner, info)) {
                        logger.debug("Updated $name")
                        updated.add(name)
                    }
                }
                updated.forEach { postponedFunctions.remove(it) }
            } while (unresolvedElements.isNotEmpty() || unresolvedElements.size != numElements)
        } catch (_: UndefinedSymbolException) {
        }
        if (unresolvedElements.isNotEmpty()) {
            unresolvedElements.forEach { (_, data) ->
                messageCollector.add(
                    CannotInferTypeMessage(
                        data.atom,
                        data.info.functionDefinition as FunctionDefinition
                    )
                )
            }
        }
        return if (postprocessingDone)
            source
        else {
            postprocessingDone = true
            resolveRecursively(applyPostResolveRewriters(source))
        }
    }

    private fun updateFunction(owner: String, scope: Scope): Boolean {
        logger.debug("Update: $scope")
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
            if (body.type != null) {
                types.add(body.type!!)
                scope.functionDefinition.arrowType = ArrowType(types)
            }
            addResolvedFunction(owner, scope.functionDefinition as FunctionDefinition)
        }
        return isCompleted
    }

    private fun resolveSource(source: ParsedSource) {
        println(source)
        source.code.forEach {
            when (it) {
                is FunctionDefinition -> resolveFunctionDefinition(source.getJvmClassName(), it)
                else -> TODO("it=$it")
            }
        }
        println("----")
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
        definedFunctions[functionDefinition.name] = SymbolDef(owner, functionDefinition)
    }

    private fun resolveAtom(atom: Atom, scope: Scope, suggestedType: Atom? = null) {
        logger.debug("Resolving atom: $atom")
        when (atom) {
            is Expression -> resolveExpression(atom, scope)
            is Variable -> {
                val data = scope[atom.name]
                if (data != null) {
                    atom.type = data.second
                    atom.scope = data.first.body as? Expression
                } else {
                    messageCollector.add(UndefinedVariableMessage(atom.name, atom.position))
                }
            }

            is Grounded<*> -> {}
            is Lambda -> {
                atom.arrowType = atom.arrowType ?: suggestedType as ArrowType
                atom.type = atom.arrowType ?: suggestedType
                atom.params.forEachIndexed { index, variable ->
                    variable.type = atom.arrowType!!.types[index]
                }
                val lambdaTypeInfo = createLambdaTypeInfo(scope, atom)
                resolveAtom(atom.body, lambdaTypeInfo)
            }

            is Symbol -> {
                val def = definedFunctions[atom.name]
                if (def == null) {
                    messageCollector.add(CannotResolveSymbolMessage(atom.name, atom.position))
                    return
                }
                if (suggestedType != def.func.arrowType) {
                    messageCollector.add(IncompatibleTypesMessage(suggestedType!!, def.func.arrowType!!, atom.position))
                    return
                }
                val wrapper = Lambda(
                    def.func.params,
                    def.func.arrowType,
                    Expression(listOf(atom) + def.func.params, def.func.returnType, null, atom.position)
                )
                resolveAtom(wrapper, scope, suggestedType)
                replaceNode(atom, wrapper)
            }

            else -> TODO("atom=$atom -> $scope -> ${atom.javaClass}")
        }
    }

    private fun replaceNode(from: Atom, to: Atom) {
        nodesToReplace[from] = to
    }

    private fun applyPostResolveRewriters(source: ParsedSource): ParsedSource {
        val rewriter = CompositeRewriter()
        rewriter.add(ReplaceNodesRewriter(nodesToReplace))
        rewriter.add(MarkMultivaluedFunctionsRewriter())
        rewriter.add(CanonicalFormRewriter(messageCollector, this))
        val res = rewriter.rewrite(source)
        return res
    }

    private fun createLambdaTypeInfo(parentScope: Scope, lambda: Lambda): Scope = parentScope.join(lambda)

    private fun resolveExpression(expression: Expression, scope: Scope) {
        logger.debug("Resolving expression: $expression")
        if (!scope.isProvided &&
            scope.functionDefinition is FunctionDefinition &&
            scope.functionDefinition.name != FunctionRewriter.MAIN
        ) {
            logger.debug("Add $expression >> $scope")
            unresolvedElements[expression.id] = AtomWithTypeInfo(expression, scope)
        }
        when (val atom = expression.atoms[0]) {
            is Symbol -> {
                val resolved = resolve(atom.name)
                if (resolved != null) {
                    val arrowType = resolved.arrowType()
                    expression.arguments().mapIndexed { index, arg ->
                        resolveAtom(arg, scope, arrowType.types[index])
                    }
                    expression.resolved = resolved
                    expression.type = resolved.arrowType().types.last()
                } else {
                    if (unresolvedElements.isEmpty()) {
                        messageCollector.add(CannotResolveSymbolMessage(atom.name, atom.position))
                    }
                }
            }

            is Special -> when (atom.value) {
                Predefined.IF -> {
                    val (_, cond, thenBranch, elseBranch) = expression.atoms
                    resolveAtom(cond, scope)
                    resolveAtom(thenBranch, scope)
                    resolveAtom(elseBranch, scope)
                    expression.type = unifyType(thenBranch.type, elseBranch.type!!)
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

                Predefined.SEQ -> {
                    expression.type = resolveList(expression, scope)
                }

                Predefined.MAP_ -> {
                    val lambda = expression.arguments()[0] as Lambda
                    resolveAtom(lambda, scope)
                    expression.type = SeqType(lambda.body.type!!, lambda.body.position)
                    expression.resolved = mapSymbol
                }

                Predefined.FLAT_MAP_ -> {
                    val lambda = expression.arguments()[0] as Lambda
                    resolveAtom(lambda, scope)
                    expression.type = SeqType(lambda.body.type!!, lambda.body.position)
                    expression.resolved = flatMapSymbol
                }

                else -> TODO("atom=$atom")
            }

            is Variable -> {
                resolveAtom(atom, scope)
                expression.arguments().forEach {
                    resolveAtom(it, scope)
                }
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
            ?.let { ResolvedSymbol(it.toJvm(), it.func, it.func.isMultivalued()) }

    private fun ResolvedSymbol.arrowType(): ArrowType = func?.arrowType ?: jvmMethod.arrowType()

    private fun ResolvedSymbol.paramTypes(): List<Atom> =
        arrowType().types.dropLast(1)

    private fun Expression.arguments() = atoms.drop(1)
}