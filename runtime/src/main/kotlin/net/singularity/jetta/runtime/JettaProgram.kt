package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceId
import net.singularity.jetta.runtime.space.SpaceImpl
import net.singularity.jetta.runtime.space.SpaceRegistry
import java.nio.file.Path
import kotlin.io.path.exists

open class JettaProgram {
    companion object {
        private var dataDir: Path = Path.of(".")

        /**
         * The id of the entry program's space — the one that [match] / [matchEval] /
         * [getSpace] currently operate on, since neither codegen nor those static methods
         * carry a space argument yet. [init] sets this when an entry program starts; before
         * that it is [SpaceId.Anonymous] so call sites running outside an `init` cycle (unit
         * tests, REPL probes) still see a consistent empty space rather than null.
         *
         * This field is a stepping stone. Once codegen threads the space name through every
         * `match` call site, the static methods take it as a parameter and this field is
         * removed.
         */
        private var currentSpaceId: SpaceId = SpaceId.Anonymous

        @JvmStatic
        fun setDataDir(path: Path) {
            dataDir = path
        }

        @JvmStatic
        fun init(programName: String) {
            // Each program starts from a clean slate: drop all previously registered spaces
            // and any leftover variable bindings from a prior run in the same JVM.
            SpaceRegistry.reset()
            Matcher.getBindings().clear()

            val id = SpaceId.FromModule(programName)
            val manifestFile = dataDir.resolve("$programName.manifest.json")
            val space: Space = if (manifestFile.exists()) {
                SpaceDirectorySerializer.load(dataDir, programName)
            } else {
                SpaceImpl()
            }
            SpaceRegistry.register(id, space)
            currentSpaceId = id
        }

        @JvmStatic
        fun getSpace(): Space = SpaceRegistry.getOrCreate(currentSpaceId)

        @JvmStatic
        fun match(src: Expression, dst: Atom): List<Atom> =
            getSpace().match(src, dst)


        /**
         * Match with a template function for nested evaluation.
         * Instead of returning substituted data, this calls the template function
         * for each match result, allowing compiled function calls in templates.
         *
         * The template function receives the fully substituted template atom
         * (same as what `match` would return) and evaluates it, returning results.
         */
        @JvmStatic
        fun matchEval(src: Expression, dst: Atom, templateFn: java.util.function.Function<Atom, List<Atom>>): List<Atom> =
            getSpace().match(src, dst).flatMap { substituted ->
                val unwrapped = if (substituted is BoundAtom) {
                    Matcher.getBindings().putAll(substituted.bindings)
                    substituted.atom
                } else substituted
                templateFn.apply(unwrapped)
            }
    }
}
