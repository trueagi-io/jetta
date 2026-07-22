package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Grounded
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The runtime half of P1 variable-head dispatch: a `name → compiled-method` linker table
 * for a COMPILED (AOT) JeTTa binary. Loaded from `<program>.jctx` by `JettaProgram.init`,
 * it lets [JettaCallSite.dispatch] resolve `($f x)` — where `$f` is a Symbol naming a user
 * function — by LINKING against that function's already-loaded compiled JVM method, instead
 * of leaving the application inert (the AOT binary has no `JitEnv`, so it cannot JIT-compile
 * the application the way the REPL does).
 *
 * Each entry holds a [MethodHandle] found via `findStatic` on the owning class, plus the
 * function's declared JVM parameter/return types so [invoke] can bridge JeTTa's boxed
 * `Atom`/`Grounded` call-site values to the method's actual primitive/`Atom` parameters and
 * box the result back to an `Atom`.
 *
 * This is the correctness step. The speed step (P2) replaces the per-call registry lookup
 * with a polymorphic-inline-cache `MutableCallSite`; this table stays the source of truth it
 * relinks against.
 */
object JettaLinkRegistry {

    /** One resolved link: the compiled method and the types needed to bridge args/return. */
    class Entry(
        val handle: MethodHandle,
        val paramTypes: Array<Class<*>>,
        val returnType: Class<*>,
        val multivalued: Boolean,
        /** A grounded operator (`+`/`-`/…): a null result means "not computable" — leave inert. */
        val groundedOp: Boolean = false,
    )

    private val table = HashMap<String, Entry>()

    /** Clear the table. Called by [JettaProgram.init] before (re)loading a program. */
    fun reset() = table.clear()

    fun lookup(name: String): Entry? = table[name]

    /**
     * Load `<programName>.jctx` from [dataDir] and resolve every entry against [loader].
     * Missing file → no-op (a program with no linkable functions, or run without artifacts).
     * A single entry that fails to resolve (class not on the classpath, method absent) is
     * skipped rather than aborting the whole load — the worst case is that one dynamic
     * application falls back to rule reduction, exactly as before P1.
     */
    fun load(dataDir: Path, programName: String, loader: ClassLoader) {
        reset()
        seedGroundedOps()
        val file = dataDir.resolve("$programName.jctx")
        if (!file.exists()) return
        val lookup = MethodHandles.publicLookup()
        file.readText().lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size != 4) return@forEach
            val (name, owner, descriptor, multivalued) = parts
            try {
                val ownerClass = Class.forName(owner.replace('/', '.'), false, loader)
                val mt = MethodType.fromMethodDescriptorString(descriptor, loader)
                val handle = lookup.findStatic(ownerClass, name, mt)
                table[name] = Entry(handle, mt.parameterArray(), mt.returnType(), multivalued.toBoolean())
            } catch (_: ReflectiveOperationException) {
                // Unresolvable link — leave it out; dispatch falls back to rule reduction.
            }
        }
    }

    /**
     * Register the grounded operators (`+`/`-`/`*`/…) as MethodHandles keyed by their operator
     * symbol, so variable-head dispatch resolves and invokes them through the same MethodHandle
     * path as user functions (see [GroundedOps]). Always available in a compiled run, whether or
     * not the program has a `.jctx`.
     */
    private fun seedGroundedOps() {
        val lookup = MethodHandles.publicLookup()
        val mt = MethodType.methodType(Atom::class.java, Any::class.java, Any::class.java)
        val params = arrayOf<Class<*>>(Any::class.java, Any::class.java)
        GroundedOps.OPS.forEach { (op, method) ->
            val handle = lookup.findStatic(GroundedOps::class.java, method, mt)
            table[op] = Entry(handle, params, Atom::class.java, multivalued = false, groundedOp = true)
        }
    }

    /**
     * Invoke the linked method for [entry] with JeTTa call-site [args] (boxed `Atom`s /
     * `Grounded` wrappers). Each arg is [coerce]d to the method's declared parameter type,
     * the method is invoked through a generic `(Object…)Object` view (so the JVM unboxes
     * primitives on the way in and boxes on the way out), and the result is normalized to an
     * `Atom` (single-valued) or returned as the `List` bag (multivalued).
     */
    fun invoke(entry: Entry, args: Array<Any?>): Any? {
        val coerced = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) coerced[i] = coerce(args[i], entry.paramTypes[i])
        val generic = entry.handle.asType(MethodType.genericMethodType(entry.paramTypes.size))
        val result = generic.invokeWithArguments(*coerced)
        if (entry.multivalued) return result // already a List<Atom>
        return when (result) {
            is Atom -> result
            null -> result
            else -> Grounded(result) // box a primitive/String result so it composes as data
        }
    }

    /**
     * Bridge one boxed call-site value to the declared JVM parameter [target]. For a primitive
     * or `String` parameter we unwrap the `Grounded`/`BoundAtom` envelope to the raw value
     * (`asType` then unboxes the `Number`/`Boolean`); for an `Atom`/`Expression`/`Object`
     * parameter we pass the atom form unchanged, wrapping a still-raw value in `Grounded`.
     */
    private fun coerce(value: Any?, target: Class<*>): Any? {
        val unwrapped = unwrap(value)
        return when (target) {
            Int::class.javaPrimitiveType, Integer::class.java -> (unwrapped as Number).toInt()
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> (unwrapped as Number).toLong()
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> (unwrapped as Number).toDouble()
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> unwrapped as Boolean
            String::class.java -> unwrapped as String
            else -> when (value) { // Atom / Expression / Object param: keep the atom form
                is BoundAtom -> value.atom
                is Atom -> value
                null -> null
                else -> Grounded(value)
            }
        }
    }

    /** Strip `BoundAtom`/`Grounded` envelopes down to the raw underlying value. */
    private fun unwrap(value: Any?): Any? = when (value) {
        is BoundAtom -> unwrap(value.atom)
        is Grounded<*> -> value.value
        else -> value
    }
}
