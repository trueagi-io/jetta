package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.getJvmDescriptor
import net.singularity.jetta.compiler.frontend.resolve.getSignature
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

class Generator {
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
            findLambdas(mkLambdaName(source), def.body, result)
        }
        return result
    }

    private fun findLambdas(name: String, body: Expression, acc: MutableMap<String, Lambda>): Map<String, Lambda> {
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
        return acc
    }
}