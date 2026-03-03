package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.formatter.TextIrFormatter
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.logger.LogConfig
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.compiler.logger.Logger
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import net.singularity.jetta.runtime.JettaProgram
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import java.io.File
import kotlin.io.path.Path

abstract class GeneratorTestBase {
    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    init {
        LogConfig.level = LogLevel.TRACE
    }

    val log = Logger.getLogger(GeneratorTestBase::class.java)

    protected fun compile(
        filename: String, code: String,
        mapImpl: JvmMethod? = null,
        flatMapImpl: JvmMethod? = null,
        init: (Context) -> Unit = {}
    ): Pair<List<CompilationResult>, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, mapImpl, flatMapImpl)
        init(context)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = rewriter.rewrite(parsed).let { context.resolveRecursively(it) }
        log.trace {
            val formatter = TextIrFormatter()
            "\n============================================================================\n" +
                    formatter.format(result) +
                    "============================================================================"
        }
        // Save space and initialize JettaProgram so generated __main can use it
        val outputDir = Path("/tmp/metta")
        val programName = filename.substringBeforeLast('.')
        SpaceDirectorySerializer.save(context.getSpace(), outputDir, programName = programName)
        JettaProgram.setDataDir(outputDir)

        val generator = Generator()
        val compiled = generator.generate(result)
        compiled.forEach {
            log.debug { "Writing " + it.className }
            writeResult(it)
        }
        return compiled to messageCollector
    }

    protected fun compileMultiple(
        vararg sources: Source,
        mapImpl: JvmMethod? = null,
        flatMapImpl: JvmMethod? = null
    ): Pair<List<CompilationResult>, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, mapImpl, flatMapImpl)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }

        val parsed = sources.map {
            val parsed = parser.parse(it, messageCollector)
            val result = rewriter.rewrite(parsed)
            context.addExternalFunctions(result)
            result
        }

        val compiled = parsed.map { context.resolve(it) }.flatMap {
            val generator = Generator()
            generator.generate(it)
        }

        compiled.forEach {
            log.trace { "Writing " + it.className }
            writeResult(it)
        }
        return compiled to messageCollector
    }

    protected fun writeResult(result: CompilationResult) {
        val file = File("/tmp/metta/${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }

    protected fun List<CompilationResult>.toMap() = this.associate { it.className to it.bytecode }

    protected fun CompilationResult.getClass(): Class<*> = listOf(this).toMap().toClasses()[this.className]!!
}