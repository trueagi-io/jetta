package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceImpl
import net.singularity.jetta.runtime.space.SpaceRegistry
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The space-fingerprint check in [JettaProgram.init]. The compiled program bakes in the
 * (atomCount, contentHash) of the space it was built against; `init` compares that against
 * the loaded manifest so a wrong/stale artifact pair fails loudly instead of silently
 * running against the wrong space. Missing artifacts stay lenient (empty space).
 */
class JettaProgramInitTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        dir = Files.createTempDirectory("jetta-fp")
        JettaProgram.setDataDir(dir)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        JettaProgram.setDataDir(java.nio.file.Path.of("."))
        dir.deleteRecursively()
    }

    private fun saveSpaceWith(name: String, atomCount: Int, hash: String) {
        val space = SpaceImpl().apply { add(Expression(Symbol("Fact"), Symbol("A"))) }
        SpaceDirectorySerializer.save(
            space = space,
            directory = dir,
            programName = name,
            atomCount = atomCount,
            contentHash = hash,
        )
    }

    @Test
    fun `matching fingerprint loads without error`() {
        saveSpaceWith("prog", atomCount = 1, hash = "fp-v1")
        JettaProgram.init("prog", 1, "fp-v1")
        // Space registered and populated.
        assertTrue(SpaceRegistry.get(net.singularity.jetta.runtime.space.SpaceId.FromModule("prog")) != null)
    }

    @Test
    fun `mismatched fingerprint throws a clear error (found the wrong artifacts)`() {
        // Artifacts on disk carry a DIFFERENT hash than the program was compiled with —
        // same atom count, so only the content hash distinguishes them (the stale-edit case).
        saveSpaceWith("prog", atomCount = 1, hash = "fp-v2")
        val ex = assertFailsWith<IllegalStateException> {
            JettaProgram.init("prog", 1, "fp-v1")
        }
        assertTrue(ex.message!!.contains("mismatch"), "message: ${ex.message}")
        assertTrue(ex.message!!.contains("fp-v1") && ex.message!!.contains("fp-v2"))
    }

    @Test
    fun `missing artifacts stay lenient (no throw, empty space)`() {
        // Nothing saved under this name: a program whose `=` rules are compiled to functions
        // and never queries the space must still run — an absent .jtsf is not an error.
        JettaProgram.init("absent", 7, "whatever")
        assertTrue(SpaceRegistry.get(net.singularity.jetta.runtime.space.SpaceId.FromModule("absent")) != null)
    }
}
