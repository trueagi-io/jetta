package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.backend.CompilationResult
import net.singularity.jetta.compiler.backend.DefaultRuntime
import net.singularity.jetta.compiler.backend.Generator
import net.singularity.jetta.compiler.backend.registerExternals
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LetRewriter
import net.singularity.jetta.runtime.JettaProgram
import net.singularity.jetta.runtime.space.SpaceImpl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * JIT-eval primitive — "the compiler is part of the runtime".
 *
 * [eval] takes a MeTTa expression *as data* and reduces it by COMPILING it to JVM
 * bytecode at call time through the real [Generator] pipeline (the same one the AOT
 * compiler uses), loading the bytecode, and invoking it — no separate interpreter for
 * dynamic forms. This completes the partial-eval bet: one code path for build-time and
 * call-time, so the AOT/interpreter divergence class of bugs cannot exist. It is also
 * what distinguishes JeTTa from PeTTa (`assertz`/`consult`) and hyperon (pure
 * interpreter): runtime metaprogramming at compiled speed.
 *
 * ## Scope — Step 1 MVP
 *
 * This slice proves the compile -> load -> invoke loop for expressions over SYSTEM
 * functions only (`(+ 1 2)` -> `3`, `(superpose (1 2 3))` -> `[1,2,3]`). User-function
 * support — resolving `(color)` against the running program's `(= …)` rules — is a
 * deliberate follow-up that plugs into [newEvalContext] (SEAM 1 below) WITHOUT touching
 * [eval]'s signature, the call path, or class loading.
 *
 * Deferred: `SwitchPoint` epoch invalidation, tree-walk cold path + tier-up, hidden-class
 * loading (SEAM 2), fully dynamic `(match …)` with a non-static template.
 */
object JettaJit {
    private val counter = AtomicInteger(0)

    /**
     * Structural-shape cache: an expression compiled once is re-invoked on every later
     * [eval] of the same shape. Keyed by the data's structural string (MVP); the
     * `CanonicalFormRewriter`-based hash — which also folds alpha-equivalent variables —
     * is the upgrade.
     */
    private val cache = ConcurrentHashMap<String, CompiledEval>()

    private class CompiledEval(val clazz: Class<*>, val entryMethod: String)

    /**
     * Reduce [code] (a MeTTa expression as DATA) to its result bag.
     *
     * Reached from compiled user code as an `INVOKESTATIC` to this method by string name
     * (the `eval` system function registered in `registerExternals`); the argument must
     * arrive inert (e.g. via `(quote …)`), never pre-reduced.
     */
    @JvmStatic
    fun eval(code: Atom): List<Atom> {
        val env = JitEnvRegistry.current()
        // The structural cache is sound only for the env-less (system-only) path: with
        // a forked env the result depends on WHICH program is running (owner classes)
        // and on its current rule set, which mutates on redefinition. Env-aware caching
        // + SwitchPoint invalidation is the follow-up; for now recompile per env-eval.
        val compiled =
            if (env == null) cache.getOrPut(cacheKey(code)) { compile(code, null) }
            else compile(code, env)
        val raw = compiled.clazz.getMethod(compiled.entryMethod).invoke(null)
        return normalize(raw)
    }

    private fun cacheKey(code: Atom): String = code.toString()

    /**
     * Compile `code` as the body of a synthetic 0-arg function `(= (__evalN) code)`
     * through the real pipeline, then load it. Mirrors `ReplImpl.compile` /
     * `Compiler.compileMultipleSources`, but the input is already IR (no parse step) and
     * we emit no `__main` (we invoke the synthetic entry directly).
     */
    private fun compile(code: Atom, env: JitEnv?): CompiledEval {
        val id = counter.getAndIncrement()
        val className = "JettaEval$id"
        val entry = "__eval$id"

        // (= (__evalN) code) — FunctionRewriter turns this into a FunctionDefinition.
        val synthetic = Expression(
            listOf(Special(Predefined.PATTERN), Expression(listOf(Symbol(entry))), code)
        )
        val source = ParsedSource(className, listOf(synthetic))

        val messageCollector = MessageCollector()
        val context = newEvalContext(messageCollector, env)

        val rewriter = CompositeRewriter()
        // FunctionRewriter writes the `(= …)` rule back into the context's space — which
        // is precisely why that space must be a throwaway (see newEvalContext): the
        // synthetic rule must never leak into the caller's space.
        rewriter.add { FunctionRewriter(messageCollector, context.getSpace()) }
        rewriter.add { LetRewriter() }
        rewriter.add { LambdaRewriter(messageCollector) }
        val resolved = context.resolve(rewriter.rewrite(source))

        if (messageCollector.hasErrors()) {
            throw JitEvalException(code, messageCollector.list().joinToString("\n") { it.toString() })
        }

        // Bake the CALLER's space name (not the unique synthetic class name) into the
        // eval'd code's `&self`/match/dispatch sites, so they route to the running
        // program's live space (registered by JettaProgram.init) rather than an empty
        // space named after the throwaway synthetic class.
        val results = Generator(generateMain = false, spaceNameOverride = JettaProgram.currentSpaceName())
            .generate(resolved)
        return CompiledEval(load(results, className, env), entry)
    }

    /**
     * SEAM 1 — Context construction. The single point that decides which symbols the
     * eval'd code can resolve.
     *
     * With a live [env] (same-JVM: REPL / in-process run), FORK the program's resolved
     * [Context]: eval'd code resolves the program's user functions, and because the
     * forked `resolvedFunctions` carries their owner classes, a user call becomes an
     * `INVOKESTATIC` against the already-compiled class (it links, not recompiles). The
     * fork shares the durable tables copy-on-write and gets a throwaway space, so the
     * synthetic `(= (__evalN) …)` rule never touches the caller's space.
     *
     * Without an env (cross-JVM `jetta`, or a direct [eval] call): a fresh system-only
     * [Context] — resolves `+`, `superpose`, `match`, … but no user functions. The
     * `.jctx`-persisted-overlay path that would restore user functions cross-JVM is a
     * later slice.
     */
    private fun newEvalContext(messageCollector: MessageCollector, env: JitEnv?): Context {
        env?.let { return it.context.fork(SpaceImpl(), messageCollector) }
        val runtime = DefaultRuntime()
        val context = Context(messageCollector, runtime.mapImpl, runtime.flatMapImpl, SpaceImpl())
        registerExternals(context)
        return context
    }

    /**
     * SEAM 2 — class loading. Defines the synthetic classes in a child [ClassLoader]
     * whose parent resolves both the runtime symbols (`Matcher`, `Convert`, …) and, when
     * an [env] is present, the program's compiled classes (so an eval'd user call's
     * `INVOKESTATIC Foo.bar` links). Without an env, parent is JettaJit's own loader.
     *
     * The hidden-class upgrade (GC-collectible, no metaspace growth — per the JIT-eval
     * invariants) swaps only this method; deferred because `defineHiddenClass` requires
     * the synthetic class to share the lookup's package, which the default-package class
     * names produced here do not yet satisfy.
     */
    private fun load(results: List<CompilationResult>, entryClassName: String, env: JitEnv?): Class<*> {
        val parent = env?.classLoader ?: JettaJit::class.java.classLoader
        val loader = EvalClassLoader(parent)
        results.forEach { loader.define(it.className, it.bytecode) }
        return loader.loadClass(entryClassName)
    }

    /**
     * Normalize a compiled function's return value to the `List<Atom>` non-determinism
     * bag every JeTTa result is shaped as: a multivalued body already returns a list, a
     * single-valued one returns a bare value (boxed by reflection) that we wrap.
     */
    private fun normalize(raw: Any?): List<Atom> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.map { toAtom(it) }
        else -> listOf(toAtom(raw))
    }

    private fun toAtom(value: Any?): Atom = when (value) {
        is BoundAtom -> value.atom
        is Atom -> value
        null -> Expression(emptyList())
        else -> Grounded(value)
    }

    private class EvalClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        private val defs = HashMap<String, ByteArray>()

        fun define(name: String, bytecode: ByteArray) {
            defs[name] = bytecode
        }

        override fun findClass(name: String): Class<*> {
            val bytes = defs.remove(name) ?: return super.findClass(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}

/** Thrown when an `eval`'d expression fails to compile (resolution/codegen errors). */
class JitEvalException(code: Atom, messages: String) :
    RuntimeException("Cannot JIT-eval `$code`:\n$messages")
