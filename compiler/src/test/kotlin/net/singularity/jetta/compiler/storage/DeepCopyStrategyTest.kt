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
    fun `main importing utils — utils' fact lands in main's effective space, only main lists it as load`(@TempDir src: Path, @TempDir out: Path) {
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

        // main's effective space sees both its own (apple) and the deep-cloned utils (banana).
        assertTrue(mainSpace.containsFact("apple"), "main should hold apple")
        assertTrue(mainSpace.containsFact("banana"), "main should hold banana (cloned from utils)")
        assertEquals(2, mainSpace.factCount(), "main has exactly own ∪ utils")

        // utils' effective space holds only its own atoms.
        assertTrue(utilsSpace.containsFact("banana"))
        assertEquals(1, utilsSpace.factCount())

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

        val mainSpace = SpaceDirectorySerializer.load(out, "main")
        // Diamond dedup at the module level — `shared` contributes once even though
        // both a and b reach c.
        assertEquals(1, mainSpace.countFact("shared"), "shared should appear exactly once in main")
        assertTrue(mainSpace.containsFact("a-own"))
        assertTrue(mainSpace.containsFact("b-own"))
        assertTrue(mainSpace.containsFact("shared"))

        // a's effective space contains a-own + shared (not b-own).
        val aSpace = SpaceDirectorySerializer.load(out, "a")
        assertTrue(aSpace.containsFact("a-own"))
        assertTrue(aSpace.containsFact("shared"))
        assertTrue(!aSpace.containsFact("b-own"))

        // b's effective space contains b-own + shared (not a-own).
        val bSpace = SpaceDirectorySerializer.load(out, "b")
        assertTrue(bSpace.containsFact("b-own"))
        assertTrue(bSpace.containsFact("shared"))
        assertTrue(!bSpace.containsFact("a-own"))

        // c's effective space holds only shared.
        val cSpace = SpaceDirectorySerializer.load(out, "c")
        assertEquals(1, cSpace.factCount())
        assertTrue(cSpace.containsFact("shared"))

        // main.manifest.json lists every transitively-reached module — order is BFS from
        // main so `a` and `b` come before `c`.
        val ext = ManifestSerializer.load(out.resolve("main.manifest.json")).extension as ManifestExtension.DeepCopy
        val ids = ext.loadModules.map { it.spaceId }.toSet()
        assertEquals(setOf("a", "b", "c"), ids)
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
        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-cp", classpath, "mainx")
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
