package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.Indexer
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

class IndexerGenerator {

    fun generateIndexer(expr: Expression): CompilationResult {
        val cw = ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES)
        val className = getClassName(expr)
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            null,
            Type.getInternalName(Object::class.java),
            arrayOf(Type.getInternalName(Indexer::class.java))
        )
        generateConstructor(cw)
        generateMatch(cw, expr)
        generateIndex(cw, className)
        return CompilationResult(className, cw.toByteArray())
    }

    private fun generateConstructor(cw: ClassWriter) {
        val mv = createMethodVisitor(cw, "<init>", "()V")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
    }

    // TODO: remove
    fun match(expr: Expression): Boolean {
        if (expr.atoms.size != 4) return false
        return true
    }

    // TODO: remove
    fun getAtom(expr: Expression, n: Int, k: Int, name: String): Boolean {
        return ((expr.atoms[n] as Expression).atoms[k] as Symbol).name.equals(name)
    }

    fun testExpr(atom: Atom): Boolean {
        return atom is Expression
    }

    fun generateMatch(cw: ClassWriter, expr: Expression) {
        val vars = mutableMapOf<String, MutableList<List<Int>>>()
        val mv = createMethodVisitor(cw, "match", "(Lnet/singularity/jetta/compiler/frontend/ir/Expression;)Z")
        var stackSize = 1
        fun generateSubMatchRecursively(subExpr: Expression, index: List<Int>, depth: Int, nPops: Int) {
            generateDebug(mv, "generateSubMatchRecursively " + subExpr)
            generateDebugObj(mv)
            val end = Label()
            subExpr.atoms.forEach { _ -> generateDup(mv) }
//            generateDup(mv)
//            generateDup(mv)
            generateExprSizeCond(mv, expr.atoms.size, end, nPops)
            mv.visitLabel(end)

            // expr on stack, so we need size - 1 dups


//            mv.visitVarInsn(Opcodes.ALOAD, 1)
//            mv.visitVarInsn(Opcodes.ALOAD, 1)


//            var nDups = subExpr.atoms.size
            subExpr.atoms.forEachIndexed { i, atom ->
                generateGetAtom(mv, i)
                generateDebugObj(mv)
                when (atom) {
                    is Symbol -> {
                        generateDebug(mv, "Compare $atom")
                        // here we have size - 1 instances of expr on stack remains
                        // we need to remove from stack nPops
                        generateCompareSymbol(mv, atom.name, depth, 0)
                    }
                    is Variable -> {
                        val list = vars.getOrPut(atom.name) { mutableListOf() }
                        list.add(index + listOf(i))
                    }
                    is Expression -> {
                        generateDup(mv)
                        mv.visitTypeInsn(Opcodes.INSTANCEOF, EXPRESSION_TYPE)
                        val label = Label()
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, label)
                        // return false
                        generateReturnFalse(mv, nPops + 1)
                        mv.visitLabel(label)
                        // subexpression is on top (DUP)
                        mv.visitTypeInsn(Opcodes.CHECKCAST, EXPRESSION_TYPE)
                        generateDebugObj(mv)
                        generateSubMatchRecursively(atom, index, depth + 1, 0)
                    }
                    else -> TODO("atom=$atom")
                    // FIXME: add GroundType
                }
            }
            stackSize++
        }
        mv.visitVarInsn(Opcodes.ALOAD, 1) // expr
        generateSubMatchRecursively(expr, listOf(), 0, 0)

        // return true
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitMaxs(stackSize, 2)
    }

    private fun generateCompareSymbol(mv: LocalVariablesSorter, name: String, depth: Int, nPops: Int) {
        // atom on top
        generateDebugObj(mv)
        generateDup(mv)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SYMBOL_TYPE)
        mv.visitInsn(Opcodes.ICONST_1)
        val checkEquality = Label()
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, checkEquality)
        // return false
        generateReturnFalse(mv, nPops + 1)
        mv.visitLabel(checkEquality)
        // subexpression is on top (DUP)
        mv.visitTypeInsn(Opcodes.CHECKCAST, SYMBOL_TYPE)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SYMBOL_TYPE, "getName", "()Ljava/lang/String;", false)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        // return false
        generateDebug(mv, "equals: $name depth=$depth")
        generateDebug(mv)
        val label = Label()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, label)
        // return false
        generateReturnFalse(mv, nPops)
        mv.visitLabel(label)
    }

    private fun generateExprSizeCond(mv: LocalVariablesSorter, n: Int, next: Label, nPops: Int) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(Expression::class.java), "getAtoms", "()Ljava/util/List;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true)
        generateLoadInt(mv, n)
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, next)
        generateDebug(mv, "size")
        generateReturnFalse(mv, nPops)
    }

    private var count = 0
    private fun generateDup(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.DUP)
        count++
    }

    private fun generatePop(mv: MethodVisitor) {
        mv.visitInsn(Opcodes.POP)
        count--
    }

    private fun generateReturnFalse(mv: LocalVariablesSorter, nPops: Int) {
//        (0..<nPops).forEach { _ ->
//            generatePop(mv)
//        }
//        if (count != 0) {
//            throw IllegalStateException("count=$count")
//        }
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
    }


    private fun generateGetAtom(mv: LocalVariablesSorter, n: Int) {
        //    LINENUMBER 63 L1
        //    ALOAD 1
        //    INVOKEVIRTUAL net/singularity/jetta/compiler/frontend/ir/Expression.getAtoms ()Ljava/util/List;
        //    ILOAD 2
        //    INVOKEINTERFACE java/util/List.get (I)Ljava/lang/Object; (itf)
        //    CHECKCAST net/singularity/jetta/compiler/frontend/ir/Atom
        //    ARETURN
        // expression on top
//        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(Expression::class.java), "getAtoms", "()Ljava/util/List;", false)
        generateLoadInt(mv, n)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
        mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(Atom::class.java))
    }

    fun mPrintln(i: Int) {
        println(i)
    }

    fun generateDebug(mv: MethodVisitor, s: String) {
//        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
//        mv.visitLdcInsn(s)
//        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false)
    }

    fun generateDebug(mv: MethodVisitor) {
//        mv.visitInsn(Opcodes.DUP)
//        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
//        mv.visitInsn(Opcodes.SWAP)
//        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false)
    }

    fun generateDebugObj(mv: MethodVisitor) {
//        mv.visitInsn(Opcodes.DUP)
//        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
//        mv.visitInsn(Opcodes.SWAP)
//        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false)
    }

    private fun createMethodVisitor(cw: ClassWriter, name: String, desc: String): LocalVariablesSorter {
        val access = Opcodes.ACC_PUBLIC
        return LocalVariablesSorter(
            access,
            desc,
            cw.visitMethod(
                access,
                name,
                desc,
                null,
                null
            )
        )
    }

    fun generateIndex(cw: ClassWriter, className: String) {}

    fun getClassName(expr: Expression): String =
        "Hello" // FIXME

    companion object {
        val SYMBOL_TYPE: String = Type.getInternalName(Symbol::class.java)
        val EXPRESSION_TYPE: String = Type.getInternalName(Expression::class.java)
    }
}