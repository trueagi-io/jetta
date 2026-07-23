package net.singularity.jetta.compiler.frontend.ir

data class MatchBranch(
    val cond: Expression?,
    val body: Atom,
    val destructuredBindings: List<DestructureBinding> = emptyList(),
    /**
     * Ordered-top-level source ordinal of the `=` rule this branch came from — its position among
     * `&self` facts (== runtime storeIndex). `-1` means "always visible" (no guard): either no
     * `!`-run precedes the rule, or the branch is not a top-level rule (let/lambda/case). When
     * `>= 0`, codegen emits a per-branch visibility guard against `JettaProgram.currentWatermark`
     * so the clause is skipped for a run declared above the rule (hyperon interleaved semantics).
     */
    val sourceOrdinal: Int = -1,
)