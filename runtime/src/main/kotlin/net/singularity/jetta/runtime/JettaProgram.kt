package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.functions.JettaFunction
import net.singularity.jetta.runtime.functions.JitEnvRegistry
import net.singularity.jetta.runtime.space.ManifestExtension
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
         * The space name of the program currently running (the last [init]). It is the
         * runtime "self" — used by JIT-eval to bake the caller's space name into eval'd
         * code so its `&self`/match/dispatch route to the running program's live space.
         * Single running program per JVM in this slice; `@Volatile` is visibility only.
         */
        @Volatile
        private var currentSpaceName: String? = null

        @JvmStatic
        fun currentSpaceName(): String? = currentSpaceName

        @JvmStatic
        fun setDataDir(path: Path) {
            dataDir = path
        }

        /**
         * Reset registry, clear binding stack, then load this program's storage
         * artefacts into the registry.
         *
         * Strategy dispatch happens via `manifest.kind`:
         *  - **deep-copy** (Track 2E default) — load `<programName>.jtsf` plus every
         *    `loadModules` entry from the manifest, registering each under its
         *    `SpaceId.FromModule(name)` slot. Each module gets an independent
         *    [SpaceImpl] instance so future mutations stay scoped to one module.
         *  - **alias** — reserved for Track 2F's Import-As; not yet implemented.
         *
         * If no manifest is present (e.g. tests / REPL bootstrap) an empty space is
         * registered under [programName] and nothing else is loaded.
         */
        @JvmStatic
        fun init(programName: String) = initInternal(programName, null, null)

        /**
         * Init with a space fingerprint baked into the compiled program (see `SpaceDigest`).
         * Emitted by the AOT `__main`. When artifacts ARE present, verifies they carry the
         * SAME fingerprint the program was compiled against; a mismatch throws a clear error
         * rather than silently running against stale/wrong artifacts (which surfaces only as
         * incorrect `match` results). Missing artifacts stay lenient (empty space) — a
         * program's `=` rules are compiled to functions, so most never touch the space. The
         * fingerprint is stored on both sides at compile time, so a correct artifact pair
         * always matches — this can never reject a valid run.
         */
        @JvmStatic
        fun init(programName: String, expectedAtomCount: Int, expectedContentHash: String) =
            initInternal(programName, expectedAtomCount, expectedContentHash)

        private fun initInternal(programName: String, expectedAtomCount: Int?, expectedContentHash: String?) {
            SpaceRegistry.reset()
            Matcher.getBindings().clear()
            currentSpaceName = programName

            // Explicit override: `-Djetta.dataDir=<dir>` points the loader at the artifacts
            // directory regardless of cwd. The intended way to run a compiled program from
            // anywhere (the `bin/jetta` output lives beside the .class files under `-d`).
            System.getProperty("jetta.dataDir")?.let { dataDir = Path.of(it) }

            // Same-JVM execution (REPL / in-process run): there is no `.jtsf` round-trip,
            // so the program's rules live in the live compile-time space, not on disk.
            // Register THAT space under the running name so runtime `match &self …` finds
            // them — instead of the fresh empty space the disk path would create. (The
            // env is installed by the producer for the duration of the run.) The live space
            // is authoritative, so the fingerprint check does not apply here.
            JitEnvRegistry.current()?.let { env ->
                SpaceRegistry.register(SpaceId.FromModule(programName), env.space)
                return
            }

            val manifestFile = dataDir.resolve("$programName.manifest.json")
            if (!manifestFile.exists()) {
                // No artifacts at dataDir → run against an empty space, as before. A MISSING
                // .jtsf is intentionally NOT an error: a program's `=` rules are compiled to
                // functions, so most programs never query the space and run fine without it.
                // The fingerprint check below fires only when artifacts ARE present but were
                // produced by a different build ("нашли не то" — found the wrong thing).
                // Point the loader at the artifacts with -Djetta.dataDir when `match &self`
                // over compiled facts is actually needed.
                SpaceRegistry.register(SpaceId.FromModule(programName), SpaceImpl())
                return
            }

            val (entrySpace, manifest) = SpaceDirectorySerializer.loadWithManifest(dataDir, programName)

            // Artifacts found — verify they belong to THIS compiled program. A mismatch means
            // stale or wrong .jtsf/manifest next to (or ahead of) the class on the classpath.
            if (expectedAtomCount != null && expectedContentHash != null &&
                (manifest.atomCount != expectedAtomCount || manifest.contentHash != expectedContentHash)
            ) {
                throw spaceMismatch(programName, expectedAtomCount, expectedContentHash, manifest)
            }

            SpaceRegistry.register(SpaceId.FromModule(programName), entrySpace)

            when (val ext = manifest.extension) {
                is ManifestExtension.DeepCopy -> {
                    ext.loadModules.forEach { mod ->
                        val moduleSpace = SpaceDirectorySerializer.load(dataDir, mod.spaceId)
                        SpaceRegistry.register(SpaceId.FromModule(mod.spaceId), moduleSpace)
                    }
                }
                is ManifestExtension.Alias ->
                    TODO("Track 2F — alias-strategy runtime loading not yet implemented")
            }
        }

        private fun spaceMismatch(
            programName: String,
            expectedCount: Int,
            expectedHash: String?,
            loaded: net.singularity.jetta.runtime.space.ManifestV2?,
        ): IllegalStateException {
            val found = if (loaded == null) {
                "no space artifacts were found under '${dataDir.toAbsolutePath()}'"
            } else {
                "the artifacts there hold ${loaded.atomCount} atoms (fingerprint ${loaded.contentHash})"
            }
            return IllegalStateException(
                "JeTTa space artifact mismatch for program '$programName': it was compiled " +
                    "expecting $expectedCount atoms (fingerprint ${expectedHash ?: "?"}), but $found. " +
                    "The .jtsf/.manifest.json are stale, from a different build, or absent — " +
                    "recompile with bin/jettac, or run from the -d output directory (or set -Djetta.dataDir)."
            )
        }

        /**
         * Match [src] against the space identified by [space].
         *
         * [space] is `Object`, not `String`, so `match` works both DIRECTLY — where codegen
         * bakes the space name in as a String LDC (`&self` → the module name, `&kb` → "&kb")
         * — and INDIRECTLY, where the space arrives through a variable as an [Atom] (e.g.
         * `(= (match-single $space $pat $ret) (once (match $space $pat $ret)))`). A String is
         * the name verbatim; a `Symbol` name is the space name, with `&self` resolved to the
         * running program ([currentSpaceName]). See [resolveSpaceName].
         *
         * [src] is typed `Atom` (not `Expression`) so a bare-VARIABLE pattern like
         * `(match &kb $x $x)` — which matches EVERY atom — is legal. Such a pattern can't
         * go through the packed indexer (there is no structure to index on), so it is
         * handled here: bind the variable to each stored atom and render [dst]. A
         * non-variable pattern is an [Expression] and takes the normal indexed path; any
         * other atom shape (a bare Symbol/Grounded, which no stored Expression can equal)
         * matches nothing.
         */
        @JvmStatic
        fun match(space: Any?, src: Atom, dst: Atom): List<Atom> {
            val spaceObj = SpaceRegistry.getOrCreate(SpaceId.FromModule(resolveSpaceName(space)))
            return matchIn(spaceObj, src, dst)
        }

        /**
         * Resolve a space reference to its registry name. Direct call sites pass a String;
         * indirect ones pass the [Atom] a variable is bound to — a `Symbol` whose name is the
         * space name (`&self` → the running program's name; `&kb`/`&wuspace` → that literal).
         */
        private fun resolveSpaceName(space: Any?): String = when (space) {
            is String -> space
            is BoundAtom -> resolveSpaceName(space.atom)
            is net.singularity.jetta.compiler.frontend.ir.Symbol ->
                if (space.name == "&self") currentSpaceName ?: space.name else space.name
            else -> currentSpaceName ?: ""
        }

        private fun matchIn(space: net.singularity.jetta.runtime.space.Space, src: Atom, dst: Atom): List<Atom> {
            return when (src) {
                is Expression -> space.match(src, dst)
                is Variable -> space.getAtoms().map { atom ->
                    Matcher.setBinding(src.name, atom) // upward propagation, mirroring SpaceImpl.match
                    BoundAtom(substituteVar(dst, src.name, atom), mutableMapOf(src.name to atom))
                }
                else -> emptyList()
            }
        }

        /** Replace every occurrence of the variable named [name] in [template] with [value]. */
        private fun substituteVar(template: Atom, name: String, value: Atom): Atom = when (template) {
            is Variable -> if (template.name == name) value else template
            is Expression -> Expression(template.atoms.map { substituteVar(it, name, value) })
            else -> template
        }

        /** The unit atom `()` — hyperon's return value for the side-effecting space ops. */
        private val UNIT_ATOM: Atom = Expression(emptyList())

        /**
         * `add-atom` — add [atom] to the space named [spaceName] as DATA (unreduced; the
         * system-function signature types the argument `Atom`, suppressing call-site
         * reduction, matching hyperon). The atom is folded into that space's live indexes
         * immediately (see [net.singularity.jetta.runtime.space.SpaceImpl.add]), so a
         * subsequent `match` observes it. Named with a hyphen — legal for a JVM method,
         * and codegen emits the MeTTa symbol verbatim as the INVOKESTATIC target. Returns
         * the unit atom `()` (an ordinary Atom, as in hyperon) rather than `Unit`/void, so
         * the result composes as data when `add-atom` is nested in a larger expression.
         */
        @JvmStatic
        fun `add-atom`(spaceName: String, atom: Atom): Atom {
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName))
                .add(atom as? Expression ?: Expression(listOf(atom)))
            return UNIT_ATOM
        }

        /** `remove-atom` — remove the first atom structurally equal to [atom] from [spaceName]. */
        @JvmStatic
        fun `remove-atom`(spaceName: String, atom: Atom): Atom {
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName))
                .remove(atom as? Expression ?: Expression(listOf(atom)))
            return UNIT_ATOM
        }

        /** `get-atoms` — the full non-deterministic bag of atoms currently in [spaceName]. */
        @JvmStatic
        fun `get-atoms`(spaceName: String): List<Atom> =
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName)).getAtoms()

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
            templateFn: JettaFunction,
        ): List<Atom> =
            SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName)).match(src, dst).flatMap { substituted ->
                val unwrapped = if (substituted is BoundAtom) {
                    Matcher.getBindings().putAll(substituted.bindings)
                    substituted.atom
                } else substituted
                @Suppress("UNCHECKED_CAST")
                templateFn.apply(arrayOf<Any?>(unwrapped)) as List<Atom>
            }
    }
}
