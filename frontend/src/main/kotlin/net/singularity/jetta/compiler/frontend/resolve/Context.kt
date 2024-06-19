package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotInferTypeMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import net.singularity.jetta.compiler.logger.Logger

class Context(private val messageCollector: MessageCollector) {
    private val logger = Logger.getLogger(Context::class.java)
    private val definedFunctions = mutableMapOf<String, SymbolDef>()
    private val resolvedFunctions = mutableMapOf<String, SymbolDef>()
    private val systemFunctions = mutableMapOf<String, ResolvedSymbol>()
    private val unresolvedElements = mutableMapOf<SourcePosition, AtomWithTypeInfo>()
    private var main: FunctionDefinition? = null

    private fun cleanUp() {
        messageCollector.clear()
        unresolvedElements.clear()
    }

    private data class SymbolDef(val owner: String, val func: FunctionDefinition)

    private data class AtomWithTypeInfo(val atom: Atom, val info: TypeInfo)

    private data class TypeInfo(
        val data: MutableMap<String, Atom>,
        val functionDefinition: FunctionDefinition
    ) {
        val isProvided = functionDefinition.typedParameters != null
    }

    private fun SymbolDef.toJvm() = JvmMethod(
        owner = owner,
        name = func.name,
        descriptor = func.getJvmDescriptor()
    )

    fun addResolvedFunction(owner: String, func: FunctionDefinition) {
        logger.debug("Add function ${func.name}")
        resolvedFunctions[func.name] = SymbolDef(owner, func)
        main?.let {
            val lastCall = it.body.atoms.last()
            if (lastCall is Expression &&
                (lastCall.atoms[0] as? Symbol)?.name == func.name) {
                it.arrowType = ArrowType(listOf(func.returnType!!))
                lastCall.resolved = resolve(func.name)
            }
        }
    }

    fun addSystemFunction(resolvedSymbol: ResolvedSymbol) {
        systemFunctions[resolvedSymbol.jvmMethod.name] = resolvedSymbol
    }

    private fun inferType(atom: Atom, typeInfo: TypeInfo, suggestedType: Atom? = null) {
        logger.debug("Infer type for atom $atom")
        when (atom) {
            is Expression -> inferTypeForExpression(atom, typeInfo)
            is Variable -> {
                typeInfo.data[atom.name]?.let {
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

    private fun inferTypeForExpression(expression: Expression, typeInfo: TypeInfo) {
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
                                    typeInfo.data[arg.name] = type
                                }

                                is Grounded<*> -> {
                                    if (arg.type != type) {
                                        TODO()
                                    }
                                }

                                is Expression -> inferTypeForExpression(arg, typeInfo)

                                else -> TODO()
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

            Predefined.IF -> {
                val (_, cond, thenBranch, elseBranch) = expression.atoms
                inferType(cond, typeInfo)
                inferType(thenBranch, typeInfo)
                inferType(elseBranch, typeInfo, thenBranch.type)
                inferType(thenBranch, typeInfo, elseBranch.type)
                // TODO: check types of then and else
                // TODO: for simplicity now (should be unified)
                expression.type = thenBranch.type ?: elseBranch.type
            }

            Predefined.COND_EQ -> {
                val (_, lhs, rhs) = expression.atoms
                inferType(lhs, typeInfo)
                inferType(rhs, typeInfo)
                if (lhs.type != null && rhs.type == null) {
                    rhs.type = lhs.type
                    if (rhs is Variable) typeInfo.data[rhs.name] = lhs.type!!
                }
                if (lhs.type == null && rhs.type != null) {
                    lhs.type = rhs.type
                    if (lhs is Variable) typeInfo.data[lhs.name] = rhs.type!!
                }
                expression.type = GroundedType.BOOLEAN
            }

            Predefined.TIMES, Predefined.MINUS, Predefined.PLUS -> {
                expression.arguments().forEach {
                    inferType(it, typeInfo)
                }
            }

            else -> TODO("atom=$atom")
        }
    }

    fun resolve(source: ParsedSource) {
        cleanUp()
        resolveSource(source)
        main = source.code.find { it is FunctionDefinition && it.name == FunctionRewriter.MAIN } as? FunctionDefinition
        val postponedFunctions = mutableMapOf<String, TypeInfo>()
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
                            postponedFunctions[it.functionDefinition.name] = it
                        }
                    }
                val resolved = mutableListOf<Pair<SourcePosition, Atom>>()
                HashMap(unresolvedElements).forEach { (pos, data) ->
                    logger.debug("Resolving ${data.atom}")
                    resolveAtom(data.atom, data.info)
                    if (data.atom.type != null) resolved.add(pos to data.atom)
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
        } catch (_: UndefinedSymbolException) { }
        if (unresolvedElements.isNotEmpty()) {
            unresolvedElements.forEach { (_, data) ->
                messageCollector.add(CannotInferTypeMessage(data.atom, data.info.functionDefinition))
            }
        }
    }

    private fun updateFunction(owner: String, typeInfo: TypeInfo): Boolean {
        logger.debug("Update: $typeInfo")
        if (typeInfo.functionDefinition.arrowType != null) return true

        var isCompleted = true
        val types = mutableListOf<Atom>()
        typeInfo.functionDefinition.params.forEach {
            val type = typeInfo.data[it.name]
            if (type != null) {
                types.add(type)
            } else {
                isCompleted = false
            }
        }
        if (isCompleted) {
            val body = typeInfo.functionDefinition.body
            resolveExpression(body, typeInfo)
            if (body.type != null) {
                types.add(body.type!!)
                typeInfo.functionDefinition.arrowType = ArrowType(types)
            }
            addResolvedFunction(owner, typeInfo.functionDefinition)
        }
        return isCompleted
    }

    private fun resolveSource(source: ParsedSource) {
        source.code.map {
            when (it) {
                is FunctionDefinition -> resolveFunctionDefinition(source.getJvmClassName(), it)
                else -> TODO()
            }
        }
    }

    private fun resolveFunctionDefinition(owner: String, functionDefinition: FunctionDefinition) {
        val typedVariables = mutableMapOf<String, Atom>()
        if (functionDefinition.returnType != null) addResolvedFunction(owner, functionDefinition)
        functionDefinition.typedParameters?.forEach {
            typedVariables[it.name] = it.type!!
        }
        resolveExpression(
            functionDefinition.body,
            TypeInfo(typedVariables, functionDefinition)
        )
        definedFunctions[functionDefinition.name] = SymbolDef(owner, functionDefinition)
    }

    private fun resolveAtom(atom: Atom, typeInfo: TypeInfo) {
        logger.debug("Resolving atom: $atom")
        when (atom) {
            is Expression -> resolveExpression(atom, typeInfo)
            is Variable -> {
                atom.type = typeInfo.data[atom.name]
            }

            is Grounded<*> -> {}
            else -> TODO("atom=$atom")
        }
    }

    private fun resolveExpression(expression: Expression, typeInfo: TypeInfo) {
        logger.debug("Resolving expression: $expression")
        if (!typeInfo.isProvided && typeInfo.functionDefinition.name != FunctionRewriter.MAIN) {
            logger.debug("Add $expression >> $typeInfo")
            assert(expression.position != null) { logger.error("No position: $expression") }
            unresolvedElements[expression.position!!] = AtomWithTypeInfo(expression, typeInfo)
        }
        when (val atom = expression.atoms[0]) {
            is Symbol -> {
                val resolved = resolve(atom.name)
                if (resolved != null) {
                    expression.arguments().map { resolveAtom(it, typeInfo) }
                    expression.resolved = resolved
                    expression.type = resolved.arrowType().types.last()
                } else {
                    if (unresolvedElements.isEmpty()) {
                        messageCollector.add(CannotResolveSymbolMessage(atom.name, atom.position))
                    }
                }
            }

            Predefined.IF -> {
                val (_, cond, thenBranch, elseBranch) = expression.atoms
                resolveAtom(cond, typeInfo)
                resolveAtom(thenBranch, typeInfo)
                resolveAtom(elseBranch, typeInfo)
            }

            Predefined.COND_EQ -> {
                val (_, lhs, rhs) = expression.atoms
                resolveAtom(lhs, typeInfo)
                resolveAtom(rhs, typeInfo)
            }

            Predefined.TIMES, Predefined.MINUS, Predefined.PLUS -> {
                var hasDouble = false
                expression.atoms.drop(1).forEach {
                    resolveAtom(it, typeInfo)
                    if (it.type == GroundedType.DOUBLE) hasDouble = true
                }
                expression.type = if (hasDouble) GroundedType.DOUBLE else GroundedType.INT
            }

            Predefined.RUN_SEQ -> {
                expression.arguments().forEach {
                    resolveAtom(it, typeInfo)
                }
                expression.type = expression.atoms.last().type
                println(">>>> " + expression.atoms.last())
                typeInfo.functionDefinition.arrowType = expression.type?.let {
                    ArrowType(listOf(it))
                }
            }
            else -> TODO("atom=$atom")
        }
    }

    private fun resolve(name: String): ResolvedSymbol? =
        systemFunctions[name] ?: resolvedFunctions[name]?.toJvm()
            ?.let { ResolvedSymbol(it, false) }

    private fun ResolvedSymbol.arrowType(): ArrowType = jvmMethod.arrowType()

    private fun ResolvedSymbol.paramTypes(): List<Atom> =
        arrowType().types.dropLast(1)

    private fun Expression.arguments() = atoms.drop(1)
}