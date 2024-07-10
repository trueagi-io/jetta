package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.Lambda
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.getJvmDescriptor
import net.singularity.jetta.compiler.frontend.resolve.getSignature
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class Generator {

    fun generate(source: ParsedSource): List<CompilationResult> {
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
        // FIXME: sort it carefully by level
        val result = findLambdas(source).toList().sortedBy {
            it.first.mapNotNull { ch ->
                if (ch == '$') null else ch
            }.joinToString(separator = "")
        }.reversed().map { (name, lambda) ->
            lambda.resolvedClassName = name
            val lambdaGenerator = LambdaGenerator(name, lambda)
            lambdaGenerator.generate()
        }
        source.code.forEach { node ->
            when (node) {
                is FunctionDefinition -> {
                    val mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                        node.name,
                        node.getJvmDescriptor(),
                        node.getSignature(),
                        null
                    )
                    FunctionGenerator(mv, node, true, null).generate()
                }

                else -> TODO("Not implemented yet")
            }
        }
        return result + listOf(CompilationResult(className, cw.toByteArray()))
    }

    private fun mkLambdaName(functionName: String, source: ParsedSource): String {
        return source.getJvmClassName() + '$' + functionName
    }

    private fun findLambdas(source: ParsedSource): Map<String, Lambda> {
        val result = mutableMapOf<String, Lambda>()
        source.code.forEach {
            val def = (it as FunctionDefinition)
            result.putAll(findLambdas(mkLambdaName(def.name, source), def.body))
        }
        return result
    }

    private fun findLambdas(name: String, body: Expression): Map<String, Lambda> {
        var counter = 1
        val result = mutableMapOf<String, Lambda>()
        if (body.atoms.first() == Predefined.RUN_SEQ) {
            body.atoms.drop(1).forEach {
                result.putAll(findLambdas(name, it as Expression))
            }
        }
        body.atoms.forEach {
            when (it) {
                is Lambda -> {
                    val lambdaName = "$name$${counter++}"
                    result[lambdaName] = it
                    result.putAll(findLambdas(lambdaName, it.body))
                }

                is Expression -> {
                    result.putAll(findLambdas(name, it))
                }
                else -> { }
            }
        }
        return result
    }
}