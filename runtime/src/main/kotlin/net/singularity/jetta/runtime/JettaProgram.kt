package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceImpl
import java.nio.file.Path
import kotlin.io.path.exists

open class JettaProgram {
    companion object {
        private var space: Space = SpaceImpl()
        private var dataDir: Path = Path.of(".")

        @JvmStatic
        fun setDataDir(path: Path) {
            dataDir = path
        }

        @JvmStatic
        fun init(programName: String) {
            // Clear the space, it's important for test
            space = SpaceImpl()
            // Clear stale bindings from previous program runs
            Matcher.getBindings().clear()
            val manifestFile = dataDir.resolve("$programName.manifest.json")
            if (manifestFile.exists()) {
                space = SpaceDirectorySerializer.load(dataDir, programName)
            }
        }

        @JvmStatic
        fun getSpace(): Space = space

        @JvmStatic
        fun match(src: Expression, dst: Atom): List<Atom> =
            space.match(src, dst)


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
            space.match(src, dst).flatMap { substituted ->
                val unwrapped = if (substituted is BoundAtom) {
                    Matcher.getBindings().putAll(substituted.bindings)
                    substituted.atom
                } else substituted
                templateFn.apply(unwrapped)
            }
    }
}