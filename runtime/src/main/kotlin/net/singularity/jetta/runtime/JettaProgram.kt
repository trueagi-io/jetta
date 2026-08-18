package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.functions.GroundedOps
import net.singularity.jetta.runtime.functions.JettaCallSite
import net.singularity.jetta.runtime.functions.JettaFunction
import net.singularity.jetta.runtime.functions.JettaLinkRegistry
import net.singularity.jetta.runtime.functions.JitEnvRegistry
import net.singularity.jetta.runtime.functions.TypeEngine
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

        /**
         * Ordered-top-level-semantics watermark (Approach 2, "space-query watermark"). The number
         * of `&self` facts declared ABOVE the currently-running top-level `!`-run in source order;
         * `< 0` means "no filtering" (the run sees every fact). Set by the generated
         * `set-watermark!` step that `FunctionRewriter.mkMain` interleaves before each run, reset
         * per program in [init]. Read by [selfAtoms] so `get-type`/`get-doc`/`typeCheckError`
         * observe only the facts/`:`-decls/`=`-rules visible at the run's position — hyperon's
         * interleaved model. See `docs/specs/ordered_top_level_semantics_plan.md`.
         *
         * `@Volatile` is visibility only (single running program per JVM in this slice).
         */
        @Volatile
        private var currentWatermark: Int = -1

        /** The current ordered-top-level visibility cutoff; `< 0` = no filtering. Read by
         *  [net.singularity.jetta.runtime.space.SpaceImpl.match] to hide `=`/data facts declared
         *  below the running `!`-form (so `reduceOrInert` / reflective `match &self` are ordered). */
        @JvmStatic
        fun currentWatermark(): Int = currentWatermark

        /**
         * The `set-watermark!` builtin — the generated per-run prologue for ordered top-level
         * semantics. [w] is a `Grounded<Int>` fact-count; store it as the visibility cutoff and
         * return unit. Compiler-internal (emitted only by [net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter],
         * never written by users); impure, so excluded from memoization in `Generator.impureGrounded`.
         */
        @JvmStatic
        fun `set-watermark!`(w: Atom): Atom {
            currentWatermark = ((w as? Grounded<*>)?.value as? Number)?.toInt() ?: -1
            return UNIT_ATOM
        }

        /**
         * Ordered-top-level reduction guard (step 2): is an `=` rule at source ordinal [ordinal]
         * visible to the current run? A compiled Match branch consults this before matching, so a
         * rule declared BELOW the running `!`-form is skipped (the expression stays inert). Only
         * branches with `ordinal >= 0` (a rule preceded by a run) emit the call; `currentWatermark
         * < 0` (run sees all facts) makes it always visible — the perf-neutral common case.
         */
        @JvmStatic
        fun isRuleVisible(ordinal: Int): Boolean {
            val wm = currentWatermark
            return wm < 0 || ordinal < wm
        }

        /**
         * Tokens bound by `bind!` (token name → bound atom, typically a state cell). Reset per
         * program in [init]. Concurrent map for parity with SpaceRegistry, though the current
         * pipeline runs a single program per JVM.
         */
        private val tokens = java.util.concurrent.ConcurrentHashMap<String, Atom>()

        /**
         * Per-target-space set of module names already imported into it, so a repeated
         * `(import! &space M)` is idempotent (hyperon loads each module into a given space
         * once). Keyed by the resolved space name; reset per program in [init].
         */
        private val importedModules = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

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
            Matcher.clearTop()
            tokens.clear()
            importedModules.clear()
            currentSpaceName = programName
            currentWatermark = -1

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

            // Cross-JVM (AOT) variable-head dispatch: load this program's linker table so
            // JettaCallSite can resolve `($f x)` (with `$f` naming a user function) by linking
            // against the compiled method. Missing `.jctx` is a no-op — a program with no
            // linkable functions dispatches nothing dynamically. Done here (not gated on the
            // manifest) because a program's `=` rules live as compiled functions regardless of
            // whether it also has space facts.
            JettaLinkRegistry.load(
                dataDir,
                programName,
                Thread.currentThread().contextClassLoader ?: JettaProgram::class.java.classLoader,
            )

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
            // A `bind!` token inside the PATTERN or TEMPLATE denotes the atom it names (hyperon
            // substitutes tokens at parse time; JeTTa binds at run time, so the substitution
            // happens here). The space reference is deliberately excluded — it is resolved by
            // name through [resolveSpaceName].
            return matchIn(spaceObj, derefDeep(src), derefDeep(dst))
        }

        /**
         * Replace every `bind!`-registered token in [atom] with the atom it names, recursing into
         * sub-expressions. Guarded by an empty-registry check, so a program that never calls
         * `bind!` — every hot benchmark — pays nothing.
         */
        @JvmStatic
        fun derefDeep(atom: Atom): Atom {
            if (tokens.isEmpty()) return atom
            // Widened to Any for the same reason as [cellOf]: a `&`-name reaching a value position
            // is a bare String, even where the parameter is typed Atom.
            return when (val t: Any = atom) {
                is net.singularity.jetta.compiler.frontend.ir.Symbol -> tokens[t.name] ?: atom
                is String -> tokens[t] ?: atom
                is Expression -> {
                    var changed = false
                    val mapped = t.atoms.map { sub: Atom ->
                        val d = derefDeep(sub)
                        if (d !== sub) changed = true
                        d
                    }
                    if (changed) Expression(mapped) else atom
                }
                else -> atom
            }
        }

        /**
         * Shallow [derefDeep] for a single VALUE: a `&`-token reaching a value position is a bare
         * String (codegen's space-reference convention), a quoted one is a [Symbol]. Anything the
         * registry does not know is returned unchanged.
         */
        @JvmStatic
        fun deref(value: Any?): Any? {
            if (tokens.isEmpty()) return value
            return when (value) {
                is String -> tokens[value] ?: value
                is net.singularity.jetta.compiler.frontend.ir.Symbol -> tokens[value.name] ?: value
                else -> value
            }
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
         * `nop` — evaluate [value] for its effect and discard it, returning the unit atom `()`.
         * The parameter is ANY, so the argument IS reduced at the call site (that is the whole
         * point: `(nop (change-state! …))` runs the mutation); only the RESULT is thrown away,
         * which is how hyperon's test scripts turn an effectful run into a unit-valued one.
         */
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun nop(value: Any?): Atom = UNIT_ATOM

        /**
         * `add-atom` — add [atom] to [space] as DATA (unreduced; the
         * system-function signature types the argument `Atom`, suppressing call-site
         * reduction, matching hyperon). Bound variables in the atom ARE substituted first
         * ([Matcher.resolveDeep]): when `add-atom` runs inside a reduced match template
         * (`(match … (add-atom &self (transitive $1 $2 $3)))`), `$1/$2/$3` are bound in the
         * Matcher and must land as their values, not as free variables. In the ordinary case
         * (arguments are already-resolved function parameters) resolveDeep is a no-op. The
         * atom is folded into that space's live indexes immediately (see
         * [net.singularity.jetta.runtime.space.SpaceImpl.add]), so a subsequent `match`
         * observes it. Named with a hyphen — legal for a JVM method, and codegen emits the
         * MeTTa symbol verbatim as the INVOKESTATIC target. Returns the unit atom `()` (an
         * ordinary Atom, as in hyperon) rather than `Unit`/void, so the result composes as
         * data when `add-atom` is nested in a larger expression.
         *
         * [space] is `Object`, not `String`, for the same reason `match`'s is
         * ([resolveSpaceName]): the space can arrive INDIRECTLY, as the value of a variable
         * rather than as a literal `&name` the compiler lowered to a String. The reference
         * stdlib is written that way throughout — `(= (add-reduct $dst $atom) (add-atom $dst
         * $atom))` — and a `String` parameter rejected the `Atom` at class load.
         */
        @JvmStatic
        fun `add-atom`(space: Any?, atom: Atom): Atom {
            val resolved = Matcher.resolveDeep(atom)
            SpaceRegistry.getOrCreate(SpaceId.FromModule(resolveSpaceName(space)))
                .add(resolved as? Expression ?: Expression(listOf(resolved)))
            return UNIT_ATOM
        }

        /** `remove-atom` — remove the first atom structurally equal to [atom] from [space]. */
        @JvmStatic
        fun `remove-atom`(space: Any?, atom: Atom): Atom {
            val resolved = Matcher.resolveDeep(atom)
            SpaceRegistry.getOrCreate(SpaceId.FromModule(resolveSpaceName(space)))
                .remove(resolved as? Expression ?: Expression(listOf(resolved)))
            return UNIT_ATOM
        }

        /** `get-atoms` — the full non-deterministic bag of atoms currently in [space]. */
        @JvmStatic
        fun `get-atoms`(space: Any?): List<Atom> =
            SpaceRegistry.getOrCreate(SpaceId.FromModule(resolveSpaceName(space))).getAtoms()

        /**
         * `import!` — runtime, order-sensitive module import (hyperon semantics).
         *
         * `(import! &space M)` copies every atom of module `M`'s space into the space named
         * `&space`, AT THE POINT OF EXECUTION. Unlike a compile-time merge this respects
         * program order: `(match &self …)` before an `(import! &self M)` does not see M's
         * atoms, and after it does — which is exactly what c2_spaces asserts.
         *
         * The module's space is loaded at [init] (from the manifest's `loadModules`) under
         * `SpaceId.FromModule(M)`; if it is missing there (e.g. it was reached only through a
         * non-`&self` import), it is loaded on demand from `<M>.jtsf`. Import is idempotent
         * per (space, module): re-importing the same module into the same space is a no-op,
         * matching hyperon's load-once-per-space rule (`c2_spaces` / `f1_imports` re-imports).
         *
         * [space] is `Object` (a baked name String for `&self`/`&kb`, or a Symbol reaching
         * here indirectly), [module] an `Atom` (the bare module Symbol, unreduced). Returns
         * the unit atom `()`.
         */
        @JvmStatic
        fun `import!`(space: Any?, module: Atom): Atom {
            val spaceName = resolveSpaceName(space)
            val moduleName = when (val m = if (module is BoundAtom) module.atom else module) {
                is Symbol -> m.name
                is Grounded<*> -> m.value.toString()
                else -> m.toString()
            }
            val done = importedModules.getOrPut(spaceName) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            if (!done.add(moduleName)) return UNIT_ATOM

            val source = SpaceRegistry.get(SpaceId.FromModule(moduleName))
                ?: runCatching { SpaceDirectorySerializer.load(dataDir, moduleName) }.getOrNull()
                ?: return UNIT_ATOM
            SpaceRegistry.register(SpaceId.FromModule(moduleName), source)

            val target = SpaceRegistry.getOrCreate(SpaceId.FromModule(spaceName))
            source.getAtoms().forEach { atom ->
                target.add(atom as? Expression ?: Expression(listOf(atom)))
            }
            return UNIT_ATOM
        }

        /**
         * `matchReduce` — like [match], but each result is passed through [reduceGrounded].
         * Used when the match TEMPLATE is a grounded-operator expression, e.g.
         * `(match &kb (, (Venus orbit $x au) (Mars orbit $y au)) (- $y $x))`: match substitutes
         * the bindings to `(- 1.5 0.7)` but returns it inert; hyperon evaluates it (→ 0.8). The
         * rewriter routes such templates here instead of to plain `match`.
         */
        @JvmStatic
        fun matchReduce(space: Any?, src: Atom, dst: Atom): List<Atom> =
            match(space, src, dst).map { reduceGrounded(it) }

        /**
         * `matchReduceDeep` — like [matchReduce], but each result goes through the FULL
         * evaluator (space rules, special forms, multi-rule union) before the grounded-op pass.
         * Used when the TEMPLATE is a bare VARIABLE: what it binds to is arbitrary, and when the
         * query is over `(= <head> $x)` it is a rule BODY, which hyperon evaluates.
         * `(match &self (= (h 2) $x) $x)` over `(= (h 2) (if (< 2 0) (- 0 2) (+ 1 2)))` must
         * answer `3`, not the `if` term. Reduction runs in the CURRENT program's space, not the
         * queried one — the queried space supplies the rule, the caller's context evaluates it.
         */
        @JvmStatic
        fun matchReduceDeep(space: Any?, src: Atom, dst: Atom): List<Atom> {
            val here = currentSpaceName ?: ""
            return match(space, src, dst).flatMap { result ->
                JettaCallSite.reduceBag(here, if (result is BoundAtom) result.atom else result)
                    .map { reduceGrounded(it) }
            }
        }

        /**
         * Reduce a fully-substituted grounded-operator expression to its value. Recursively
         * evaluates nested grounded-op sub-expressions (`(- 8 (/ 4 6.4))`) then applies the head
         * operator via [GroundedOps], which unwraps `Grounded` operands to numbers at runtime —
         * so no static numeric type is needed (the values arrive as `Grounded` from the match
         * bindings). A non-grounded-op head, wrong arity, or a non-numeric operand leaves the
         * atom unchanged (inert), matching hyperon's "unreduced unless computable" rule.
         */
        private fun reduceGrounded(atom: Atom): Atom {
            val inner = if (atom is BoundAtom) atom.atom else atom
            if (inner !is Expression || inner.atoms.isEmpty()) return atom
            // Applicative order: reduce the ARGUMENTS whatever the head is. An unreducible head
            // does not freeze what it is applied to — hyperon answers `(ln 4)` for `(ln (+ 2 2))`,
            // and f1 :56 needs the same of `(g (+ 1 2))` once `g` is (correctly) not visible.
            // Codegen already applies this rule to STATIC terms; this is its runtime counterpart.
            val reduced = inner.atoms.map { reduceGrounded(it) }
            if (reduced.size == 3) {
                val op = when (val h = reduced[0]) {
                    is Symbol -> h.name
                    is net.singularity.jetta.compiler.frontend.ir.Special -> h.value
                    else -> null
                }
                // apply returns null when [op] is not a grounded operator OR an operand is not a
                // number — both mean "not computable", so fall through to the rebuilt term.
                if (op != null) GroundedOps.apply(op, reduced[1], reduced[2])?.let { return it }
            }
            // Nothing computed: keep the original atom (with its BoundAtom envelope) when no
            // argument moved, so an inert term is returned identically to before.
            return if (reduced == inner.atoms) atom else Expression(reduced)
        }

        /**
         * `is-var` — True when [atom] is syntactically a variable (an unbound `$x`). Its
         * argument is unreduced (Atom param), so a variable arrives as a [Variable] rather
         * than its binding. Returns the boolean symbols hyperon uses.
         */
        @JvmStatic
        fun `is-var`(atom: Atom): Atom =
            if ((if (atom is BoundAtom) atom.atom else atom) is Variable) Symbol("True") else Symbol("False")

        /**
         * `car-atom` — the first element of a non-empty expression, e.g.
         * `(car-atom (a b c))` → `a`. An empty expression or a non-expression argument is an
         * error (hyperon returns an `(Error … )` atom), which we surface the same way.
         */
        @JvmStatic
        fun `car-atom`(atom: Atom): Atom {
            val e = if (atom is BoundAtom) atom.atom else atom
            return if (e is Expression && e.atoms.isNotEmpty()) e.atoms[0]
            else Expression(Symbol("Error"), atom, Symbol("car-atom expects a non-empty expression"))
        }

        /**
         * `cdr-atom` — the tail (all but the first element) of a non-empty expression, e.g.
         * `(cdr-atom (a b c))` → `(b c)`. Empty / non-expression argument is an error, as above.
         */
        @JvmStatic
        fun `cdr-atom`(atom: Atom): Atom {
            val e = if (atom is BoundAtom) atom.atom else atom
            return if (e is Expression && e.atoms.isNotEmpty()) Expression(atoms = e.atoms.drop(1))
            else Expression(Symbol("Error"), atom, Symbol("cdr-atom expects a non-empty expression"))
        }

        /**
         * `decons-atom` — split a non-empty expression into head and tail, as a TWO-element
         * expression: `(decons-atom (Cons X Nil))` → `(Cons (X Nil))`. Note the shape — it is
         * not the flat expression, which is what makes it the exact inverse of [cons-atom] and
         * lets `(unify ($head $tail) …)` destructure the result. The reference `stdlib.metta`
         * defines `car-atom` and `cdr-atom` in terms of this one.
         */
        @JvmStatic
        fun `decons-atom`(atom: Atom): Atom {
            val e = if (atom is BoundAtom) atom.atom else atom
            return if (e is Expression && e.atoms.isNotEmpty())
                Expression(e.atoms[0], Expression(atoms = e.atoms.drop(1)))
            else Expression(Symbol("Error"), atom, Symbol("decons-atom expects a non-empty expression"))
        }

        /**
         * `cons-atom` — prepend an atom to an expression: `(cons-atom a (b c))` → `(a b c)`.
         * The inverse of [decons-atom]; a non-expression tail is an error.
         */
        @JvmStatic
        fun `cons-atom`(head: Atom, tail: Atom): Atom {
            val h = if (head is BoundAtom) head.atom else head
            val t = if (tail is BoundAtom) tail.atom else tail
            return if (t is Expression) Expression(atoms = listOf(h) + t.atoms)
            else Expression(Symbol("Error"), tail, Symbol("cons-atom expects an expression as its second argument"))
        }

        // ---- Documentation: get-doc / help! ------------------------------------------------
        // `@doc` and `:` forms reach the running "self" space as ordinary facts (see
        // FunctionRewriter's TYPE/ANNOTATION branches). get-doc queries them and assembles the
        // `@doc-formal` structure hyperon's reference suite expects; help! prints it.

        private val UNDEF = Symbol("%Undefined%")
        private val EMPTY = Symbol("Empty")

        /**
         * The atoms of the currently-running program's own space (`&self`), clipped to the current
         * ordered-top-level [currentWatermark]: a run sees only the facts declared above it in
         * source order. `getAtoms()` returns the store in insertion == source-fact order, and the
         * `.jtsf` round-trip preserves that order, so `subList(0, wm)` is exactly the visible prefix.
         * A negative watermark (or one past the end) means "see everything" — the common
         * facts-then-runs shape, where no filtering is emitted at all (perf-neutral).
         */
        private fun selfAtoms(): List<Atom> {
            val all = SpaceRegistry.getOrCreate(SpaceId.FromModule(currentSpaceName ?: "")).getAtoms()
            val wm = currentWatermark
            return if (wm < 0 || wm >= all.size) all else all.subList(0, wm)
        }

        /** `(@ tag …)` — an annotation Expression whose tag Symbol is [tag]. */
        private fun isAnn(a: Atom, tag: String): Boolean =
            a is Expression && a.atoms.size >= 2 &&
                (a.atoms[0] as? Special)?.value == Predefined.ANNOTATION &&
                (a.atoms[1] as? Symbol)?.name == tag

        /** `(: subject T)` — a type fact declaring the type of [subject]. */
        private fun isTypeFact(a: Atom, subject: Atom): Boolean =
            a is Expression && a.atoms.size >= 3 &&
                (a.atoms[0] as? Special)?.value == Predefined.TYPE &&
                a.atoms[1] == subject

        /** Build `(@ tag rest…)`. */
        private fun ann(tag: String, vararg rest: Atom): Expression =
            Expression(atoms = listOf(Special(Predefined.ANNOTATION), Symbol(tag)) + rest)

        /**
         * `get-doc <atom>` — return the `@doc-formal` documentation structure for [atom], or the
         * symbol `Empty` if it is undocumented. The argument is unreduced (ATOM meta-type) so a
         * documented symbol arrives as its Symbol and an application `(f a b)` as an inert
         * Expression (which never has a matching `@doc`, hence `Empty`).
         *
         * `@kind` is `function` when the `@doc` body carries an `@params` clause, else `atom`.
         * Type slots come from the `:` fact for the subject; when there is none they are the
         * symbol `%Undefined%`.
         */
        @JvmStatic
        fun `get-doc`(atom: Atom): Atom {
            val subject = if (atom is BoundAtom) atom.atom else atom
            val atoms = selfAtoms()
            val doc = atoms.firstOrNull {
                isAnn(it, "doc") && (it as Expression).atoms.getOrNull(2) == subject
            } as? Expression ?: return EMPTY
            val body = doc.atoms.drop(3)
            val declType: Atom? = (atoms.firstOrNull { isTypeFact(it, subject) } as? Expression)
                ?.atoms?.getOrNull(2)
            return if (body.any { isAnn(it, "params") }) buildFunctionDoc(subject, declType, body)
            else buildAtomDoc(subject, declType, body)
        }

        /**
         * Function-kind doc. Splits an arrow `(-> A … R)` from the `:` fact into per-parameter
         * arg types + a return type; when no arrow type is known every slot is `%Undefined%`.
         * The `@desc` clause and each `@param`/`@return` description string are reused verbatim
         * from the input so structural (`Grounded<String>`) equality holds.
         */
        private fun buildFunctionDoc(item: Atom, declType: Atom?, body: List<Atom>): Atom {
            val arrow = declType as? Expression
            val isArrow = arrow != null && (arrow.atoms.getOrNull(0) as? Special)?.value == Predefined.ARROW
            val argTypes: List<Atom> = if (isArrow) arrow!!.atoms.subList(1, arrow.atoms.size - 1) else emptyList()
            val retType: Atom = if (isArrow) arrow!!.atoms.last() else UNDEF

            val descClause = body.firstOrNull { isAnn(it, "desc") }
            val inputParams = ((body.firstOrNull { isAnn(it, "params") } as? Expression)
                ?.atoms?.getOrNull(2) as? Expression)?.atoms ?: emptyList()
            val outParams = inputParams.mapIndexed { i, p ->
                val pdesc = (p as? Expression)?.atoms?.getOrNull(2) ?: UNDEF
                ann("param", ann("type", if (isArrow) (argTypes.getOrNull(i) ?: UNDEF) else UNDEF), ann("desc", pdesc))
            }
            val rd = (body.firstOrNull { isAnn(it, "return") } as? Expression)?.atoms?.getOrNull(2) ?: UNDEF

            val slots = mutableListOf<Atom>()
            slots += ann("item", item)
            slots += ann("kind", Symbol("function"))
            slots += ann("type", declType ?: UNDEF)
            if (descClause != null) slots += descClause
            slots += ann("params", Expression(atoms = outParams))
            slots += ann("return", ann("type", retType), ann("desc", rd))
            return Expression(atoms = listOf(Special(Predefined.ANNOTATION), Symbol("doc-formal")) + slots)
        }

        /** Atom-kind doc: `(@ doc-formal (@ item X) (@ kind atom) (@ type T) <@desc from body>)`. */
        private fun buildAtomDoc(item: Atom, declType: Atom?, body: List<Atom>): Atom {
            val slots = mutableListOf<Atom>()
            slots += ann("item", item)
            slots += ann("kind", Symbol("atom"))
            slots += ann("type", declType ?: UNDEF)
            body.firstOrNull { isAnn(it, "desc") }?.let { slots += it }
            return Expression(atoms = listOf(Special(Predefined.ANNOTATION), Symbol("doc-formal")) + slots)
        }

        /**
         * `help! <atom>` — print [atom]'s documentation in a human-readable form. The output is
         * not asserted by the reference suite, so a minimal formatter suffices; it must simply
         * run without crashing (including on undocumented atoms). Returns the unit atom.
         */
        @JvmStatic
        fun `help!`(atom: Atom): Atom {
            val subject = if (atom is BoundAtom) atom.atom else atom
            val doc = `get-doc`(subject)
            if (doc == EMPTY) {
                kotlin.io.println("Atom $subject: %Undefined% No documentation")
                return UNIT_ATOM
            }
            val slots = (doc as Expression).atoms.drop(2)
            fun slot(tag: String): Atom? =
                (slots.firstOrNull { isAnn(it, tag) } as? Expression)?.atoms?.getOrNull(2)
            val kind = (slot("kind") as? Symbol)?.name ?: "atom"
            val type = slot("type")
            val desc = slot("desc")
            val header = if (kind == "function") "Function" else "Atom"
            kotlin.io.println("$header $subject: $type $desc")
            if (kind == "function") {
                val params = (slot("params") as? Expression)?.atoms ?: emptyList()
                if (params.isNotEmpty()) {
                    kotlin.io.println("Parameters:")
                    params.forEach { p ->
                        val pe = p as? Expression
                        val pType = (pe?.atoms?.getOrNull(2) as? Expression)?.atoms?.getOrNull(2)
                        val pDesc = (pe?.atoms?.getOrNull(3) as? Expression)?.atoms?.getOrNull(2)
                        kotlin.io.println("  $pType $pDesc")
                    }
                }
                val ret = slots.firstOrNull { isAnn(it, "return") } as? Expression
                if (ret != null) kotlin.io.println("Return: ${ret.atoms.getOrNull(2)} ${(ret.atoms.getOrNull(3) as? Expression)?.atoms?.getOrNull(2)}")
            }
            return UNIT_ATOM
        }

        /**
         * `get-type <atom>` — the inferred MeTTa type(s) of [atom]. Returns a bag (`List`): a
         * singleton holding the type when well-typed, EMPTY when ill-typed (the `()` empty-set
         * the reference suite asserts via `assertEqualToResult … ()`). The argument is unreduced
         * (ATOM meta-type) so `(+ 5 "4")` is type-checked as an inert expression rather than
         * evaluated. Single-valued for now — a symbol carrying several `:` types (non-deterministic
         * get-type) is a later phase; this takes the first declaration. See [TypeEngine].
         */
        @JvmStatic
        fun `get-type`(atom: Atom): List<Atom> {
            // A `bind!` token denotes the atom it names, so `(get-type &state-token)` asks about
            // the state, not about an undeclared symbol.
            val t = TypeEngine.inferType(derefDeep(atom), selfAtoms())
            return if (t == null) emptyList() else listOf(t)
        }

        /**
         * Eval-time type check for a user-function application (phase D2.3). [callExpr] is the
         * reconstructed `(f arg…)` (built by the generated function's prologue via
         * [net.singularity.jetta.runtime.functions.JettaCallSite.nonReduced]). Returns the inert
         * `(Error <callExpr> (BadArgType pos expected actual))` atom when an argument's type does
         * not match `f`'s declared `(: f (-> …))` arrow (the reference's BadArgType), or `null`
         * when it is well-typed OR gradually typed (an undeclared/`%Undefined%` argument unifies
         * with anything) — in which case the caller proceeds to normal clause reduction.
         * [TypeEngine.checkApp] reads the `:` facts in `&self`, so a head without an arrow `:`
         * fact yields `null` too.
         */
        @JvmStatic
        fun typeCheckError(callExpr: Atom): Atom? {
            // Check the DEREFERENCED form (a `bind!` token has the type of the atom it names) but
            // report the original: hyperon's error term echoes the expression as written.
            val err = TypeEngine.checkApp(derefDeep(callExpr), selfAtoms()) ?: return null
            return TypeEngine.errorExpr(callExpr, err)
        }

        /**
         * `letMatch <pattern> <value> <body>` — the runtime of MeTTa's Form-2 pattern-`let`
         * (`(let (List $t) VALUE BODY)`), lowered by `LetRewriter`. [pattern] is the LHS
         * unreduced data (`(List $t)`), [value] the evaluated RHS (a result bag `List`, or a
         * single result), and [body] a lambda over the pattern's variables in document order.
         *
         * For each result, the pattern is unified against it ([TypeEngine.unify]); on success the
         * pattern variables' bindings are read back ([TypeEngine.resolve]) and passed positionally
         * to [body], whose results are spliced into the output bag. A result that fails to unify
         * contributes nothing (an empty branch, as in `match`). Multivalued (returns a `List`) so
         * it composes with `assertEqual`'s bag semantics.
         */
        @JvmStatic
        fun letMatch(pattern: Atom, value: Any?, body: JettaFunction): List<Atom> {
            val pat = if (pattern is BoundAtom) pattern.atom else pattern
            val varNames = LinkedHashSet<String>()
            collectVarNames(pat, varNames)
            val vars = varNames.toList()

            val results: List<Any?> = when (val v = if (value is BoundAtom) value.atom else value) {
                is List<*> -> v
                null -> emptyList()
                else -> listOf(v)
            }
            val out = ArrayList<Atom>()
            for (raw in results) {
                val elem = (if (raw is BoundAtom) raw.atom else raw) as? Atom ?: continue
                val s = HashMap<String, Atom>()
                if (!TypeEngine.unify(pat, elem, s)) continue
                val args = Array<Any?>(vars.size) { i -> TypeEngine.resolve(Variable(vars[i]), s) }
                when (val res = body.apply(args)) {
                    is List<*> -> res.forEach { it?.let { a -> out.add(a as Atom) } }
                    is Atom -> out.add(res)
                    null -> {}
                    else -> out.add(Grounded(res))
                }
            }
            return out
        }

        /**
         * `unifyMatch <names> <a> <b> <then> <else>` — the runtime of minimal MeTTa's
         * `(unify $a $b $then $else)`, lowered by `FunctionRewriter`. [a] and [b] arrive as
         * UNREDUCED data (both are meta-type `Atom` in hyperon too) and are unified
         * symmetrically — either side may be the pattern, which is how the reference stdlib
         * writes it (`(unify $list ($head $tail) …)` and `(unify ($head $tail) $ht …)` both
         * occur). On success the bindings are read back and passed positionally to [thenBranch];
         * on failure [elseBranch] (0-arg) is applied. Both branches are lambdas so that only the
         * taken one is evaluated.
         *
         * [names] carries the [thenBranch] parameter names as an `Expression` of `Symbol`s, in
         * the lambda's parameter order. They cannot be recovered from [a]/[b] at runtime: codegen
         * resolves an in-scope pattern variable to its VALUE (see `generateQuote`'s `Variable`
         * arm), and that value may itself contain variables — the reference `let*` unifies
         * `($pattern $atom)` against a pair that still holds the user's `$v1`. Collecting names
         * from the atoms, as [letMatch] can afford to, would pick those up and shift every
         * positional argument. Symbols rather than Variables for the same reason in reverse: a
         * quoted `Variable` whose name matches an enclosing slot would be emitted as that slot's
         * value, losing the name.
         */
        @JvmStatic
        fun unifyMatch(
            names: Atom,
            a: Atom,
            b: Atom,
            thenBranch: JettaFunction,
            elseBranch: JettaFunction,
        ): List<Atom> {
            val left = if (a is BoundAtom) a.atom else a
            val right = if (b is BoundAtom) b.atom else b
            val s = HashMap<String, Atom>()
            if (!TypeEngine.unify(left, right, s)) return branchResult(elseBranch.apply(emptyArray()))
            val params = (if (names is BoundAtom) names.atom else names).let { n ->
                (n as? Expression)?.atoms?.mapNotNull { (it as? Symbol)?.name } ?: emptyList()
            }
            val args = Array<Any?>(params.size) { i -> TypeEngine.resolve(Variable(params[i]), s) }
            return branchResult(thenBranch.apply(args))
        }

        /** Counter for the fresh names [sealed] mints; global, so two seals never collide. */
        private val sealCounter = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * `sealed` — rename every variable in [atom] to a fresh one, EXCEPT those listed in
         * [ignore]. Note the direction: the first argument is the list to LEAVE ALONE, which is
         * how hyperon documents it ("replaces all occurrences of any var inside atom by unique
         * variable, except list of variables to ignore"). That is what gives a template a locally
         * scoped variable: `map-atom` seals its own `$map` so the recursion's variables cannot
         * capture the user's, while keeping the one variable it is about to substitute for.
         *
         * Both arguments are INERT — the point is the term as written, not its value.
         */
        @JvmStatic
        fun sealed(ignore: Atom, atom: Atom): Atom {
            val keep = HashSet<String>()
            collectVarNames(if (ignore is BoundAtom) ignore.atom else ignore, keep)
            val renamed = HashMap<String, Variable>()
            fun go(a: Atom): Atom = when (a) {
                is Variable ->
                    if (a.name in keep) a
                    else renamed.getOrPut(a.name) { Variable("${a.name}#${sealCounter.incrementAndGet()}") }
                is Expression -> Expression(a.atoms.map { go(it) })
                else -> a
            }
            return go(if (atom is BoundAtom) atom.atom else atom)
        }

        /**
         * `atom-subst` — substitute [value] for the variable named by [variable] throughout
         * [template].
         *
         * Written natively rather than taken from the reference stdlib, whose definition is a
         * minimal-MeTTa trick — `(chain (eval (noeval $atom)) $var (return $templ))` binds *the
         * variable that `$var`'s VALUE names*, which a compiler that lowers `chain` onto `let`
         * cannot express: our `let` binds the syntactic `$var`, not the variable it holds. As a
         * builtin it shadows that rule (see `Context.markDefinitionsShadowedByRuntime`) and every
         * call site links here instead. The operation itself is plain substitution.
         */
        @JvmStatic
        fun `atom-subst`(value: Atom, variable: Atom, template: Atom): Atom {
            val v = if (variable is BoundAtom) variable.atom else variable
            val name = (v as? Variable)?.name ?: return template
            return substituteVar(
                if (template is BoundAtom) template.atom else template,
                name,
                if (value is BoundAtom) value.atom else value,
            )
        }

        /**
         * `get-metatype` — the atom's META-type: which of MeTTa's four kinds of atom it is, as
         * opposed to `get-type`, which answers with the `:` declarations in the space. The
         * argument is INERT (hyperon does not reduce it either), so `(get-metatype (+ 1 2))` is
         * `Expression`, not the metatype of `3`.
         *
         * `ArrowType` answers `Expression` because it is one: the rewriter folds the surface form
         * `(-> A B)` into that IR node, and the reference stdlib's `is-function` relies on seeing
         * a type as an Expression whose head it can compare with `->`.
         */
        @JvmStatic
        fun `get-metatype`(atom: Atom): Atom = when (if (atom is BoundAtom) atom.atom else atom) {
            is Variable -> Symbol("Variable")
            is Expression, is ArrowType -> Symbol("Expression")
            is Grounded<*> -> Symbol("Grounded")
            else -> Symbol("Symbol")
        }

        /**
         * `ifEqual <a> <b> <then> <else>` — the runtime of minimal MeTTa's
         * `(if-equal $a $b $then $else)`, lowered by `FunctionRewriter`. Like [unifyMatch] the two
         * atoms arrive UNREDUCED and the branches are lambdas so only the taken one is evaluated —
         * the reference stdlib puts `(Error …)` in an else-branch, which must not run otherwise.
         *
         * The comparison is STRUCTURAL equality, not unification: `Expression` and `Symbol` define
         * `equals` structurally, and hyperon's own `if-equal` compares atoms rather than matching
         * them, so a variable here is a term to compare and binds nothing. Bound variables are
         * substituted first ([Matcher.resolveDeep]), which is what the reference interpreter does
         * before handing arguments to a grounded operation.
         */
        @JvmStatic
        fun ifEqual(a: Atom, b: Atom, thenBranch: JettaFunction, elseBranch: JettaFunction): List<Atom> {
            val left = Matcher.resolveDeep(if (a is BoundAtom) a.atom else a)
            val right = Matcher.resolveDeep(if (b is BoundAtom) b.atom else b)
            val taken = if (left == right) thenBranch else elseBranch
            return branchResult(taken.apply(emptyArray()))
        }

        /** Normalise a branch lambda's result to the `List<Atom>` bag every JeTTa result is shaped as. */
        private fun branchResult(res: Any?): List<Atom> = when (res) {
            is List<*> -> res.mapNotNull { it as? Atom }
            is Atom -> listOf(res)
            null -> emptyList()
            else -> listOf(Grounded(res))
        }

        /** Collect the variable names in [a] in document order (deduplicated by [out]'s type). */
        private fun collectVarNames(a: Atom, out: MutableSet<String>) {
            when (a) {
                is Variable -> out.add(a.name)
                is Expression -> a.atoms.forEach { collectVarNames(it, out) }
                else -> {}
            }
        }

        /**
         * Truthiness of a reduced condition value used where a boolean is required — a
         * predicate call or nested `if` in a condition position (`(if (is-var $x) …)`).
         * hyperon's booleans are the symbols `True`/`False`; a grounded `Boolean` is also
         * accepted. Anything else is false (so a non-matching / empty condition is falsey).
         */
        @JvmStatic
        fun isTruthy(value: Any?): Boolean {
            val v = if (value is BoundAtom) value.atom else value
            return when (v) {
                is Boolean -> v
                is Symbol -> v.name == "True"
                is Grounded<*> -> v.value == true
                // A multivalued call in a boolean slot yields a BAG. A one-element bag is that
                // element — the ordinary case, e.g. a predicate whose body asks `get-type`
                // (bag-returning) though it is declared `(-> Atom Bool)`. An empty bag has no
                // value and is false; a genuinely branching bag would have to FORK the `if`,
                // which a compiled boolean condition cannot do, so it is false here too.
                is List<*> -> v.size == 1 && isTruthy(v[0])
                else -> false
            }
        }

        // --- mutable state (hyperon `new-state`/`get-state`/`change-state!` + `bind!`) ------
        //
        // A state is a mutable [StateCell] wrapped in a Grounded atom. `bind!` names it via a
        // token in [tokens]; `get-state`/`change-state!` accept either that token (a Symbol,
        // resolved through the registry) or the state atom directly. The value arguments are
        // stored as data (ATOM params suppress reduction), matching hyperon.

        /** `new-state` — create a fresh mutable cell holding [value]. */
        @JvmStatic
        fun `new-state`(value: Atom): Atom = Grounded(StateCell(value))

        /**
         * `bind!` — bind [token] (a Symbol name) to [value] in the token registry. [value] is
         * reduced at the call site (ANY param), so `(bind! s (new-state x))` stores the cell.
         */
        @JvmStatic
        fun `bind!`(token: Atom, value: Any?): Atom {
            val name = (token as? Symbol)?.name ?: token.toString()
            tokens[name] = (if (value is BoundAtom) value.atom else value) as Atom
            return UNIT_ATOM
        }

        /** `get-state` — the current value of the state [token] refers to (or is). */
        @JvmStatic
        fun `get-state`(token: Atom): Atom =
            cellOf(token)?.value ?: nonReducedState("get-state", token)

        /**
         * `change-state!` — set the state's value to [newValue]; returns the state atom.
         *
         * A state is typed by what it was created with (`(StateMonad T)`), and hyperon rejects a
         * write of a different type rather than silently retyping the cell: the form's VALUE
         * becomes `(Error (change-state! …) (BadArgType 2 T <new type>))` and the cell keeps its
         * old content. The check is [TypeEngine.checkApp] over the stdlib signature
         * `(-> (StateMonad $t) $t (StateMonad $t))` — the tvar binds to the current content type
         * at parameter 1 and constrains parameter 2. Untyped content is `%Undefined%`, which
         * unifies with anything, so gradually-typed programs are unaffected.
         */
        @JvmStatic
        fun `change-state!`(token: Atom, newValue: Any?): Atom {
            val cell = cellOf(token) ?: return nonReducedState("change-state!", token)
            val value = asAtom(newValue)
            val state: Atom = Grounded(cell)
            val callExpr = Expression(listOf(Symbol("change-state!"), state, value))
            TypeEngine.checkApp(callExpr, selfAtoms())?.let { return TypeEngine.errorExpr(callExpr, it) }
            cell.value = value
            return state
        }

        /** Coerce a runtime value to an [Atom]: unwrap a [BoundAtom], box a raw primitive. */
        private fun asAtom(value: Any?): Atom = when (value) {
            is BoundAtom -> asAtom(value.atom)
            is Atom -> value
            else -> Grounded(value)
        }

        /** Resolve a state token/atom to its [StateCell]: a bound Symbol via [tokens], or a state atom directly. */
        private fun cellOf(token: Atom): StateCell? {
            // Widen to Any: a `&`-prefixed token is lowered to its bare String name (the
            // space-reference convention in codegen), so the runtime value can be a String even
            // though the parameter is typed Atom — and `is String` is not expressible against a
            // statically-Atom subject.
            val resolved: Any? = when (val t: Any = token) {
                is Symbol -> tokens[t.name] ?: t
                is BoundAtom -> t.atom
                // A state token bound via `bind!` is registered under that same String name
                // (bind! keys by `token.toString()`), so resolve it through the registry rather
                // than failing to a non-reduced state.
                is String -> tokens[t]
                else -> t
            }
            ((resolved as? Grounded<*>)?.value as? StateCell)?.let { return it }
            // The state may be addressed INDIRECTLY, by an expression that a `=` rule rewrites to
            // it — hyperon's "symbolic expression wrapping a state" idiom, where
            // `(add-atom &self (= (status (Goal $g)) <state>))` installs the rule at RUN time. The
            // head is therefore unknown to codegen, so the argument reaches us as inert data
            // rather than reduced. Reduce it here against the live space and retry. Miss path
            // only: a token that already denoted a cell returned above, so the ordinary
            // `bind!`/direct-atom cases never pay for this.
            if (resolved is Expression && resolved.atoms.isNotEmpty()) {
                val reduced = JettaCallSite.reduce(currentSpaceName ?: "", resolved)
                if (reduced !== resolved) return (reduced as? Grounded<*>)?.value as? StateCell
            }
            return null
        }

        private fun nonReducedState(op: String, token: Any?): Atom {
            // `token` may be a bare String (a `&`-name that resolved to no state cell). Storing a
            // non-Atom into the Expression's Atom[] throws ArrayStoreException, so make it an
            // Atom: a String is a symbol name, anything else is wrapped grounded.
            val tokenAtom: Atom = when (token) {
                is Atom -> token
                is String -> Symbol(token)
                else -> Grounded(token)
            }
            return Expression(listOf(Symbol(op), tokenAtom))
        }

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
                    Matcher.installBindings(substituted.bindings)
                    substituted.atom
                } else substituted
                @Suppress("UNCHECKED_CAST")
                templateFn.apply(arrayOf<Any?>(unwrapped)) as List<Atom>
            }
    }
}
