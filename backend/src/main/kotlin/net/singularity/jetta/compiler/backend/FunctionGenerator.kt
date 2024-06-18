package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.Predefined.AND
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_EQ
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_GE
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_GT
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_LE
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_LT
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_NEQ
import net.singularity.jetta.compiler.frontend.ir.Predefined.FALSE
import net.singularity.jetta.compiler.frontend.ir.Predefined.IF
import net.singularity.jetta.compiler.frontend.ir.Predefined.MINUS
import net.singularity.jetta.compiler.frontend.ir.Predefined.OR
import net.singularity.jetta.compiler.frontend.ir.Predefined.PLUS
import net.singularity.jetta.compiler.frontend.ir.Predefined.RUN_SEQ
import net.singularity.jetta.compiler.frontend.ir.Predefined.TIMES
import net.singularity.jetta.compiler.frontend.ir.Predefined.TRUE
import net.singularity.jetta.compiler.frontend.ir.Predefined.XOR
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class FunctionGenerator(private val mv: MethodVisitor, private val function: FunctionDefinition) {
    private var maxStack = 0
    private var maxLocals = function.params.size

    fun generate() {
        generateAtom(function.body, null, true)
        mv.visitMaxs(maxStack, maxLocals)
    }

    private fun FunctionDefinition.getParameterIndex(variable: Variable): Int {
        var jvmIndex = 0
        var index = 0
        params.forEach {
            if (it.name == variable.name) return jvmIndex
            when (arrowType!!.types[index++]) {
                GroundedType.INT, GroundedType.BOOLEAN -> jvmIndex++
                GroundedType.DOUBLE -> jvmIndex += 2
                else -> TODO()
            }
        }
        throw IllegalArgumentException(variable.toString())
    }

    private fun generateAtom(atom: Atom, exit: Label?, doReturn: Boolean) {
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
                val function = atom.atoms[0]
                val arguments = atom.atoms.drop(1)

                when (function) {
                    PLUS, TIMES, MINUS -> generateArithmetics(function, arguments, atom.type as GroundedType, doReturn)
                    IF -> generateIf(arguments, exit, doReturn)
                    RUN_SEQ -> {
                        arguments.forEach {
                            generateAtom(it, null, false)
                        }
                    }
                    is Special -> {
                        if (function.isBooleanExpression()) {
                            generateIf(listOf(atom, TRUE, FALSE), exit, doReturn)
                        }
                    }
                    is Symbol -> generateCall(function.name, arguments, atom.resolved)
                    else -> TODO("Not implemented yet $function")
                }
            }

            is Variable -> {
                when (atom.type) {
                    GroundedType.INT,
                    GroundedType.BOOLEAN -> mv.visitVarInsn(Opcodes.ILOAD, function.getParameterIndex(atom))
                    GroundedType.DOUBLE -> mv.visitVarInsn(Opcodes.DLOAD, function.getParameterIndex(atom))
                    else -> TODO("Not implemented yet")
                }
            }

            else -> TODO("Not implemented yet $atom")
        }
        if (doReturn) {
            generateReturn()
        } else {
            if (exit != null) {
                mv.visitJumpInsn(Opcodes.GOTO, exit)
            }
        }
    }

    private fun generateCall(functionName: String, arguments: List<Atom>, resolved: ResolvedSymbol?) {
        val (jvmSymbol, _) = resolved ?: throw UnresolvedSymbolError(functionName)
        arguments.forEach {
            generateAtom(it, null, false)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            jvmSymbol.owner,
            jvmSymbol.name,
            jvmSymbol.descriptor,
            false
        )
    }

    private fun generateReturn() {
        when (function.returnType) {
            GroundedType.INT, GroundedType.BOOLEAN -> mv.visitInsn(Opcodes.IRETURN)
            GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DRETURN)
            GroundedType.UNIT -> mv.visitInsn(Opcodes.RETURN)
            else -> TODO("type=${function.returnType} of $function")
        }
    }

    private fun generateBooleanExpr(
        expr: Atom,
        exit: Label,
    ) {
        fun generateBooleanOp(left: Atom, right: Atom, inverseOp: Int) {
            val label1 = Label()
            generateAtom(left, label1, false)
            mv.visitLabel(label1)
            val label2 = Label()
            generateAtom(right, label2, false)
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
            TRUE -> TODO()
            FALSE -> TODO()
            is Expression -> {
                val (op, left, right) = expr.atoms
                when (op) {
                    AND -> {
                        val label = Label()
                        generateBooleanExpr(left, label) // true or false on stack
                        mv.visitLabel(label)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val next = Label()
                        mv.visitJumpInsn(Opcodes.IF_ICMPNE, next) // if true then check the right part
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, exit)
                        mv.visitLabel(next)
                        generateBooleanExpr(right, exit)
                    }

                    OR -> {
                        val label = Label()
                        generateBooleanExpr(left, label)
                        mv.visitLabel(label)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val next = Label()
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, next)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, exit)
                        mv.visitLabel(next)
                        generateBooleanExpr(right, exit)
                    }

                    XOR -> {
                        val label1 = Label()
                        generateBooleanExpr(left, label1)
                        mv.visitLabel(label1)
                        val label2 = Label()
                        generateBooleanExpr(right, label2)
                        mv.visitLabel(label2)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.ICONST_2)
                        mv.visitInsn(Opcodes.IREM) // not the best way but simple
                    }

                    COND_EQ -> generateBooleanOp(left, right, Opcodes.IF_ICMPNE)
                    COND_NEQ -> generateBooleanOp(left, right, Opcodes.IF_ICMPEQ)
                    COND_GT -> generateBooleanOp(left, right, Opcodes.IF_ICMPLE)
                    COND_LT -> generateBooleanOp(left, right, Opcodes.IF_ICMPGE)
                    COND_GE -> generateBooleanOp(left, right, Opcodes.IF_ICMPLT)
                    COND_LE -> generateBooleanOp(left, right, Opcodes.IF_ICMPGT)
                    else -> TODO("Op=$op")
                }
            }

            else -> TODO()
        }
    }

    private fun generateIf(arguments: List<Atom>, exit: Label?, doReturn: Boolean) {
        val (cond, thenExpr, elseExpr) = arguments
        val label = Label()
        generateBooleanExpr(cond, label)
        mv.visitLabel(label)
        mv.visitInsn(Opcodes.ICONST_1)
        val elseLabel = Label()
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, elseLabel)
        generateAtom(thenExpr, exit, doReturn)
        mv.visitLabel(elseLabel)
        generateAtom(elseExpr, exit, doReturn)
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

    private fun castIfNeeded(type: Atom, requiredType: Atom) {
        if (type == requiredType) return
        if (type == GroundedType.INT && requiredType == GroundedType.DOUBLE) {
            mv.visitInsn(Opcodes.I2D)
            return
        }
        TODO()
    }

    private fun generateArithmetics(op: Atom, arguments: List<Atom>, type: GroundedType, doReturn: Boolean) {
        fun operation() {
            when (op) {
                PLUS -> when (type) {
                    GroundedType.INT -> mv.visitInsn(Opcodes.IADD)
                    GroundedType.DOUBLE -> mv.visitInsn(Opcodes.DADD)
                    else -> TODO()
                }

                TIMES -> mv.visitInsn(Opcodes.IMUL)
                MINUS -> mv.visitInsn(Opcodes.ISUB)
                else -> TODO("Not implemented $op")
            }
        }
        generateAtom(arguments[0], null, false)
        castIfNeeded(arguments[0].type(), type)
        generateAtom(arguments[1], null, false)
        castIfNeeded(arguments[1].type(), type)
        operation()
        maxStack += 2
        for (i in 2..<arguments.size) {
            generateAtom(arguments[i], null, false)
            castIfNeeded(arguments[i].type(), type)
            operation()
            maxStack++
        }
        if (doReturn) generateReturn()
    }
}