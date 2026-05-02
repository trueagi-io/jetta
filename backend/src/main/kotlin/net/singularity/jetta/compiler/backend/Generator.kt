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
        // The simple class name doubles as the module's space name: every match call site
        // generated below bakes this string in via LDC so the runtime registry can route
        // the call to the right space.
        val moduleSpaceName = className.substringAfterLast('/')
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            null,
            Type.getInternalName(Object::class.java),
            null
        )
        cw.visitSource(source.filename, null)
        val result = findLambdas(source).toList().sortedBy {
            val ind = it.first.indexOf('$')
            if (ind >= 0) it.first.substring(ind + 1).toInt() else 0
        }.reversed().map { (name, lambda) ->
            lambda.resolvedClassName = name
            val lambdaGenerator = LambdaGenerator(name, lambda, moduleSpaceName)
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
                    if (node.name == FunctionRewriter.MAIN) {
                        mv.visitLdcInsn(moduleSpaceName)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "net/singularity/jetta/runtime/JettaProgram",
                            "init",
                            "(Ljava/lang/String;)V",
                            false
                        )
                    }
                    FunctionGenerator(mv, node, true, null, moduleSpaceName).generate()
                    if (generateMain && node.name == FunctionRewriter.MAIN) {
                        val mainDesc = node.getJvmDescriptor()
                        val mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                            "main",
                            "([Ljava/lang/String;)V",
                            null,
                            null
                        )
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "__main", mainDesc, false)
                        if (!mainDesc.endsWith("V")) {
                            mv.visitInsn(Opcodes.POP)
                        }
                        mv.visitInsn(Opcodes.RETURN)
                        mv.visitMaxs(1, 1)
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
                is Expression -> findLambdas(name, body, result)
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
                if (body.atoms.isEmpty()) {
                    return acc
                }
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