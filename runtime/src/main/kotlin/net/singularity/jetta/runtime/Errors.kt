package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol

/**
 * A top-level `!`-run answered an `(Error …)` term, which ends the program.
 *
 * The reference interpreter has no exception for this — it flips its runner into
 * `MettaRunnerMode::TERMINATE` and stops reading the script. A compiled program has no
 * interpreter loop to flip, so the equivalent is a throw out of `__main`: every later run is
 * a call that never happens, which is exactly "the script stops here".
 *
 * It carries the error atom itself rather than only a rendered message, so a host embedding a
 * compiled program can inspect the term the way the reference inspects its result vector.
 */
class MettaError(val error: Any?) : RuntimeException("$error")

/**
 * MeTTa-level errors: the `(Error <term> <detail>)` convention shared by the runtime's grounded
 * operations, the eval-time type check, and the standard library's `if-error`.
 */
object Errors {

    /** Head symbol of an error term — hyperon's `ERROR_SYMBOL`. */
    const val ERROR = "Error"

    /**
     * Whether [value] is an error term: an expression whose head is the symbol `Error`.
     *
     * Deliberately SHALLOW, matching hyperon's `atom_is_error`: `(foo (Error …))` is an ordinary
     * term that happens to contain an error, and a program is free to build and pass one around
     * (`if-error` and `return-on-error` exist precisely to inspect such values). Only an error in
     * the RESULT position is the run's own failure.
     */
    @JvmStatic
    fun isError(value: Any?): Boolean {
        val atom = if (value is BoundAtom) value.atom else value
        return atom is Expression && atom.atoms.isNotEmpty() &&
            (atom.atoms.first() as? Symbol)?.name == ERROR
    }

    /**
     * The first error term in a run's result, or `null` when there is none. A multivalued run
     * answers a bag, and hyperon terminates when ANY element of it is an error
     * (`result.iter().any(atom_is_error)`), so the bag is searched rather than tested as a whole.
     */
    @JvmStatic
    fun firstError(value: Any?): Any? = when (value) {
        is BoundAtom -> firstError(value.atom)
        is List<*> -> value.firstNotNullOfOrNull { firstError(it) }
        else -> if (isError(value)) value else null
    }

    /**
     * Called by generated `__main` on the result of every top-level `!`-run, in order.
     *
     * An error stops the program, as it stops the reference script: hyperon records the error
     * result and switches to `TERMINATE`, so the runs below it are never evaluated. Verified on
     * `metta-repl`: `!(println! first)` `!(+ 5 "S")` `!(println! third)` prints `first` and the
     * error, and never `third`.
     *
     * The LAST run is checked too even though nothing follows it. Termination is only half of the
     * semantics — the other half is that the error is the program's visible outcome, which is how
     * the reference's own output is judged (an `(Error …)` printed at top level means the file
     * failed). Letting a trailing error pass silently is what made a false pass possible before.
     */
    @JvmStatic
    fun checkRunResult(value: Any?) {
        val error = firstError(value) ?: return
        throw MettaError(error)
    }
}
