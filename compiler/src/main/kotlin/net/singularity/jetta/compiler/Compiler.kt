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
import net.singularity.jetta.compiler.modules.ShippedModuleResolver
import net.singularity.jetta.compiler.modules.CompositeModuleResolver
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.ModuleInterface
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ImportResolutionPass
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LetRewriter
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModuleResolver
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
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.ModuleLoad
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
    /** Where an `import!` looks for an already-compiled module before reading its source. */
    val precompiledModules: PrecompiledModuleResolver = PrecompiledModuleResolver.NONE,
    /**
     * Whether every program gets `(import! &self stdlib)` prepended, as the reference interpreter
     * loads its standard library for every program it runs.
     *
     * DEFAULT OFF, and the default is the only thing here still under discussion. Turning it on
     * costs three of the 22 reference topic tests today — `d1_gadt`, `d5_auto_types` (a
     * StackOverflowError) and `f1_imports` — because a program's reflective queries start seeing
     * the library's own declarations: `(: Error (-> Atom Atom ErrorType))` gives an ill-typed
     * expression a type where the reference answers the empty set, and `(: = (-> $t $t
     * %Undefined%))` reaches a rule's result. The reference passes all three WITH its library
     * loaded, so these are gaps of ours that the import exposes rather than a reason not to do it.
     * Flipping this to `true` is the whole change, once they are fixed.
     */
    val autoImportStdlib: Boolean = false,
) {
    /**
     * The resolver an import actually consults: what the caller configured, with the modules
     * SHIPPED in the compiler's jar always behind it. Appended rather than left to the caller
     * because the automatic stdlib import has to find the library whatever else is configured —
     * a caller passing its own resolver is narrowing where ITS modules come from, not asking for
     * a compiler without a standard library.
     */
    private val moduleResolver: PrecompiledModuleResolver =
        CompositeModuleResolver(listOf(precompiledModules, ShippedModuleResolver()))

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
        val importPass = ImportResolutionPass(parser, cache, messageCollector, moduleResolver)

        // Phase 1: parse user-supplied sources and resolve their imports. The pass leaves
        // each user source with the import! Runs removed and fills the cache with every
        // transitively-imported module ready for the same downstream pipeline.
        val userParsed = sources.map { source ->
            println("Compiling ${source.filename}")
            val raw = parser.parse(source, messageCollector)
            importPass.resolve(withAutomaticStdlibImport(raw), Paths.get(source.filename))
        }

        // Linked modules first: a module imported as ARTIFACTS contributes its interface to the
        // resolver and its atoms to the shared space, and nothing else — no rewriting, no
        // resolution, no codegen. Both halves have to be in place before the rewriter chain runs
        // below, because `FunctionRewriter`'s `isReducibleName` already asks the context whether a
        // head resolves, and the space is what the pattern indexer and a reflective `match &self`
        // read.
        cache.precompiled.values.forEach { module ->
            context.addLinkedModule(module.entries)
            module.atoms.forEach(context.getSpace()::add)
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
        val declaredArrowNamesByProgram = mutableMapOf<String, Set<String>>()
        allSources.forEachIndexed { i, preRewriteSource ->
            val resolvedSource = resolved[i]
            val programName = resolvedSource.getJvmClassName().substringAfterLast('/')
            val space = (spaces[preRewriteSource] as? SpaceImpl) ?: SpaceImpl()
            val fingerprint = SpaceDigest.of(space.getAtoms())
            fingerprints[programName] = fingerprint
            declaredTypeNamesByProgram[programName] = Generator.declaredTypeNamesOf(space.getAtoms())
            declaredArrowNamesByProgram[programName] = Generator.declaredArrowNamesOf(space.getAtoms())
            val ext = withPrecompiledModules(
                storageStrategy.manifestExtensionFor(preRewriteSource, cache, importsBySource),
                preRewriteSource,
                cache,
            )
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

        // The module interface, written once per program as `<program>.jctx` beside its
        // `.class`. Two readers: JettaProgram.init loads it so JettaCallSite can link `($f x)`
        // (with `$f` naming a user fn) against the compiled method instead of leaving the
        // application inert (P1), and the compiler reads it to call INTO an already-compiled
        // module without re-resolving its source. Context-global (owner disambiguates), so every
        // program's `.jctx` carries the full set — cheap and lets a program dispatch to any
        // resolved function. See Context.linkerTable / ModuleInterface.
        val linkerTableText = ModuleInterface.renderAll(context.linkerTable())

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
                declaredArrowNames = declaredArrowNamesByProgram[programName] ?: emptySet(),
            )
            val compiled = generator.generate(it)
            compiled.forEach(::writeResult)
            writeLinkerTable(programName, linkerTableText)
        }
        return true to messageCollector.list()
    }

    /**
     * Append the linked modules [source] imports to its manifest extension.
     *
     * The strategy computes `loadModules` by walking the import graph of PARSED sources, and a
     * precompiled module has none — it was never parsed here. But the runtime needs it listed for
     * the same reason a source-imported module is: `init` registers each listed module's space so
     * the `(import! …)` still in the program's body has something to copy from.
     */
    private fun withPrecompiledModules(
        extension: ManifestExtension,
        source: ParsedSource,
        cache: ModuleCompilationCache,
    ): ManifestExtension {
        val names = cache.precompiledImports[canonicalPath(source)].orEmpty()
        if (names.isEmpty()) return extension
        return when (extension) {
            is ManifestExtension.DeepCopy -> {
                val existing = extension.loadModules.map { it.spaceId }.toSet()
                val added = names.sorted()
                    .filterNot { it in existing }
                    .map { ModuleLoad(spaceId = it, jtsf = "$it.jtsf") }
                ManifestExtension.DeepCopy(loadModules = extension.loadModules + added)
            }
            is ManifestExtension.Alias -> extension
        }
    }

    /**
     * Prepend `(import! &self stdlib)` to a program the user asked to compile, which is how the
     * reference interpreter runs every program — its standard library is always loaded, and a
     * corpus file calling `if-error` or `match-types` expects it there without saying so.
     *
     * Prepended rather than merged in some other way because it must behave exactly like an
     * import the program wrote itself: the module is linked at compile time, and the surviving
     * Run copies the library's atoms into `&self` at the point it executes — before anything else
     * the program does, which is what makes a `match &self` over a library declaration work.
     *
     * Only ENTRY programs get it. A module reached through an `import!` does not, so a library's
     * atoms are not copied once per module space in a program that imports three of them.
     * A program that already imports the library keeps its own import, at its own position.
     */
    private fun withAutomaticStdlibImport(source: ParsedSource): ParsedSource {
        if (!autoImportStdlib) return source
        if (source.code.any { it.isImportOf(STDLIB_MODULE) }) return source
        val directive = Run(
            Expression(listOf(Symbol("import!"), Symbol("&self"), Symbol(STDLIB_MODULE))),
            position = null,
        )
        return ParsedSource(source.filename, listOf(directive) + source.code)
    }

    private fun Atom.isImportOf(moduleName: String): Boolean {
        val atoms = (this as? Run)?.expression?.atoms ?: return false
        if (atoms.size < 3) return false
        return (atoms[0] as? Symbol)?.name == "import!" && (atoms[2] as? Symbol)?.name == moduleName
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

    private companion object {
        /** The module name the vendored standard library is compiled and shipped under. */
        const val STDLIB_MODULE = "stdlib"
    }

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
