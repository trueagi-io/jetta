package net.singularity.jetta.compiler.frontend.resolve

data class JvmMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val signature: String? = null,
    /**
     * Indices of `Atom`-typed parameters whose argument must reach the method **fully inert**
     * (un-reduced), not merely un-boxed. The JVM descriptor alone cannot distinguish this — every
     * `Atom` param looks identical — so builtins that inspect the raw term (`get-type`; later
     * `quote`/`match`-pattern) declare it here. Codegen ([isParameterInertAtom]) then quotes the
     * argument structurally instead of evaluating it. Empty for ordinary `Atom` params, which keep
     * the existing reduce-then-box behavior.
     */
    val inertAtomParams: Set<Int> = emptySet(),
    /**
     * Indices of parameters a USER function declares literally as the meta-type `Atom`, where the
     * argument is held unreduced only when it is a TEMPLATE — a term carrying a variable that
     * nothing in scope binds, so there is no value to compute in the first place.
     *
     * Weaker than [inertAtomParams] on purpose: this is the parameter the body uses only as a VALUE,
     * so evaluating a closed argument at the call site is the same answer, compiled. Held, `(: ift
     * (-> Bool Atom %Undefined%))` over `(add-atom &kb (Green $x))` would need the body to force it;
     * computed here, the write happens (e1_kb_write). The reference stdlib's `filter-atom` template
     * `(> $v 1)` has no value to compute, and it is passed on. A parameter the body ALSO uses as a
     * term is held outright instead — see `FunctionDefinition.heldAtomParams`.
     */
    val templateAtomParams: Set<Int> = emptySet(),
)