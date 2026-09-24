package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.Lambda
import net.singularity.jetta.compiler.frontend.ir.PredefinedAtoms
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued
import net.singularity.jetta.compiler.frontend.resolve.isShadowedByRuntime

class MarkMultivaluedFunctionsRewriter(val functions: MutableMap<String, FunctionDefinition>) : Rewriter {
    companion object {
        // Non-determinism barriers — see CanonicalFormRewriter.BARRIER_FUNCTIONS.
        private val BARRIER_FUNCTIONS = setOf("collapse", "assertEqual", "assertEqualToResult", "msort", "once", "unique")
    }

    override fun rewrite(source: ParsedSource): ParsedSource {
        source.code.forEach {
            val def = it as FunctionDefinition
            functions[def.name] = def
        }
        // A FIXPOINT, not one pass: a caller becomes multivalued when any callee does, at any
        // depth and in any source order. The old single pass patched callers through a
        // `callsLocations` table filled on the way, which reached ONE level only — with
        // `a -> b -> c` written in that order and `c` the one that superposes, `b` was marked
        // when `c` was, but nothing re-marked `a`, so `a` stayed scalar while its body returned
        // `b`'s bag (`ClassCastException: ArrayList cannot be cast to Grounded` at `(+ 10 (a 1))`).
        // Marking is monotone and bounded by the number of definitions, so this terminates.
        val definitions = source.code.filterIsInstance<FunctionDefinition>()
        do {
            var changed = false
            definitions.forEach {
                if (!it.isMultivalued() && checkAtom(it.body)) {
                    it.annotations.add(PredefinedAtoms.MULTIVALUED)
                    changed = true
                }
            }
        } while (changed)
        return source
    }

    private fun checkAtom(atom: Atom): Boolean {
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) return false
                // `quote` is inert data: a multivalued call inside a quote does not make
                // the enclosing function multivalued — its result is the single quoted
                // atom. Mirrors the quote boundary in CanonicalFormRewriter's lift pass.
                if (atom.atoms[0] == PredefinedAtoms.QUOTE) return false
                // Non-determinism barriers (assertEqual/assertEqualToResult/collapse)
                // consume the whole bag of results of their arguments, so a multivalued
                // call inside them must NOT propagate multivaluedness to the caller.
                if ((atom.atoms[0] as? Symbol)?.name in BARRIER_FUNCTIONS) return false
                // A `let` is an IMMEDIATELY APPLIED lambda by the time this pass runs (LetRewriter
                // and LambdaRewriter are both upstream), so a multivalued call in a `let` BODY sat
                // in a position this pass never visited — it descended into arguments only. The
                // enclosing function was then left scalar while its body produced a bag, and the
                // descriptor and the body disagreed: `areturn` of a `List` against a declared
                // `Expression` return, which is a VerifyError at class load. The reference
                // `stdlib.metta` is written almost entirely in `chain`, i.e. in `let`s.
                //
                // Only a lambda in HEAD position is followed. A lambda in an argument slot is a
                // VALUE — the enclosing function returns the function object, and whatever bag its
                // body would produce belongs to whoever eventually applies it.
                (atom.atoms[0] as? Lambda)?.let { if (checkAtom(it.body)) return true }
                (atom.atoms[0] as? Symbol)?.let {
                    // A rule SHADOWED by a runtime function of the same name is skipped: the
                    // resolver answers every call site with the builtin, so the callee's
                    // valuedness is the builtin's, not this unreachable rule's. hyperon's
                    // stdlib.metta redefines `cdr-atom` over the multivalued `unify`, and reading
                    // that rule here made every caller believe the scalar builtin returned a bag.
                    //
                    // Guarded by ARITY, as `CanonicalFormRewriter.isMultivaluedHead` is: a call
                    // `Context.resolveAtom` leaves inert for want of it cannot reach this rule, so
                    // the two passes must agree that it is data. (This guard was once measured to
                    // re-break `mettaset.metta`; with the fixpoint above it is neutral on the
                    // whole corpus, and `mettaset`'s crash is the lift's own — it has the same
                    // `IncompatibleClassChangeError` with or without the guard.)
                    functions[it.name]?.takeUnless { def -> def.isShadowedByRuntime() }
                        ?.takeIf { def -> def.params.size == atom.atoms.size - 1 }?.let { def ->
                        if (def.isMultivalued()) {
                            return true
                        }
                    }
                    // Also check if the call is to a resolved system function
                    // that is multivalued (e.g., match)
                    if (atom.resolved?.isMultiValued == true) {
                        return true
                    }
                }
                atom.atoms.drop(1).forEach {
                    if (checkAtom(it)) return true
                }
                return false
            }
            else -> return false
        }
    }
}