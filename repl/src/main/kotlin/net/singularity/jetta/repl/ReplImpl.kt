package net.singularity.jetta.repl

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.backend.CompilationResult
import net.singularity.jetta.compiler.backend.Generator
import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.frontend.rewrite.RewriteException
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import java.io.File

class ReplImpl : Repl {
    private var counter = 0
    private val classLoader = ByteArrayReplClassLoader()

    private val messageCollector = MessageCollector()
    private val context = Context(messageCollector)

    override fun eval(code: String): EvalResult {
        val filename = createFilename()
        val result = compile(filename, code)
        val renderer = createMessageRenderer()
        val messages = messageCollector.list().map { renderer.render(it) }
        if (messageCollector.hasErrors()) {
            return EvalResult(null, messages, false)
        }
        result.forEach(classLoader::add)
        val main = result.find { it.className == filename }!!
        println("$filename.class")
        File("/tmp/$filename.class").writeBytes(main.bytecode)
        val clazz = classLoader.loadClass(main.className)
        clazz.methods.forEach {
            println(it.name)
        }
        try {
            val method = clazz.getMethod(FunctionRewriter.MAIN)
            return EvalResult(method.invoke(null), messages, true)
        } catch (_: NoSuchMethodException) { }
        return EvalResult(null, messages, true)
    }

    private fun compile(filename: String, code: String): List<CompilationResult> {
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add(FunctionRewriter(messageCollector))
        rewriter.add(LambdaRewriter(messageCollector))
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = try {
            rewriter.rewrite(parsed)
        } catch (_: RewriteException) {
            return listOf()
        }
        context.resolve(result)
        if (messageCollector.list().isNotEmpty()) return listOf()
        val generator = Generator()
        return generator.generate(result)
    }

    private fun createFilename(): String = "Line_${++counter}"

    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    private fun createMessageRenderer(): MessageRenderer = object : MessageRenderer {
        override fun render(message: Message): String {
            return message.toString()
        }
    }
}