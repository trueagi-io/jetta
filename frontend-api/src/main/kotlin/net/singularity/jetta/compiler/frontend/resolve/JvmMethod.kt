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
     * Weaker than [inertAtomParams] on purpose, and the difference is not pedantry: hyperon keeps
     * reducing a function's RESULT, so passing a reducible term unreduced there merely defers the
     * work, while JeTTa returns its result as it stands. Held unconditionally, `(: ift (-> Bool Atom
     * %Undefined%))` over `(add-atom &kb (Green $x))` would never perform the write (e1_kb_write),
     * and `(: myPair (-> Atom Atom Atom))` over `(f X)` would answer `(Pair (f X) (f Y))`. Both terms
     * are closed and computable, so they are computed; the reference stdlib's `filter-atom` template
     * `(> $v 1)` is not, and it is passed on.
     */
    val templateAtomParams: Set<Int> = emptySet(),
)