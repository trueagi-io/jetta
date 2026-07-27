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
    val inertAtomParams: Set<Int> = emptySet()
)