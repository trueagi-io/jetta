package net.singularity.jetta.compiler.frontend.ir

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod

/**
 * What a call site's head resolved to.
 *
 * [alternatives] is normally empty: one MeTTa name, one compiled method. It is non-empty only
 * for a name that MORE THAN ONE VISIBLE MODULE defines — two imported modules each carrying
 * their own `(= (dup $x) …)`. The reference interpreter keeps both rules in `&self` and UNIONS
 * their answers, so such a call is non-deterministic: codegen invokes [jvmMethod] and every
 * entry of [alternatives] and collects the results into one bag, and [isMultiValued] is true.
 * See `Context.resolveUserFunction`.
 */
data class ResolvedSymbol(
    val jvmMethod: JvmMethod,
    val arrowType: ArrowType?,
    val isMultiValued: Boolean,
    val alternatives: List<JvmMethod> = emptyList(),
)
