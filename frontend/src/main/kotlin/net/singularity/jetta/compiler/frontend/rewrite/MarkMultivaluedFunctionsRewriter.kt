package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.PredefinedAtoms
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued

class MarkMultivaluedFunctionsRewriter(val functions: MutableMap<String, FunctionDefinition>) : Rewriter {
    private val callsLocations = mutableMapOf<String, MutableList<FunctionDefinition>>()

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
                (atom.atoms[0] as? Symbol)?.let {
                    functions[it.name]?.let { def ->
                        if (def.isMultivalued()) {
                            return true
                        }
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