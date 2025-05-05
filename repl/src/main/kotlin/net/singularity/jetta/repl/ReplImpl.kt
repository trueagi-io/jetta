package net.singularity.jetta.repl

import net.singularity.jetta.compiler.backend.CompilationResult
import net.singularity.jetta.compiler.backend.DefaultRuntime
import net.singularity.jetta.compiler.backend.Generator
import net.singularity.jetta.compiler.backend.JettaRuntime
import net.singularity.jetta.compiler.frontend.*
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.frontend.rewrite.RewriteException
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import java.io.File

class ReplImpl(runtime: JettaRuntime = DefaultRuntime(), logLevel: LogLevel = LogLevel.DEBUG) : Repl {
    private var counter = 0
    private val classLoader = ByteArrayReplClassLoader()

    private val messageCollector = MessageCollector()

    private val context = Context(messageCollector, runtime.mapImpl, runtime.flatMapImpl, logLevel)

    override fun eval(code: String): EvalResult {
        context.clearMessages()
        val filename = createFilename()
        val result = try {
            compile(filename, code)
        } catch (e: Exception) {
            return EvalResult(null, listOf(e.stackTraceToString()), false)
        }
        val renderer = createMessageRenderer()
        val messages = messageCollector.list().map { renderer.render(it) }
        if (messageCollector.hasErrors()) {
            return EvalResult(null, messages, false)
        }
        result.forEach(classLoader::add)
        val main = result.find { it.className == filename }!!
        val clazz = classLoader.loadClass(main.className)
        try {
            val method = clazz.getMethod(FunctionRewriter.MAIN)
            return EvalResult(method.invoke(null), messages, true)
        } catch (_: NoSuchMethodException) { }
        return EvalResult(null, messages, true)
    }

    private fun compile(filename: String, code: String): List<CompilationResult> {
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = try {
            rewriter.rewrite(parsed).let { context.resolve(it) }
        } catch (_: RewriteException) {
            return listOf()
        }
        result.let {
            if (messageCollector.list().isNotEmpty()) return listOf()
            val generator = Generator()
            return generator.generate(it)
        }
    }

    private fun createFilename(): String = "Line_${++counter}"

    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    private fun createMessageRenderer(): MessageRenderer = DefaultMessageRenderer()
}