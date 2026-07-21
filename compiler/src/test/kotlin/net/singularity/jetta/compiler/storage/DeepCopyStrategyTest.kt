package net.singularity.jetta.compiler.storage

import net.singularity.jetta.compiler.Compiler
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.ManifestSerializer
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepCopyStrategyTest {

    @Test
    fun `main importing utils — each space holds only its own atoms, main lists utils as load`(@TempDir src: Path, @TempDir out: Path) {
        File(src.toFile(), "main.metta").writeText(
            """
            (Fact apple)
            !(import! &self utils)
            !(println "ok")
            """.trimIndent()
        )
        File(src.toFile(), "utils.metta").writeText(
            """
            (Fact banana)
            """.trimIndent()
        )

        compileSilently(listOf(File(src.toFile(), "main.metta").absolutePath), out)

        val mainSpace = SpaceDirectorySerializer.load(out, "main")
        val utilsSpace = SpaceDirectorySerializer.load(out, "utils")

        // Runtime-ordered import: each serialized space holds ONLY its own atoms. main's
        // space is not statically merged — the runtime `import!` copies banana into main's
        // live &self at execution time, which the serialized artifact does not capture.
        assertTrue(mainSpace.containsFact("apple"), "main should hold its own apple")
        assertTrue(!mainSpace.containsFact("banana"), "main must NOT statically hold banana")
        assertEquals(1, mainSpace.factCount(), "main has exactly its own atoms")

        // utils' space holds only its own atoms.
        assertTrue(utilsSpace.containsFact("banana"))
        assertEquals(1, utilsSpace.factCount())

        // The manifest still lists utils so init loads it under SpaceId.FromModule("utils"),
        // ready for the runtime `import!` to copy from.
        val mainManifest = ManifestSerializer.load(out.resolve("main.manifest.json"))
        val utilsManifest = ManifestSerializer.load(out.resolve("utils.manifest.json"))

        assertEquals(2, mainManifest.version)
        assertEquals("deep-copy", mainManifest.kind)
        assertEquals("main", mainManifest.spaceId)
        val mainExt = mainManifest.extension as ManifestExtension.DeepCopy
        assertEquals(listOf("utils"), mainExt.loadModules.map { it.spaceId })
        assertEquals(listOf("utils.jtsf"), mainExt.loadModules.map { it.jtsf })

        val utilsExt = utilsManifest.extension as ManifestExtension.DeepCopy
        assertEquals(emptyList(), utilsExt.loadModules)
    }

    @Test
    fun `runtime import copies utils' fact into main's live space in order`(@TempDir src: Path, @TempDir out: Path) {
        // End-to-end order check: a `match &self` BEFORE the import sees nothing; AFTER the
        // import it sees utils' fact. This is the behaviour the static merge could not model.
        File(src.toFile(), "mainord.metta").writeText(
            """
            !(println (collapse (match &self (Fact ${'$'}x) ${'$'}x)))
            !(import! &self utilord)
            !(println (collapse (match &self (Fact ${'$'}x) ${'$'}x)))
            """.trimIndent()
        )
        File(src.toFile(), "utilord.metta").writeText("(Fact banana)")

        compileSilently(listOf(File(src.toFile(), "mainord.metta").absolutePath), out)

        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-Djetta.dataDir=${out.toAbsolutePath()}", "-cp", classpath, "mainord")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        assertEquals(0, rc, "java exited non-zero. output:\n$output")
        val lines = output.trim().lines()
        assertEquals("()", lines.first(), "before import: &self has no Fact; got:\n$output")
        assertTrue(lines.last().contains("banana"), "after import: &self should hold banana; got:\n$output")
    }

    @Test
    fun `diamond — shared module's fact appears once in main`(@TempDir src: Path, @TempDir out: Path) {
        File(src.toFile(), "main.metta").writeText(
            """
            !(import! &self a)
            !(import! &self b)
            !(println "ok")
            """.trimIndent()
        )
        File(src.toFile(), "a.metta").writeText(
            """
            !(import! &self c)
            (Fact a-own)
            """.trimIndent()
        )
        File(src.toFile(), "b.metta").writeText(
            """
            !(import! &self c)
            (Fact b-own)
            """.trimIndent()
        )
        File(src.toFile(), "c.metta").writeText(
            """
            (Fact shared)
            """.trimIndent()
        )

        compileSilently(listOf(File(src.toFile(), "main.metta").absolutePath), out)

        // Own-only serialized spaces: main has no own facts; each module holds only its own.
        val mainSpace = SpaceDirectorySerializer.load(out, "main")
        assertEquals(0, mainSpace.factCount(), "main has no own facts (only imports)")

        val aSpace = SpaceDirectorySerializer.load(out, "a")
        assertTrue(aSpace.containsFact("a-own"))
        assertTrue(!aSpace.containsFact("shared"))
        assertTrue(!aSpace.containsFact("b-own"))

        val bSpace = SpaceDirectorySerializer.load(out, "b")
        assertTrue(bSpace.containsFact("b-own"))
        assertTrue(!bSpace.containsFact("shared"))
        assertTrue(!bSpace.containsFact("a-own"))

        // c's space holds only shared.
        val cSpace = SpaceDirectorySerializer.load(out, "c")
        assertEquals(1, cSpace.factCount())
        assertTrue(cSpace.containsFact("shared"))

        // main.manifest.json lists every transitively-reached module (loaded at init under
        // their own ids, ready for the runtime import). Diamond dedup at the module level —
        // `c` is listed once even though both a and b reach it.
        val ext = ManifestSerializer.load(out.resolve("main.manifest.json")).extension as ManifestExtension.DeepCopy
        val ids = ext.loadModules.map { it.spaceId }
        assertEquals(setOf("a", "b", "c"), ids.toSet())
        assertEquals(ids.size, ids.toSet().size, "each module listed exactly once")
    }

    @Test
    fun `cross-module function call — main runs and finds utils' function`(@TempDir src: Path, @TempDir out: Path) {
        File(src.toFile(), "mainx.metta").writeText(
            """
            !(import! &self utilsx)
            !(println (foo))
            """.trimIndent()
        )
        File(src.toFile(), "utilsx.metta").writeText(
            """
            (: foo (-> Int))
            (= (foo) 1)
            """.trimIndent()
        )

        compileSilently(listOf(File(src.toFile(), "mainx.metta").absolutePath), out)

        // Run via a fresh JVM with the test JVM's classpath plus the compile output
        // directory. java.class.path already carries runtime/frontend-api/backend, so
        // JettaProgram + SpaceImpl + the compiled mainx/utilsx classes resolve cleanly.
        // -Djetta.dataDir points the space loader at the artifacts dir (the subprocess cwd
        // is the test's working dir, not `out`), so init's fingerprint check finds them.
        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-Djetta.dataDir=${out.toAbsolutePath()}", "-cp", classpath, "mainx")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        assertEquals(0, rc, "java exited non-zero. output:\n$output")
        assertTrue(output.trim().endsWith("1"), "expected trailing '1' but got:\n$output")
    }

    private fun compileSilently(files: List<String>, out: Path) {
        val compiler = Compiler(
            files = files,
            outputDir = out.toAbsolutePath().toString(),
            logLevel = LogLevel.ERROR,
        )
        val rc = compiler.compile()
        assertEquals(0, rc, "compiler returned $rc")
    }

    private fun net.singularity.jetta.runtime.space.SpaceImpl.containsFact(symbol: String): Boolean =
        countFact(symbol) > 0

    private fun net.singularity.jetta.runtime.space.SpaceImpl.factCount(): Int {
        val storeField = net.singularity.jetta.runtime.space.SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(this) as List<net.singularity.jetta.compiler.frontend.ir.Expression>
        return store.size
    }

    private fun net.singularity.jetta.runtime.space.SpaceImpl.countFact(symbol: String): Int {
        val storeField = net.singularity.jetta.runtime.space.SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(this) as List<net.singularity.jetta.compiler.frontend.ir.Expression>
        return store.count { e ->
            e.atoms.size == 2 &&
                (e.atoms[0] as? net.singularity.jetta.compiler.frontend.ir.Symbol)?.name == "Fact" &&
                (e.atoms[1] as? net.singularity.jetta.compiler.frontend.ir.Symbol)?.name == symbol
        }
    }
}
