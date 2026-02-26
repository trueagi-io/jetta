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
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import net.singularity.jetta.compiler.backend.registerExternals
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.logger.LogConfig
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import java.io.File
import kotlin.io.path.Path

class Compiler(
    val files: List<String>,
    val outputDir: String,
    val runtime: JettaRuntime = DefaultRuntime(),
    val logLevel: LogLevel = LogLevel.DEBUG
) {


    init {
        LogConfig.level = logLevel
    }

    fun compile(): Int {
        val sources = files.map {
            Source(it, File(it).readText())
        }
        val (success, messages) = compileMultipleSources(sources)
        val renderer = DefaultMessageRenderer()
        messages.forEach {
            println(renderer.render(it))
        }
        return if (success) 0 else 1
    }

    private fun MessageCollector.containsErrors(): Boolean =
        list().find { it.level == MessageLevel.ERROR } != null

    private fun addSystemFunctions(context: Context) {
        registerExternals(context)
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
            println("Compiling ${source.filename}")
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

        val space = context.getSpace()
        resolved.forEach {
            val programName = it.getJvmClassName().substringAfterLast('/')
            SpaceDirectorySerializer.save(space, Path(outputDir), programName)
        }

        resolved.forEach {
            val generator = Generator(generateMain = true)
            val compiled = generator.generate(it)
            compiled.forEach(::writeResult)
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