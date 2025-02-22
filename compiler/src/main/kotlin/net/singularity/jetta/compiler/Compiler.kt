package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.backend.CompilationResult
import net.singularity.jetta.compiler.backend.DefaultRuntime
import net.singularity.jetta.compiler.backend.Generator
import net.singularity.jetta.compiler.backend.JettaRuntime
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import java.io.File

class Compiler(val files: List<String>, val outputDir: String, val runtime: JettaRuntime = DefaultRuntime()) {
    fun compile() {
        files.forEach {
            print("Compiling $it")
            val code = File(it).readText()
            compile(it, code)
            println(" OK")
        }
    }

    private fun compile(filename: String, code: String): Pair<List<CompilationResult>, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, runtime.mapImpl, runtime.flatMapImpl)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add(FunctionRewriter(messageCollector))
        rewriter.add(LambdaRewriter(messageCollector))
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = rewriter.rewrite(parsed).let { context.resolveRecursively(it) }
        val generator = Generator()
        val compiled = generator.generate(result)
        compiled.forEach {
            writeResult(it)
        }
        return compiled to messageCollector
    }

    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    fun writeResult(result: CompilationResult) {
        val file = File(outputDir + File.separator + "${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }
}