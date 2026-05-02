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

        @JvmStatic
        fun setDataDir(path: Path) {
            dataDir = path
        }

        /**
         * Reset registry, clear binding stack, and load (or create) the entry program's
         * space under [SpaceId.FromModule] keyed on [programName]. Subsequent generated
         * `match` calls supply that same name as their first argument and look the space
         * up via the registry.
         */
        @JvmStatic
        fun init(programName: String) {
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
        }

        /**
         * Match [src] against the space identified by [spaceName].
         *
         * The space-name string is baked into bytecode at codegen time — every `match`
         * call site carries the name of the module that produced it. Resolution happens
         * here via [SpaceRegistry.getOrCreate]; an unfamiliar id auto-creates an empty
         * space, which is the right behaviour for cold starts.
         */
        @JvmStatic
        fun match(spaceName: String, src: Expression, dst: Atom): List<Atom> =
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName)).match(src, dst)

        /**
         * Like [match], but each result is fed through [templateFn] for nested evaluation.
         * The template function receives the fully substituted template atom and returns
         * its evaluation result. Used for compiled `match`-with-template-call rewrites.
         */
        @JvmStatic
        fun matchEval(
            spaceName: String,
            src: Expression,
            dst: Atom,
            templateFn: java.util.function.Function<Atom, List<Atom>>,
        ): List<Atom> =
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName)).match(src, dst).flatMap { substituted ->
                val unwrapped = if (substituted is BoundAtom) {
                    Matcher.getBindings().putAll(substituted.bindings)
                    substituted.atom
                } else substituted
                templateFn.apply(unwrapped)
            }
    }
}
