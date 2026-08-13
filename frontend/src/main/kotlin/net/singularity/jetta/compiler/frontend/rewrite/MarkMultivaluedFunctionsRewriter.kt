package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.Lambda
import net.singularity.jetta.compiler.frontend.ir.PredefinedAtoms
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued

class MarkMultivaluedFunctionsRewriter(val functions: MutableMap<String, FunctionDefinition>) : Rewriter {
    private val callsLocations = mutableMapOf<String, MutableList<FunctionDefinition>>()

    companion object {
        // Non-determinism barriers — see CanonicalFormRewriter.BARRIER_FUNCTIONS.
        private val BARRIER_FUNCTIONS = setOf("collapse", "assertEqual", "assertEqualToResult", "msort", "once", "unique")
    }

    override fun rewrite(source: ParsedSource): ParsedSource {
        source.code.forEach {
            val def = it as FunctionDefinition
            functions[def.name] = def
        }
        source.code.forEach {
            when (it) {
                is FunctionDefinition -> {
                    if (checkAtom(it.body, it)) {
                        if (!it.isMultivalued()) {
                            it.annotations.add(PredefinedAtoms.MULTIVALUED)
                        }
                        callsLocations[it.name]?.let { list ->
                            list.forEach { call -> call.annotations.add(PredefinedAtoms.MULTIVALUED) }
                        }
                    }
                }
                else -> { }
            }
        }
        return source
    }

    private fun checkAtom(atom: Atom, func: FunctionDefinition): Boolean {
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
                (atom.atoms[0] as? Lambda)?.let { if (checkAtom(it.body, func)) return true }
                (atom.atoms[0] as? Symbol)?.let {
                    functions[it.name]?.let { def ->
                        if (def.isMultivalued()) {
                            return true
                        }
                    }
                    // Also check if the call is to a resolved system function
                    // that is multivalued (e.g., match)
                    if (atom.resolved?.isMultiValued == true) {
                        return true
                    }
                    callsLocations.getOrPut(it.name) { mutableListOf() }.add(func)
                }
                atom.atoms.drop(1).forEach {
                    if (checkAtom(it, func)) return true
                }
                return false
            }
            else -> return false
        }
    }
}