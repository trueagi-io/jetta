package net.singularity.jetta.compiler.storage

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import java.security.MessageDigest

/**
 * A content fingerprint of a module's effective space, baked into the compiled program AND
 * written to its manifest at compile time. [JettaProgram.init] compares the two: if the
 * artifacts loaded at runtime don't carry the same fingerprint the program was compiled
 * with, it fails loudly instead of silently running against a wrong/stale/absent space
 * (which previously surfaced as empty `match` results and no error).
 *
 * The fingerprint is computed only at compile time and stored on both sides, so a correct
 * artifact pair always matches — the check can never false-positive on a valid run. It
 * catches: missing artifacts, artifacts from a different compile (stale), and edits that
 * change content without changing the atom count (which a bare count would miss).
 */
object SpaceDigest {
    data class Fingerprint(val atomCount: Int, val contentHash: String)

    fun of(atoms: List<Expression>): Fingerprint {
        // Order-independent (sorted) so a reordering of otherwise-identical facts is not
        // flagged as a mismatch. Canonical rendering ignores type/id — only structure and
        // leaf identity matter for "is this the same space".
        val canon = atoms.map { canonical(it) }.sorted().joinToString("\n")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
        return Fingerprint(atoms.size, bytes.joinToString("") { "%02x".format(it) }.take(16))
    }

    private fun canonical(atom: Atom): String = when (atom) {
        is Expression -> atom.atoms.joinToString(" ", "(", ")") { canonical(it) }
        is Symbol -> atom.name
        is Grounded<*> -> "#${atom.value}"      // distinguish a grounded value from a same-named symbol
        is Variable -> "\$${atom.name}"
        else -> atom.toString()
    }
}
