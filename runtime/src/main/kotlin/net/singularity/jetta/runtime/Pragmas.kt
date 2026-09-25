package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol

/**
 * `pragma!` — the reference interpreter's runtime mode flags, and the settings they write.
 *
 * Modelled on `PragmaOp` (`lib/src/metta/runner/stdlib/core.rs`): the key must be a symbol, the
 * value is stored as written, and the call answers the unit atom. The three documented keys are
 * `type-check auto`, `interpreter bare-minimal` and `max-stack-depth <number>`.
 *
 * Only `max-stack-depth` is ACTED ON here. The other two are recorded and nothing reads them:
 * measured on `metta-repl` (2026-09-16), `type-check auto` gates only a top-level PRE-check —
 * the per-evaluation type check is unconditional there exactly as it is here — so honouring it
 * would change no answer of ours. Recording them keeps `!(pragma! …)` from being the silent
 * no-op it was before this existed, and gives the next key a place to land.
 *
 * Settings are per program: [reset] is called from `JettaProgram.init`. `@Volatile` is
 * visibility only — one running program per JVM in this slice, as elsewhere in the runtime.
 */
object Pragmas {

    const val MAX_STACK_DEPTH = "max-stack-depth"

    private val settings = mutableMapOf<String, Atom>()

    @Volatile
    private var maxStackDepth: Int = 0

    /** Called by `JettaProgram.init`, so one program's pragmas cannot leak into the next. */
    @JvmStatic
    fun reset() {
        settings.clear()
        maxStackDepth = 0
    }

    /** The current recursion bound in MeTTa calls; `0` means no bound, as in the reference. */
    @JvmStatic
    fun maxStackDepth(): Int = maxStackDepth

    /** The value a key holds, or `null` — for a future key that wants to read its own setting. */
    @JvmStatic
    fun setting(key: String): Atom? = settings[key]

    /**
     * `(pragma! <key> <value>)`. Both arguments are INERT — `auto` and `bare-minimal` are bare
     * symbols that mean nothing as applications, and a numeric value must arrive as the literal.
     *
     * Answers the unit atom `()`, as the reference does, or an error term when the value does not
     * fit the key: `(pragma! max-stack-depth -12)` answers
     * `(Error (pragma! max-stack-depth -12) UnsignedIntegerIsExpected)`, the reference's own
     * message and shape (`test_pragma_max_stack_depth`). Note that such an error is now a
     * TERMINATING result at top level — see [Errors].
     */
    @JvmStatic
    fun `pragma!`(key: Atom, value: Atom): Atom {
        val name = (JettaProgram.deref(key) as? Symbol)?.name
            ?: return error(key, value, "pragma! expects symbol atom as a key")
        if (name == MAX_STACK_DEPTH) {
            val depth = unsignedIntOrNull(value)
                ?: return error(key, value, "UnsignedIntegerIsExpected")
            maxStackDepth = depth
        }
        settings[name] = value
        return Expression(emptyList())
    }

    /**
     * The value as a non-negative Int, or `null`. Read through the value's rendering rather than
     * its Kotlin type because a literal reaches here as `Grounded<Int>` while a symbol from a
     * quoted position reaches it as a `Symbol` whose name is the digits — the reference likewise
     * parses `value.to_string()`.
     */
    private fun unsignedIntOrNull(value: Atom): Int? {
        val text = when (val v: Any = value) {
            is Grounded<*> -> v.value?.toString()
            is Symbol -> v.name
            else -> v.toString()
        }
        val parsed = text?.toIntOrNull() ?: return null
        return if (parsed < 0) null else parsed
    }

    private fun error(key: Atom, value: Atom, message: String): Atom =
        Expression(
            Symbol(Errors.ERROR),
            Expression(Symbol("pragma!"), key, value),
            Symbol(message),
        )
}
