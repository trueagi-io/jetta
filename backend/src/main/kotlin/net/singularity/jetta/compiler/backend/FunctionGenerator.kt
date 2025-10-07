package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.*
import net.singularity.jetta.runtime.Matcher
import net.singularity.jetta.runtime.space.SpaceImpl
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

open class FunctionGenerator(
    private val mv: LocalVariablesSorter,
    private val function: FunctionLike,
    private val isStatic: Boolean,
    private val className: String?
) {
    fun generate() {
        generateAtom(mv, function.body, null, true)
        mv.visitMaxs(maxStack, maxLocals)
    }

    protected var maxStack = 0
    protected var maxLocals = function.params.size

    private fun generateLoadInt(value: Int) {
        when (value) {
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            else -> mv.visitIntInsn(Opcodes.BIPUSH, value)
        }
    }

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
                if (atom.name == Predefined.SELF) {
                    generateSpaceSingleton(mv)
                } else TODO("Not implemented " + atom.name)
            }

            else -> TODO("Not implemented yet $atom")
        }
    }

    private fun generateSpaceSingleton(mv: LocalVariablesSorter) {
        mv.visitFieldInsn(
            Opcodes.GETSTATIC,
            "net/singularity/jetta/runtime/Matcher",
            "INSTANCE",
            "Lnet/singularity/jetta/runtime/Matcher;"
        )
        mv.visitFieldInsn(
            Opcodes.GETSTATIC,
            "net/singularity/jetta/runtime/space/SpaceImpl",
            "Companion",
            "Lnet/singularity/jetta/runtime/space/SpaceImpl\$Companion;"
        )
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "net/singularity/jetta/runtime/space/SpaceImpl\$Companion",
            "getInstance",
            "()Lnet/singularity/jetta/runtime/space/Space;",
            false
        )
    }

    protected fun generateAtom(
        mv: LocalVariablesSorter,
        atom: Atom,
        exit: Label?,
        doReturn: Boolean,
        needBoxing: Boolean = false
    ) {
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

                    is Symbol -> generateCall(mv, func.name, arguments, atom.resolved)
                    is Variable -> generateLambdaCall(mv, func, arguments)

                    else -> TODO("Not implemented yet $func")
                }
            }

            is Variable -> {
                generateLoadVar(mv, atom, function.params, isStatic, className)
            }

            is Lambda -> {
                mv.visitTypeInsn(Opcodes.NEW, atom.resolvedClassName!!)
                mv.visitInsn(Opcodes.DUP)
                val capturedVariables = atom.capturedVariables()
                capturedVariables.forEach {
                    generateLoadVar(mv, it, function.params, isStatic, className)
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

    fun test() {
        Matcher.match(
            SpaceImpl.getInstance(),
            Expression(Symbol("leaf2")), Variable("x")
        )
    }

    private fun generateQuote(mv: LocalVariablesSorter, atom: Atom) {
        when (atom) {
            is Expression -> {
                /*
                    LINENUMBER 177 L2
                    NEW net/singularity/jetta/compiler/frontend/ir/Expression
                    DUP
                    ICONST_2
                    ANEWARRAY net/singularity/jetta/compiler/frontend/ir/Atom
                    ASTORE 1
                    ALOAD 1
                    ICONST_0
                    NEW net/singularity/jetta/compiler/frontend/ir/Symbol
                    DUP
                    LDC "hello"
                    ACONST_NULL
                    ICONST_2
                    ACONST_NULL
                    INVOKESPECIAL net/singularity/jetta/compiler/frontend/ir/Symbol.<init> (Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
                    AASTORE
                    ALOAD 1
                    ICONST_1
                    NEW net/singularity/jetta/compiler/frontend/ir/Symbol
                    DUP
                    LDC "world"
                    ACONST_NULL
                    ICONST_2
                    ACONST_NULL
                    INVOKESPECIAL net/singularity/jetta/compiler/frontend/ir/Symbol.<init> (Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
                    AASTORE
                    ALOAD 1
                    ACONST_NULL
                    ACONST_NULL
                    BIPUSH 6
                    ACONST_NULL
                    INVOKESPECIAL net/singularity/jetta/compiler/frontend/ir/Expression.<init> ([Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/ResolvedSymbol;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
                   L3
                 */
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

            /*
                NEW net/singularity/jetta/compiler/frontend/ir/Symbol
                DUP
                LDC "hello"
                ACONST_NULL
                ICONST_2
                ACONST_NULL
                INVOKESPECIAL net/singularity/jetta/compiler/frontend/ir/Symbol.<init> (Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
               L4
             */
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

            is Grounded<*> -> {
                TODO()
            }

            is Variable -> {
                /*
                LINENUMBER 178 L3
                NEW net/singularity/jetta/compiler/frontend/ir/Variable
                DUP
                LDC "x"
                ACONST_NULL
                ACONST_NULL
                BIPUSH 6
                ACONST_NULL
                INVOKESPECIAL net/singularity/jetta/compiler/frontend/ir/Variable.<init> (Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
                CHECKCAST net/singularity/jetta/compiler/frontend/ir/Atom
               L4
                LINENUMBER 175 L4
                INVOKEVIRTUAL net/singularity/jetta/runtime/Matcher.match (Lnet/singularity/jetta/runtime/space/Space;Lnet/singularity/jetta/compiler/frontend/ir/Expression;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;
                POP
               L5
                 */
                mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Variable::class.java))
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(atom.name)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ACONST_NULL)
                generateLoadInt(6)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    Type.getInternalName(Variable::class.java),
                    "<init>",
                    "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false
                )
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(Atom::class.java))
            }

            else -> TODO()
        }
    }

    private fun generateMatch(mv: LocalVariablesSorter, match: Match) {
        generateLoadInt(match.branches.size)
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
        val resultVar = mv.newLocal(Type.getObjectType("java/util/ArrayList"))
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List")
        mv.visitVarInsn(Opcodes.ASTORE, resultVar)
        match.branches.forEach { branch -> generateMatchBranch(mv, branch, match.returnType!!, resultVar) }
        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        mv.visitInsn(Opcodes.ARETURN)
    }

    private fun generateMatchBranch(mv: LocalVariablesSorter, branch: MatchBranch, resultType: Atom, resultVar: Int) {
        // 1. generate condition for the pattern
        // 2. generate function application
        val elseLabel = Label()
        if (branch.cond != null) {
            val label = Label()
            generateBooleanExpr(mv, branch.cond!!, label)
            mv.visitLabel(label)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, elseLabel)
        }
        mv.visitVarInsn(Opcodes.ALOAD, resultVar)
        generateAtom(mv, branch.body, null, false)
        generateBoxingIfNeeded(resultType)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        mv.visitInsn(Opcodes.POP)
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
        if (resolved.jvmMethod.name == "match") {
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                jvmSymbol.owner,
                jvmSymbol.name,
                jvmSymbol.descriptor,
                false
            )
        } else {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                jvmSymbol.owner,
                jvmSymbol.name,
                jvmSymbol.descriptor,
                false
            )
        }
    }

    private fun generateLambdaCall(mv: LocalVariablesSorter, variable: Variable, arguments: List<Atom>) {
        val index = function.getParameterIndex(variable)
        if (index < 0) throw IllegalArgumentException(variable.toString())
        mv.visitVarInsn(Opcodes.ALOAD, index)
        arguments.forEach {
            generateAtom(mv, it, null, false)
            boxIfNeeded(mv, it.type as? GroundedType)
        }
        val arrowType = variable.type as ArrowType
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            arrowType.getJvmInterfaceName(),
            "apply",
            arrowType.getApplyJvmPlainDescriptor(),
            true
        )
        unboxIfNeeded(mv, arrowType.types.last() as? GroundedType)
    }

    private fun generateReturn(mv: MethodVisitor) {
        if (function is FunctionDefinition && function.isMultivalued()) {
            mv.visitInsn(Opcodes.ARETURN)
            return
        }
        when (function.returnType) {
            GroundedType.INT, GroundedType.BOOLEAN -> mv.visitInsn(Opcodes.IRETURN)
            GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DRETURN)
            GroundedType.UNIT -> mv.visitInsn(Opcodes.RETURN)
            GroundedType.LIST -> mv.visitInsn(Opcodes.ARETURN)
            is SeqType -> mv.visitInsn(Opcodes.ARETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
    }

    private fun generateBooleanExpr(mv: LocalVariablesSorter, expr: Atom, exit: Label) {
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
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPNE)
                        }
                    }

                    Predefined.COND_NEQ -> {
                        if (left.type == GroundedType.DOUBLE) {
                            generateDoubleGt(left, right!!, Opcodes.IFNE)
                        } else {
                            generateIntComparison(left, right!!, Opcodes.IF_ICMPEQ)
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