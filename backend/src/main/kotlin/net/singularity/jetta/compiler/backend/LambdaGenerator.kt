package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Lambda
import net.singularity.jetta.compiler.frontend.resolve.toJvmType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.LocalVariablesSorter

const val JETTA_FUNCTION_INTERNAL_NAME =
    "net/singularity/jetta/runtime/functions/JettaFunction"

private const val JETTA_LAMBDA_METAFACTORY_INTERNAL_NAME =
    "net/singularity/jetta/runtime/functions/JettaLambdaMetafactory"

private const val LAMBDA_BOOTSTRAP_DESCRIPTOR =
    "(Ljava/lang/invoke/MethodHandles\$Lookup;" +
        "Ljava/lang/String;" +
        "Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/invoke/MethodHandle;" +
        ")Ljava/lang/invoke/CallSite;"

/**
 * Static handle to [net.singularity.jetta.runtime.functions.JettaLambdaMetafactory.bootstrap],
 * embedded in every indy lambda creation site as the bootstrap method.
 */
val LAMBDA_BOOTSTRAP_HANDLE: Handle = Handle(
    Opcodes.H_INVOKESTATIC,
    JETTA_LAMBDA_METAFACTORY_INTERNAL_NAME,
    "bootstrap",
    LAMBDA_BOOTSTRAP_DESCRIPTOR,
    false,
)

private const val JETTA_CALL_SITE_INTERNAL_NAME =
    "net/singularity/jetta/runtime/functions/JettaCallSite"

private const val CALL_SITE_BOOTSTRAP_DESCRIPTOR =
    "(Ljava/lang/invoke/MethodHandles\$Lookup;" +
        "Ljava/lang/String;" +
        "Ljava/lang/invoke/MethodType;" +
        // Trailing static bootstrap arg: the owning module's space name, baked at
        // each dispatch site so JettaCallSite can query the right space for `(= …)`
        // rules. Bound into the dispatch handle by JettaCallSite.bootstrap.
        "Ljava/lang/String;" +
        ")Ljava/lang/invoke/CallSite;"

/**
 * Static handle to [net.singularity.jetta.runtime.functions.JettaCallSite.bootstrap],
 * embedded in every indy JIT-eval dispatch site (`(head args…)` where head is not
 * a statically-known compiled-function Symbol).
 */
val CALL_SITE_BOOTSTRAP_HANDLE: Handle = Handle(
    Opcodes.H_INVOKESTATIC,
    JETTA_CALL_SITE_INTERNAL_NAME,
    "bootstrap",
    CALL_SITE_BOOTSTRAP_DESCRIPTOR,
    false,
)

/**
 * Emits a lambda body as a `private static synthetic` method on the enclosing
 * class. The method signature is `(captures..., samArgs-typed...) -> samReturn-typed`.
 * Captures take the leading slots — variable references in the body resolve to
 * those parameters via the regular parameter-index logic (no GETFIELD pathway
 * needed, no per-lambda class generated).
 *
 * The created indy site at the lambda's creation point pairs this static body
 * with [net.singularity.jetta.runtime.functions.JettaLambdaMetafactory.bootstrap].
 */
fun emitLambdaMethod(
    cw: ClassWriter,
    enclosingClassInternalName: String,
    methodName: String,
    lambda: Lambda,
    moduleSpaceName: String,
) {
    val captures = lambda.capturedVariables()
    val descriptor = buildString {
        append('(')
        captures.forEach { append(it.type!!.toJvmType()) }
        lambda.params.forEach { append(it.type!!.toJvmType()) }
        append(')')
        append(lambda.returnType!!.toJvmType())
    }

    val access = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
    val raw = cw.visitMethod(access, methodName, descriptor, null, null)
    lambda.position?.let { /* line numbers emitted by FunctionGenerator */ }

    val mv = LocalVariablesSorter(access, descriptor, raw)

    // Synthetic Lambda with captures spliced into the params list — that's how
    // captured-variable references inside the body resolve to method parameters
    // (LOAD instructions) instead of to capture fields (GETFIELD).
    val syntheticParams = captures + lambda.params
    val synthetic = Lambda(syntheticParams, lambda.arrowType, lambda.body, lambda.position)

    FunctionGenerator(
        mv = mv,
        function = synthetic,
        isStatic = true,
        className = null,
        moduleSpaceName = moduleSpaceName,
        enclosingClassInternalName = enclosingClassInternalName,
    ).generate()
}
