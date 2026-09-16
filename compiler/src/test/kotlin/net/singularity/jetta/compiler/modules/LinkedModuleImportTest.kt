package net.singularity.jetta.compiler.modules

import net.singularity.jetta.compiler.Compiler
import net.singularity.jetta.compiler.frontend.resolve.ModuleInterface
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModuleResolver
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.runtime.space.ArtifactSource
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.ManifestSerializer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Importing a module that is ALREADY COMPILED must produce the same program as importing its
 * source. The module's interface (`.jctx`) and space (`.jtsf`) stand in for its source, so the
 * oracle is bytecode identity: whatever the resolver, the multivalued lift and codegen concluded
 * from reading the source, they must conclude from reading the artifact.
 */
class LinkedModuleImportTest {

    /** A module worth linking: a declared scalar, a multivalued function, and a template
     *  parameter — the three things the interface has to carry that a descriptor cannot. */
    private val moduleSource = """
        (: twice (-> Int Int))
        (= (twice ${'$'}x) (* 2 ${'$'}x))

        (: pick (-> Int Int))
        (= (pick ${'$'}x) ${'$'}x)
        (= (pick ${'$'}x) (+ ${'$'}x 100))

        (: applyTwice (-> Atom Int Int))
        (= (applyTwice ${'$'}f ${'$'}x) (${'$'}f (${'$'}f ${'$'}x)))
    """.trimIndent()

    private val clientSource = """
        !(import! &self libmod)
        !(println! (twice 21))
    """.trimIndent()

    @Test
    fun `a linked import yields the same program bytes as a source import`(
        @TempDir moduleDir: Path,
        @TempDir artifacts: Path,
        @TempDir sourceBuild: Path,
        @TempDir linkedBuild: Path,
    ) {
        // 1. Compile the module on its own — these are the artifacts a build would ship.
        val moduleFile = File(moduleDir.toFile(), "libmod.metta")
        moduleFile.writeText(moduleSource)
        compile(listOf(moduleFile.absolutePath), artifacts)
        assertTrue(Files.isRegularFile(artifacts.resolve("libmod.jctx")), "module interface written")
        assertTrue(Files.isRegularFile(artifacts.resolve("libmod.jtsf")), "module space written")

        // 2. The source path: client and module source side by side, as today.
        val clientFile = File(moduleDir.toFile(), "client.metta")
        clientFile.writeText(clientSource)
        compile(listOf(clientFile.absolutePath), sourceBuild)

        // 3. The linked path: the SAME client file, with the module's source taken away and its
        //    artifacts on the module path. The client's absolute path is baked into its class as
        //    the source-file constant, so compiling a copy elsewhere would differ in bytes for a
        //    reason that has nothing to do with the import.
        assertTrue(moduleFile.delete(), "module source removed so only the artifacts can answer")
        compile(
            listOf(clientFile.absolutePath),
            linkedBuild,
            ArtifactModuleResolver(listOf(artifacts)),
        )

        assertContentEquals(
            Files.readAllBytes(sourceBuild.resolve("client.class")),
            Files.readAllBytes(linkedBuild.resolve("client.class")),
            "the client's bytecode must not depend on how the module was imported",
        )

        // The module itself is not rebuilt — that is the whole point of linking.
        assertTrue(Files.isRegularFile(sourceBuild.resolve("libmod.class")), "source import compiles the module")
        assertTrue(!Files.isRegularFile(linkedBuild.resolve("libmod.class")), "linked import must not recompile it")
    }

    @Test
    fun `a linked module's interface reaches the program's own table unchanged`(
        @TempDir moduleDir: Path,
        @TempDir artifacts: Path,
        @TempDir clientOnly: Path,
        @TempDir linkedBuild: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), artifacts)
        File(clientOnly.toFile(), "client.metta").writeText(clientSource)
        compile(
            listOf(File(clientOnly.toFile(), "client.metta").absolutePath),
            linkedBuild,
            ArtifactModuleResolver(listOf(artifacts)),
        )

        val shipped = ModuleInterface.parseAll(Files.readString(artifacts.resolve("libmod.jctx")))
            .associateBy { it.name }
        val inProgram = ModuleInterface.parseAll(Files.readString(linkedBuild.resolve("client.jctx")))
            .associateBy { it.name }

        // Passed through, not rebuilt: a call linked via the program's table and one linked via
        // the module's own table have to mean the same thing.
        listOf("twice", "pick", "applyTwice").forEach { name ->
            assertEquals(shipped[name], inProgram[name], "entry for $name")
        }
        // The properties the artifact exists to carry survived the round trip.
        assertEquals(true, shipped["pick"]?.multivalued, "pick is the multivalued one")
        assertEquals(setOf(0), shipped["applyTwice"]?.templateAtomParams, "its Atom parameter is a template")
    }

    @Test
    fun `the manifest lists a linked module so the runtime can load its space`(
        @TempDir moduleDir: Path,
        @TempDir artifacts: Path,
        @TempDir clientOnly: Path,
        @TempDir linkedBuild: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), artifacts)
        File(clientOnly.toFile(), "client.metta").writeText(clientSource)
        compile(
            listOf(File(clientOnly.toFile(), "client.metta").absolutePath),
            linkedBuild,
            ArtifactModuleResolver(listOf(artifacts)),
        )

        val manifest = ManifestSerializer.load(linkedBuild.resolve("client.manifest.json"))
        val ext = manifest.extension as ManifestExtension.DeepCopy
        // `stdlib` alongside it: every program imports the standard library now.
        assertEquals(listOf("libmod", "stdlib"), ext.loadModules.map { it.spaceId })
    }

    /** No artifacts on the module path — the import falls back to the module's source. */
    @Test
    fun `an unresolvable module name still compiles from source`(
        @TempDir moduleDir: Path,
        @TempDir empty: Path,
        @TempDir out: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        File(moduleDir.toFile(), "client.metta").writeText(clientSource)
        compile(
            listOf(File(moduleDir.toFile(), "client.metta").absolutePath),
            out,
            ArtifactModuleResolver(listOf(empty)),
        )
        assertTrue(Files.isRegularFile(out.resolve("libmod.class")), "fell back to compiling the source")
    }

    /**
     * A linked program RUNS: the module's methods are invoked from its shipped class and its atoms
     * are copied into `&self` by the `import!` the program still executes.
     */
    @Test
    fun `a linked program runs against the module's shipped artifacts`(
        @TempDir moduleDir: Path,
        @TempDir artifacts: Path,
        @TempDir clientOnly: Path,
        @TempDir linkedBuild: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), artifacts)
        File(clientOnly.toFile(), "client.metta").writeText(clientSource)
        compile(
            listOf(File(clientOnly.toFile(), "client.metta").absolutePath),
            linkedBuild,
            ArtifactModuleResolver(listOf(artifacts)),
        )

        // The module's class and space come from where it was built; the program's own artifacts
        // from where it was built. Both on the path is what a shipped module looks like before
        // the runtime learns to read one out of the compiler's jar.
        val classpath = listOf(
            linkedBuild.toAbsolutePath().toString(),
            artifacts.toAbsolutePath().toString(),
            System.getProperty("java.class.path"),
        ).joinToString(File.pathSeparator)
        listOf("libmod.jtsf", "libmod.manifest.json").forEach {
            Files.copy(artifacts.resolve(it), linkedBuild.resolve(it))
        }
        val proc = ProcessBuilder(
            "java", "-Djetta.dataDir=${linkedBuild.toAbsolutePath()}", "-cp", classpath, "client"
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        assertEquals(0, rc, "java exited non-zero. output:\n$output")
        assertTrue(output.contains("42"), "expected the module's method to answer 42, got:\n$output")
    }

    /**
     * A module SHIPPED with the compiler is a fallback, not an override: a program carrying the
     * module's own source compiles that. This is the compile-time half of an agreement with the
     * runtime, which looks in the program's artifact directory before the compiler's jar — if the
     * two ends disagreed, a program would link one module's interface and load another's atoms.
     */
    @Test
    fun `a shipped module loses to the module's own source`(
        @TempDir moduleDir: Path,
        @TempDir shipped: Path,
        @TempDir out: Path,
    ) {
        // Build the "shipped" copy from a DIFFERENT module body, so which one was used is visible.
        File(moduleDir.toFile(), "libmod.metta").writeText(
            moduleSource.replace("(* 2 ${'$'}x)", "(* 3 ${'$'}x)")
        )
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), shipped)

        // Now the program, with the module's ORIGINAL source beside it.
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        File(moduleDir.toFile(), "client.metta").writeText(clientSource)
        compileWith(
            listOf(File(moduleDir.toFile(), "client.metta").absolutePath),
            out,
            ShippedModuleResolver(ArtifactSource.Directory(shipped)),
        )

        assertTrue(
            Files.isRegularFile(out.resolve("libmod.class")),
            "the module's own source must be compiled rather than the shipped build linked",
        )
    }

    /** With no source to compile, the shipped module is linked. */
    @Test
    fun `a shipped module is linked when the program has no source for it`(
        @TempDir moduleDir: Path,
        @TempDir shipped: Path,
        @TempDir clientOnly: Path,
        @TempDir out: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), shipped)

        File(clientOnly.toFile(), "client.metta").writeText(clientSource)
        compileWith(
            listOf(File(clientOnly.toFile(), "client.metta").absolutePath),
            out,
            ShippedModuleResolver(ArtifactSource.Directory(shipped)),
        )

        assertTrue(!Files.isRegularFile(out.resolve("libmod.class")), "linked, not recompiled")
        val manifest = ManifestSerializer.load(out.resolve("client.manifest.json"))
        assertEquals(
            listOf("libmod", "stdlib"),
            (manifest.extension as ManifestExtension.DeepCopy).loadModules.map { it.spaceId },
        )
    }

    /** `--module-path` is explicit intent and DOES override a module's source. */
    @Test
    fun `module-path artifacts override the module's own source`(
        @TempDir moduleDir: Path,
        @TempDir artifacts: Path,
        @TempDir out: Path,
    ) {
        File(moduleDir.toFile(), "libmod.metta").writeText(moduleSource)
        compile(listOf(File(moduleDir.toFile(), "libmod.metta").absolutePath), artifacts)

        File(moduleDir.toFile(), "client.metta").writeText(clientSource)
        compileWith(
            listOf(File(moduleDir.toFile(), "client.metta").absolutePath),
            out,
            ArtifactModuleResolver(listOf(artifacts)),
        )

        assertTrue(
            !Files.isRegularFile(out.resolve("libmod.class")),
            "the source is right there, but the artifacts were asked for explicitly",
        )
    }

    private fun compileWith(
        files: List<String>,
        out: Path,
        resolver: PrecompiledModuleResolver,
    ) {
        val compiler = Compiler(
            files = files,
            outputDir = out.toAbsolutePath().toString(),
            logLevel = LogLevel.ERROR,
            precompiledModules = resolver,
        )
        assertEquals(0, compiler.compile(), "compiler returned non-zero for $files")
    }

    private fun compile(
        files: List<String>,
        out: Path,
        resolver: ArtifactModuleResolver? = null,
    ) {
        val compiler = if (resolver == null) {
            Compiler(files = files, outputDir = out.toAbsolutePath().toString(), logLevel = LogLevel.ERROR)
        } else {
            Compiler(
                files = files,
                outputDir = out.toAbsolutePath().toString(),
                logLevel = LogLevel.ERROR,
                precompiledModules = resolver,
            )
        }
        assertEquals(0, compiler.compile(), "compiler returned non-zero for $files")
    }
}
