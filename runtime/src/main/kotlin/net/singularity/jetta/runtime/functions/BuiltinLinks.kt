package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.backend.DefaultRuntime
import net.singularity.jetta.compiler.backend.registerExternals
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.runtime.space.SpaceImpl
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The compiler's BUILTINS, linkable by name at run time — `cdr-atom`, `cons-atom`, `size-atom`, … —
 * for [JettaCallSite.reduceTemplateBag], which reduces a term handed over as data.
 *
 * [JettaLinkRegistry] holds what a program's `.jctx` names, which is its own functions and the
 * grounded operators; a builtin is compiled to an `INVOKESTATIC` and never appears there. So a term
 * a meta-typed parameter held unevaluated could not be forced when it called one: `(cdr-atom (a b))`
 * stayed inert, and a recursion walking a held expression down to `()` never reached it. The table
 * is read off the same `registerExternals` the compiler uses, so the two cannot disagree, and it is
 * built on the first miss rather than per program: most programs never force a term at all.
 *
 * A builtin whose descriptor is `void` has no value to answer with and is left out.
 */
internal object BuiltinLinks {
    private val context by lazy {
        val runtime = DefaultRuntime()
        Context(MessageCollector(), runtime.mapImpl, runtime.flatMapImpl, SpaceImpl()).also { registerExternals(it) }
    }

    private val entries = ConcurrentHashMap<String, Optional<JettaLinkRegistry.Entry>>()

    fun lookup(name: String): JettaLinkRegistry.Entry? =
        entries.computeIfAbsent(name) { Optional.ofNullable(link(it)) }.orElse(null)

    private fun link(name: String): JettaLinkRegistry.Entry? {
        if (!context.isSystemFunction(name)) return null
        val symbol = context.resolve(name) ?: return null
        val jvm = symbol.jvmMethod
        if (jvm.descriptor.endsWith(")V")) return null
        return try {
            val loader = BuiltinLinks::class.java.classLoader
            val owner = Class.forName(jvm.owner.replace('/', '.'), false, loader)
            val type = MethodType.fromMethodDescriptorString(jvm.descriptor, loader)
            val handle = MethodHandles.publicLookup().findStatic(owner, jvm.name, type)
            JettaLinkRegistry.Entry(handle, type.parameterArray(), type.returnType(), symbol.isMultiValued)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
