package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.*
import net.singularity.jetta.runtime.Matcher
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
) {
    private val destructuredLocals = mutableMapOf<String, Int>()

    /**
     * Check whether the given variable is a captured variable in the current
     * lambda class (i.e., it was a free variable in the lambda body and is
     * stored as a field in the generated lambda class).
     */
    private fun isLambdaCapturedVariable(variable: Variable): Boolean {
        if (function !is Lambda) return false
        val lambda = function
        return lambda.capturedVariables().any { it.name == variable.name }
    }

    fun generate() {
        emitLineNumber(function)
        val matcherType = Type.getInternalName(Matcher::class.java)

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
        when (atom) {
            is Expression -> {
                val func = atom.atoms[0]
                val arguments = atom.atoms.drop(1)

                when (func) {
                    is Special -> when (func.value) {
                        Predefined.PLUS, Predefined.TIMES, Predefined.MINUS -> generateArithmetics(
                            mv,
                            func,
                            arguments,
                            atom.type as GroundedType,
                            doReturn
                        )

                        Predefined.DIVIDE -> generateDivide(mv, arguments, doReturn)
                        Predefined.DIV -> generateDiv(mv, arguments, doReturn)
                        Predefined.MOD -> generateMod(mv, arguments, doReturn)

                        Predefined.IF -> generateIf(mv, arguments, exit, doReturn)
                        Predefined.RUN_SEQ -> {
                            arguments.forEach {
                                generateAtom(mv, it, null, false)
                            }
                        }

                        Predefined.SEQ -> generateSeq(mv, arguments, atom.type!!)
                        Predefined.MAP_ -> generateCall(mv, Predefined.MAP_, arguments, atom.resolved)
                        Predefined.FLAT_MAP_ -> generateCall(mv, Predefined.FLAT_MAP_, arguments, atom.resolved)
                        Predefined.QUOTE -> generateQuote(mv, arguments[0])
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
                            // Unresolved symbol in function position — quote the whole expression
                            // This handles MeTTa constructors like (And $a $b), (Pair $x $y), etc.
                            generateQuote(mv, atom)
                        }
                    }

                    is Variable -> generateLambdaCall(mv, func, arguments)

                    else -> TODO("Not implemented yet $func")
                }
            }

            is Variable -> {
                val slot = destructuredLocals[atom.name]
                if (slot != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                } else {
                    val paramIndex = function.getParameterIndex(atom)
                    if (paramIndex >= 0) {
                        generateLoadVar(mv, atom, function.params, isStatic, className)
                    } else if (isLambdaCapturedVariable(atom)) {
                        // Captured variable from enclosing scope — load via GETFIELD
                        generateLoadVar(mv, atom, function.params, isStatic, className)
                    } else {
                        // True free variable — create a raw Variable for match unification
                        generateNewVariable(mv, atom.name)
                    }
                }
            }

            is Lambda -> {
                mv.visitTypeInsn(Opcodes.NEW, atom.resolvedClassName!!)
                mv.visitInsn(Opcodes.DUP)
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
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    atom.resolvedClassName,
                    "<init>",
                    mkLambdaInitDescriptor(capturedVariables),
                    false
                )
                mv.visitTypeInsn(Opcodes.CHECKCAST, atom.arrowType!!.getJvmInterfaceName())
            }

            is Match -> generateMatch(mv, atom)

            else -> {
                generateLoad(mv, atom)
            }
        }
        if (needBoxing) generateBoxingIfNeeded(atom.type!!)
        if (doReturn) {
            generateReturn(mv)
        } else {
            if (exit != null) {
                mv.visitJumpInsn(Opcodes.GOTO, exit)
            }
        }
    }

    private fun generateQuote(mv: LocalVariablesSorter, atom: Atom) {
        when (atom) {
            is Expression -> {
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Expression::class.java))
                mv.visitInsn(Opcodes.DUP)
                generateLoadInt(atom.atoms.size)
                val atomType = Type.getInternalName(Atom::class.java)
                val arr = mv.newLocal(Type.getObjectType("[$atomType"))
                mv.visitTypeInsn(Opcodes.ANEWARRAY, atomType)
                mv.visitVarInsn(Opcodes.ASTORE, arr)
                atom.atoms.forEachIndexed { index, atom ->
                    mv.visitVarInsn(Opcodes.ALOAD, arr)
                    generateLoadInt(index)
                    generateQuote(mv, atom)
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
                if (slot != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                } else {
                    // Use generateLoadVar which handles function params, captured
                    // lambda fields, AND falls back to generateNewVariable for
                    // truly free pattern variables (when className == null).
                    generateLoadVar(mv, atom, function.params, isStatic, className)
                }
            }

            else -> TODO("Not implemented yet $atom")
        }
    }

    private fun generateMatch(mv: LocalVariablesSorter, match: Match) {
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
        match.branches.forEach { branch -> generateMatchBranch(mv, branch, returnType, resultVar) }
        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        generatePop(mv)
        mv.visitInsn(Opcodes.ARETURN)
    }

    private fun generateMatchBranch(mv: LocalVariablesSorter, branch: MatchBranch, resultType: Atom, resultVar: Int) {
        val elseLabel = Label()
        if (branch.cond != null) {
            emitLineNumber(branch.cond!!)
            // Resolve bindings on parameters before evaluating conditions.
            // This ensures that shared Variables passed from callers have
            // their bindings applied before pattern matching.
            for (i in function.params.indices) {
                if (function.params[i].type != GroundedType.ATOM) continue
                val paramOffset = if (isStatic) 0 else 1
                mv.visitVarInsn(Opcodes.ALOAD, i + paramOffset)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(Matcher::class.java),
                    "resolveBinding",
                    "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;",
                    false
                )
                mv.visitVarInsn(Opcodes.ASTORE, i + paramOffset)
            }
            val label = Label()
            generateBooleanExpr(mv, branch.cond!!, label)
            mv.visitLabel(label)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, elseLabel)
        }

        // Extract destructured bindings into local variable slots
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

        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        generateAtom(mv, branch.body, null, false)

        // If the branch body produces a List (SeqType), flatten it into the result
        // using addAll instead of add. This handles match/flat-map branches that
        // return multiple results.
        if (branch.body.type is SeqType) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true)
        } else {
            generateBoxingIfNeeded(resultType)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        }
        mv.visitInsn(Opcodes.POP)

        destructuredLocals.clear()
        destructuredLocals.putAll(savedLocals)

        if (branch.cond != null) {
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
            generateLoad(mv, arg)
            generateBoxingIfNeeded(arg.type!!)
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

    private fun generateBoxingIfNeeded(type: Atom) {
        val (owner, name, desc) = when (type) {
            GroundedType.INT -> Triple("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
            GroundedType.BOOLEAN -> TODO()
            GroundedType.DOUBLE -> Triple("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;")
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
            generateAtom(mv, arg, null, false, jvmSymbol.doesParameterHaveAnyType(index))
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
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(Matcher::class.java),
            "pop",
            "()V",
            false
        )
        if (function is FunctionDefinition && function.isMultivalued()) {
            mv.visitInsn(Opcodes.ARETURN)
            return
        }
        when (function.returnType) {
            GroundedType.INT, GroundedType.BOOLEAN -> mv.visitInsn(Opcodes.IRETURN)
            GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DRETURN)
            GroundedType.UNIT -> mv.visitInsn(Opcodes.RETURN)
            GroundedType.ATOM -> mv.visitInsn(Opcodes.ARETURN)
            GroundedType.LIST -> mv.visitInsn(Opcodes.ARETURN)
            is SeqType -> mv.visitInsn(Opcodes.ARETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
    }

    private fun containsVariable(atom: Atom): Boolean = when (atom) {
        is Variable -> true
        is Expression -> atom.atoms.any { containsVariable(it) }
        else -> false
    }

    private fun generateBooleanExpr(mv: LocalVariablesSorter, expr: Atom, exit: Label) {
        emitLineNumber(expr)
        fun generateIntComparison(left: Atom, right: Atom, inverseOp: Int) {
            val label1 = Label()
            generateAtom(mv, left, label1, false)
            mv.visitLabel(label1)
            val label2 = Label()
            generateAtom(mv, right, label2, false)
            mv.visitLabel(label2)
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
            generateAtom(mv, right, null, false)

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
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFEQ)
                        } else if (left.type == GroundedType.INT || left.type == GroundedType.BOOLEAN) {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPNE)
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
                                    Type.getInternalName(Matcher::class.java),
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
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFNE)
                        } else if (left.type == GroundedType.INT || left.type == GroundedType.BOOLEAN) {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPEQ)
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
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFGT)
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPLE)
                        }
                    }

                    Predefined.COND_LT -> {
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFLT)
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPGE)
                        }
                    }

                    Predefined.COND_GE -> {
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFGE)
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPLT)
                        }
                    }

                    Predefined.COND_LE -> {
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFLE)
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPGT)
                        }
                    }

                    else -> TODO("Op=$op")
                }
            }

            else -> generateAtom(mv, expr, exit, false)
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
        generateAtom(mv, thenExpr, exit, doReturn)
        mv.visitLabel(elseLabel)
        generateAtom(mv, elseExpr, exit, doReturn)
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
        TODO()
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

                Predefined.TIMES -> mv.visitInsn(Opcodes.IMUL)
                Predefined.MINUS -> mv.visitInsn(Opcodes.ISUB)
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
        Type.getInternalName(Matcher::class.java),
        "pop",
        "()V",
        false
    )
}