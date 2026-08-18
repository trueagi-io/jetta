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
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ImportResolutionPass
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LetRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import net.singularity.jetta.compiler.backend.registerExternals
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.formatter.TextIrFormatter
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.logger.LogConfig
import net.singularity.jetta.compiler.storage.DeepCopyStrategy
import net.singularity.jetta.compiler.storage.SpaceDigest
import net.singularity.jetta.compiler.storage.StorageStrategy
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceImpl
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path

class Compiler(
    val files: List<String>,
    val outputDir: String,
    val runtime: JettaRuntime = DefaultRuntime(),
    val logLevel: LogLevel = LogLevel.DEBUG,
    val dumpIr: Boolean = false,
    val storageStrategy: StorageStrategy = DeepCopyStrategy,
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
        val context = Context(messageCollector, runtime.mapImpl, runtime.flatMapImpl, SpaceImpl())
        addSystemFunctions(context)
        val parser = createParserFacade()
        val cache = ModuleCompilationCache()
        val importPass = ImportResolutionPass(parser, cache, messageCollector)

        // Phase 1: parse user-supplied sources and resolve their imports. The pass leaves
        // each user source with the import! Runs removed and fills the cache with every
        // transitively-imported module ready for the same downstream pipeline.
        val userParsed = sources.map { source ->
            println("Compiling ${source.filename}")
            val raw = parser.parse(source, messageCollector)
            importPass.resolve(raw, Paths.get(source.filename))
        }

        // Phase 2: build a deterministic compilation queue. Imported modules are placed
        // BEFORE user sources so their function definitions are registered with the shared
        // Context (via addExternalFunctions) before any user source's resolver runs and
        // tries to look those symbols up.
        //
        // That argument applies BETWEEN imported modules just as much: a module whose body calls
        // an imported function must be resolved after the module that defines it, or the call
        // resolves to nothing and is emitted as inert data. Sorting the queue by path alone got
        // that right only by luck of the filename — f1_moduleA came before f1_moduleC, so
        // moduleA's `(g (+ 1 $x))` was left unresolved and `(f 2)` answered `(g 3)` instead of
        // 103 (f1_imports :65). Order by the import graph, tie-breaking on canonical path so
        // build outputs stay reproducible across machines.
        val allSources: List<ParsedSource> = dependencyOrder(cache) + userParsed

        // Run the rewriter chain per-source, wiring a fresh ownAtomsCollector for each so
        // we can serialize per-module spaces below. The shared `context.getSpace()` still
        // receives every atom — the resolver and pattern indexer rely on the merged view.
        val atomsBySource = mutableMapOf<ParsedSource, MutableList<Expression>>()
        val parsed = allSources.map { source ->
            val collector = mutableListOf<Expression>()
            atomsBySource[source] = collector
            val rewriter = CompositeRewriter()
            rewriter.add {
                FunctionRewriter(
                    messageCollector, context.getSpace(), collector,
                    isReducibleName = { context.resolve(it) != null }
                )
            }
            rewriter.add { LetRewriter() }
            rewriter.add { LambdaRewriter(messageCollector) }
            val result = rewriter.rewrite(source)
            context.addExternalFunctions(result)
            result
        }

        if (messageCollector.containsErrors()) {
            return false to messageCollector.list()
        }
        val resolved = parsed.map { context.resolve(it) }

        if (dumpIr) {
            dumpIrFiles(resolved)
        }

        // Phase 3: per-module storage via the strategy. Each compiled source — both
        // user entries and imported modules — gets its own `.jtsf` whose effective space
        // is computed by the strategy (DeepCopy: own atoms ∪ transitive &self-imports).
        //
        // Keying convention for the strategy: pre-rewrite ParsedSource instances. That's
        // what `cache.resolved` and `userParsed` already use, and what `atomsBySource`
        // was populated with. Lookup by resolved-source happens via the allSources↔resolved
        // index pairing established below.
        val importsBySource: Map<ParsedSource, Set<Path>> = run {
            val byCanonical: Map<Path, ParsedSource> = buildMap {
                putAll(cache.resolved)
                userParsed.forEach { put(canonicalPath(it), it) }
            }
            cache.imports.entries.mapNotNull { (importerPath, targets) ->
                val importer = byCanonical[importerPath] ?: return@mapNotNull null
                importer to targets.toSet()
            }.toMap()
        }
        val spaces = storageStrategy.computeSpaces(
            userSources = userParsed,
            cache = cache,
            atomsBySource = atomsBySource,
            importsBySource = importsBySource,
        )

        // resolved[i] corresponds to allSources[i]. The strategy keys spaces by the
        // pre-rewrite ParsedSource (allSources[i]); save under the post-resolver
        // source's class name to match the bytecode that will look it up at runtime.
        // The space fingerprint is computed once per module here and written to BOTH the
        // manifest and (below) the compiled class, so JettaProgram.init can verify the
        // artifacts loaded at runtime are the ones this program was compiled against.
        val fingerprints = mutableMapOf<String, SpaceDigest.Fingerprint>()
        // D2.3: per-program set of `(: f …)`-declared symbol names, read from the module's space
        // facts. Threaded to Generator so only declared functions get the eval-time type-check
        // prologue (untyped hot code stays uninstrumented). A `:` fact head is `Special`/`Symbol` ":".
        val declaredTypeNamesByProgram = mutableMapOf<String, Set<String>>()
        allSources.forEachIndexed { i, preRewriteSource ->
            val resolvedSource = resolved[i]
            val programName = resolvedSource.getJvmClassName().substringAfterLast('/')
            val space = (spaces[preRewriteSource] as? SpaceImpl) ?: SpaceImpl()
            val fingerprint = SpaceDigest.of(space.getAtoms())
            fingerprints[programName] = fingerprint
            declaredTypeNamesByProgram[programName] = Generator.declaredTypeNamesOf(space.getAtoms())
            val ext = storageStrategy.manifestExtensionFor(preRewriteSource, cache, importsBySource)
            SpaceDirectorySerializer.save(
                space = space,
                directory = Path(outputDir),
                programName = programName,
                manifestExtension = ext,
                strategyKind = storageStrategy.kind,
                atomCount = fingerprint.atomCount,
                contentHash = fingerprint.contentHash,
            )
        }

        // Linker table for variable-head dispatch in the compiled binary (P1). Written once
        // per program as `<program>.jctx` beside its `.class`; loaded by JettaProgram.init so
        // JettaCallSite can link `($f x)` (with `$f` naming a user fn) against the compiled
        // method instead of leaving the application inert. The table is context-global (owner
        // disambiguates), so every program's `.jctx` carries the full set — cheap and lets a
        // program dispatch to any resolved function.
        val linkerTableText = renderLinkerTable(context.linkerTable())

        resolved.forEach {
            // autoTable = true: AOT is a closed world (rules fixed at compile), so memoizing
            // pure/deterministic recursive functions is sound without cache invalidation.
            val programName = it.getJvmClassName().substringAfterLast('/')
            val fingerprint = fingerprints[programName]
            val generator = Generator(
                generateMain = true,
                autoTable = true,
                spaceAtomCount = fingerprint?.atomCount,
                spaceContentHash = fingerprint?.contentHash,
                declaredTypeNames = declaredTypeNamesByProgram[programName] ?: emptySet(),
            )
            val compiled = generator.generate(it)
            compiled.forEach(::writeResult)
            writeLinkerTable(programName, linkerTableText)
        }
        return true to messageCollector.list()
    }

    private fun canonicalPath(source: ParsedSource): Path =
        Paths.get(source.filename).toAbsolutePath().normalize()

    /**
     * The imported modules in dependency order — every module after the ones it imports.
     *
     * [ModuleCompilationCache.imports] already holds the edges (importer -> imported), recorded
     * for every `import!` regardless of its target space, so this is a depth-first post-order walk
     * over that graph. Roots and each node's children are visited in canonical-path order, which
     * keeps the result reproducible and reduces to the old path sort when nothing imports anything.
     * A cycle cannot form — [ImportResolutionPass] rejects one — but the `visiting` guard keeps
     * this total anyway rather than recursing forever if that ever changes.
     */
    private fun dependencyOrder(cache: ModuleCompilationCache): List<ParsedSource> {
        val ordered = mutableListOf<ParsedSource>()
        val done = mutableSetOf<Path>()
        val visiting = mutableSetOf<Path>()

        fun visit(path: Path) {
            if (path in done || !visiting.add(path)) return
            cache.imports[path]?.sortedBy { it.toString() }?.forEach { visit(it) }
            visiting.remove(path)
            done.add(path)
            cache.resolved[path]?.let { ordered.add(it) }
        }

        cache.resolved.keys.sortedBy { it.toString() }.forEach { visit(it) }
        return ordered
    }

    private fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    /**
     * Serialize the linker table as tab-separated lines `name\towner\tdescriptor\tmultivalued`
     * (one function per line). A plain text format keeps `.jctx` diffable and trivial to parse
     * at runtime without pulling a serialization dependency into the runtime module.
     */
    private fun renderLinkerTable(entries: List<Context.LinkerSymbol>): String =
        entries.joinToString("\n") { "${it.name}\t${it.owner}\t${it.descriptor}\t${it.multivalued}" }

    private fun writeLinkerTable(programName: String, text: String) {
        val file = File(outputDir + File.separator + "$programName.jctx")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText(text)
    }

    fun writeResult(result: CompilationResult) {
        val file = File(outputDir + File.separator + "${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }

    private fun dumpIrFiles(sources: List<ParsedSource>) {
        val formatter = TextIrFormatter()
        sources.forEach { source ->
            val irText = formatter.format(source)
            val baseName = source.filename
                .substringAfterLast(File.separatorChar)
                .substringBeforeLast('.')
            val irFile = File(outputDir + File.separator + "$baseName.jir")
            if (!irFile.parentFile.exists()) irFile.parentFile.mkdirs()
            irFile.writeText(irText)
            println("IR dumped: ${irFile.path}")
        }
    }
}
