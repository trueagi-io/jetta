package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.backend.CompilationResult
import net.singularity.jetta.compiler.backend.DefaultRuntime
import net.singularity.jetta.compiler.backend.Generator
import net.singularity.jetta.compiler.backend.JettaRuntime
import net.singularity.jetta.compiler.frontend.DefaultMessageRenderer
import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import net.singularity.jetta.runtime.IO
import org.objectweb.asm.Type
import java.io.File

class Compiler(val files: List<String>, val outputDir: String, val runtime: JettaRuntime = DefaultRuntime()) {
    fun compile(): Int {
        val sources = files.map {
            Source(it, File(it).readText())
        }
        val (success, messages) = compileMultipleSources(sources)
        val renderer = DefaultMessageRenderer()
        messages.forEach {
            println(renderer.render(it))
        }
        return if (success) {
            0
        } else {
            1
        }
    }

    private fun MessageCollector.containsErrors(): Boolean =
        list().find { it.level == MessageLevel.ERROR } != null

    private fun addSystemFunctions(context: Context) {
        context.addSystemFunction(
            ResolvedSymbol(
                JvmMethod(
                    owner = Type.getInternalName(IO::class.java),
                    name = "println",
                    descriptor = "(Ljava/lang/Object;)V"
                ), null, false
            )
        )
    }

    fun compileMultipleSources(sources: List<Source>): Pair<Boolean, List<Message>> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, runtime.mapImpl, runtime.flatMapImpl)
        addSystemFunctions(context)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }

        val parsed = sources.map { source ->
            println("Compiling ${source.filename} ====== \n ${source.code} \n ======")
            val parsed = parser.parse(source, messageCollector)
            val result = rewriter.rewrite(parsed)
            context.addExternalFunctions(result)
            result
        }

        if (messageCollector.containsErrors()) {
            // do not generate classes if there is any error
            return false to messageCollector.list()
        }
        val resolved = parsed.map { context.resolve(it) }

        resolved.forEach {
            val generator = Generator(generateMain = true)
            val compiled = generator.generate(it)
            compiled.forEach {
                writeResult(it)
            }
        }
        return true to messageCollector.list()
    }

    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    fun writeResult(result: CompilationResult) {
        val file = File(outputDir + File.separator + "${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }
}