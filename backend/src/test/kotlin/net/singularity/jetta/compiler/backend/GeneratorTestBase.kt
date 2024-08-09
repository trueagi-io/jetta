package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.ByteArrayClassLoader
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import java.io.File

open class GeneratorTestBase {
    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    protected fun compile(filename: String, code: String): Pair<List<CompilationResult>, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add(FunctionRewriter(messageCollector))
        rewriter.add(LambdaRewriter(messageCollector))
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = rewriter.rewrite(parsed).let { context.resolve(it) }
        val generator = Generator()
        val compiled = generator.generate(result)
        compiled.forEach {
            println("Writing " + it.className)
            writeResult(it)
        }
        return compiled to messageCollector
    }

    protected fun writeResult(result: CompilationResult) {
        val file = File("/tmp/metta/${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }

    protected fun Map<String, ByteArray>.toClasses(): Map<String, Class<*>> {
        val loader = ByteArrayClassLoader(this)
        return mapValues { loader.loadClass(it.key) }
    }

    protected fun List<CompilationResult>.toMap() = this.associate { it.className to it.bytecode }

    protected fun CompilationResult.getClass(): Class<*> = listOf(this).toMap().toClasses()[this.className]!!

}