package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.resolve.ModuleInterfaceEntry

/**
 * A module that was compiled EARLIER and is imported as artifacts rather than as source: its
 * [entries] (the `.jctx` interface) say what its functions are and where their methods live, and
 * its [atoms] are the space it serialized (`.jtsf`) — type declarations, `=` rules, doc atoms.
 *
 * Both halves are needed to import it. The entries let a call site link; the atoms are what the
 * resolver, the pattern indexer and a reflective `match &self` see, and what the runtime `import!`
 * copies into the target space. Nothing here holds the compiled code — that is a class the runtime
 * loads by the name the entries carry.
 */
data class PrecompiledModule(
    val name: String,
    val entries: List<ModuleInterfaceEntry>,
    val atoms: List<Expression>,
)

/**
 * Where [ImportResolutionPass] asks whether a module is already compiled, before it goes looking
 * for the module's source next to the importer.
 *
 * Kept as an interface because the lookup differs by deployment and the pass should not care:
 * a directory of artifacts during a build, a resource inside the compiler's own jar for the
 * shipped stdlib. Reading a `.jtsf` needs the runtime's serializer, which the frontend does not
 * depend on, so the implementations live in the compiler module.
 */
interface PrecompiledModuleResolver {
    /** The compiled module named [name], or null when it has to be compiled from source. */
    fun find(name: String): PrecompiledModule?

    companion object {
        /** Resolves nothing — the default, so an unconfigured compile behaves exactly as before. */
        val NONE: PrecompiledModuleResolver = object : PrecompiledModuleResolver {
            override fun find(name: String): PrecompiledModule? = null
        }
    }
}
