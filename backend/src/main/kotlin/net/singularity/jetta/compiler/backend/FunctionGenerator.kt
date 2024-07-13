package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.getApplyJvmPlainDescriptor
import net.singularity.jetta.compiler.frontend.resolve.getJvmInterfaceName
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

open class FunctionGenerator(
    private val mv: MethodVisitor,
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

    protected fun generateAtom(mv: MethodVisitor, atom: Atom, exit: Label?, doReturn: Boolean) {
        when (atom) {
            is Grounded<*> -> {
                when (atom.value) {
                    0, false -> mv.visitInsn(Opcodes.ICONST_0)
                    1, true -> mv.visitInsn(Opcodes.ICONST_1)
                    2 -> mv.visitInsn(Opcodes.ICONST_2)
                    3 -> mv.visitInsn(Opcodes.ICONST_3)
                    4 -> mv.visitInsn(Opcodes.ICONST_4)
                    5 -> mv.visitInsn(Opcodes.ICONST_5)
                    is Int -> mv.visitIntInsn(Opcodes.BIPUSH, atom.value as Int)
                    0.0 -> mv.visitInsn(Opcodes.DCONST_0)
                    1.0 -> mv.visitInsn(Opcodes.DCONST_1)
                    is Double -> mv.visitLdcInsn(atom.value)
                    else -> TODO("Not implemented yet " + atom.value)
                }
            }

            is Expression -> {
                val func = atom.atoms[0]
                val arguments = atom.atoms.drop(1)

                when (func) {
                    Predefined.PLUS, Predefined.TIMES, Predefined.MINUS -> generateArithmetics(
                        mv,
                        func,
                        arguments,
                        atom.type as GroundedType,
                        doReturn
                    )

                    Predefined.DIVIDE -> generateDivide(mv, arguments, doReturn)

                    Predefined.IF -> generateIf(mv, arguments, exit, doReturn)
                    Predefined.RUN_SEQ -> {
                        arguments.forEach {
                            generateAtom(mv, it, null, false)
                        }
                    }

                    is Special -> {
                        if (func.isBooleanExpression()) {
                            generateIf(mv, listOf(atom, Predefined.TRUE, Predefined.FALSE), exit, doReturn)
                        }
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
            else -> TODO("Not implemented yet $atom")
        }
        if (doReturn) {
            generateReturn(mv)
        } else {
            if (exit != null) {
                mv.visitJumpInsn(Opcodes.GOTO, exit)
            }
        }
    }

    private fun generateCall(
        mv: MethodVisitor,
        functionName: String,
        arguments: List<Atom>,
        resolved: ResolvedSymbol?
    ) {
        val (jvmSymbol, _) = resolved ?: throw UnresolvedSymbolError(functionName)
        arguments.forEach {
            generateAtom(mv, it, null, false)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            jvmSymbol.owner,
            jvmSymbol.name,
            jvmSymbol.descriptor,
            false
        )
    }

    private fun generateLambdaCall(mv: MethodVisitor, variable: Variable, arguments: List<Atom>) {
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
        when (function.returnType) {
            GroundedType.INT, GroundedType.BOOLEAN -> mv.visitInsn(Opcodes.IRETURN)
            GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DRETURN)
            GroundedType.UNIT -> mv.visitInsn(Opcodes.RETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
    }

    private fun generateBooleanExpr(mv: MethodVisitor, expr: Atom, exit: Label) {
        fun generateBooleanOp(left: Atom, right: Atom, inverseOp: Int) {
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

        when (expr) {
            Predefined.TRUE -> TODO()
            Predefined.FALSE -> TODO()
            is Expression -> {
                val (op, left, right) = expr.atoms
                when (op) {
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
                        generateBooleanExpr(mv, right, exit)
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
                        generateBooleanExpr(mv, right, exit)
                    }

                    Predefined.XOR -> {
                        val label1 = Label()
                        generateBooleanExpr(mv, left, label1)
                        mv.visitLabel(label1)
                        val label2 = Label()
                        generateBooleanExpr(mv, right, label2)
                        mv.visitLabel(label2)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.ICONST_2)
                        mv.visitInsn(Opcodes.IREM) // not the best way but simple
                    }

                    Predefined.COND_EQ -> generateBooleanOp(left, right, Opcodes.IF_ICMPNE)
                    Predefined.COND_NEQ -> generateBooleanOp(left, right, Opcodes.IF_ICMPEQ)
                    Predefined.COND_GT -> generateBooleanOp(left, right, Opcodes.IF_ICMPLE)
                    Predefined.COND_LT -> generateBooleanOp(left, right, Opcodes.IF_ICMPGE)
                    Predefined.COND_GE -> generateBooleanOp(left, right, Opcodes.IF_ICMPLT)
                    Predefined.COND_LE -> generateBooleanOp(left, right, Opcodes.IF_ICMPGT)
                    else -> TODO("Op=$op")
                }
            }

            else -> TODO()
        }
    }

    private fun generateIf(mv: MethodVisitor, arguments: List<Atom>, exit: Label?, doReturn: Boolean) {
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
        mv: MethodVisitor,
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

    private fun generateArithmetics(
        mv: MethodVisitor,
        op: Atom,
        arguments: List<Atom>,
        type: GroundedType,
        doReturn: Boolean
    ) {
        fun operation() {
            when (op) {
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