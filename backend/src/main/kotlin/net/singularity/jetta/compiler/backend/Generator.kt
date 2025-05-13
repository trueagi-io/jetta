package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.getJvmDescriptor
import net.singularity.jetta.compiler.frontend.resolve.getSignature
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

class Generator(val generateMain: Boolean = false) {
    private var lambdaCount = 1

    fun generate(source: ParsedSource): List<CompilationResult> {
        lambdaCount = 1
        val cw = ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES)
        val className = source.getJvmClassName()
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            null,
            Type.getInternalName(Object::class.java),
            null
        )
        val result = findLambdas(source).toList().sortedBy {
            val ind = it.first.indexOf('$')
            if (ind >= 0) it.first.substring(ind + 1).toInt() else 0
        }.reversed().map { (name, lambda) ->
            lambda.resolvedClassName = name
            val lambdaGenerator = LambdaGenerator(name, lambda)
            lambdaGenerator.generate()
        }
        source.code.forEach { node ->
            when (node) {
                is FunctionDefinition -> {
                    val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
                    val desc = node.getJvmDescriptor()
                    val mv = LocalVariablesSorter(
                        access,
                        desc,
                        cw.visitMethod(
                            access,
                            node.name,
                            desc,
                            node.getSignature(),
                            null
                        )
                    )
                    FunctionGenerator(mv, node, true, null).generate()
                    if (generateMain && node.name == FunctionRewriter.MAIN) {
                        val mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                            "main",
                            "([Ljava/lang/String;)V",
                            null,
                            null
                        )
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "__main", "()V", false)
                        mv.visitInsn(Opcodes.RETURN)
                    }
                }

                else -> TODO("Not implemented yet")
            }
        }
        return result + listOf(CompilationResult(className, cw.toByteArray()))
    }

    private fun mkLambdaName(source: ParsedSource): String {
        return source.getJvmClassName()
    }

    private fun findLambdas(source: ParsedSource): Map<String, Lambda> {
        val result = mutableMapOf<String, Lambda>()
        source.code.forEach {
            val def = (it as FunctionDefinition)
            val name = mkLambdaName(source)
            when (val body = def.body) {
                is Expression -> findLambdas(name, def.body as Expression, result)
                is Match -> {
                    body.branches.forEach { branch ->
                        findLambdas(name, branch.body, result)
                    }
                }
                else -> {}
            }
        }
        return result
    }

    private fun findLambdas(name: String, body: Atom, acc: MutableMap<String, Lambda>): Map<String, Lambda> {
        when (body) {
            is Expression -> {
                if ((body.atoms.first() as? Special)?.value == Predefined.RUN_SEQ) {
                    body.atoms.drop(1).forEach {
                        findLambdas(name, it as Expression, acc)
                    }
                    return acc
                }
                body.atoms.forEach {
                    when (it) {
                        is Lambda -> {
                            val lambdaName = "$name$${lambdaCount++}"
                            acc[lambdaName] = it
                            findLambdas(name, it.body, acc)
                        }

                        is Expression -> {
                            findLambdas(name, it, acc)
                        }

                        else -> {}
                    }
                }
            }

            else -> {}
        }
        return acc
    }
}