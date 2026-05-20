package net.singularity.jetta.runtime.functions

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bootstrap for `invokedynamic` lambda creation. Mirrors the shape of
 * [java.lang.invoke.LambdaMetafactory.metafactory] but targets the project's
 * own [JettaFunction] interface (SAM `apply(Object[]) -> Object`) and uses
 * hidden classes throughout (no metaspace bloat on repeated definition).
 *
 * Same machinery will back the JIT-eval call site once it lands; the bootstrap
 * receives an `implMethod` handle and produces a `CallSite` whose target
 * constructs a [JettaFunction] instance with captures bound.
 */
object JettaLambdaMetafactory {
    private val counter = AtomicInteger(0)

    /**
     * Bootstrap method invoked once per indy site on first hit.
     *
     * @param caller     The caller's [MethodHandles.Lookup]; carries access rights.
     * @param name       SAM method name — always `"apply"` for [JettaFunction]; unused.
     * @param invokedType `(captures...) -> JettaFunction` — the indy site's type.
     * @param implMethod Direct method handle to the lambda body, with type
     *                   `(captures..., samArgs-typed...) -> samReturn-typed`.
     */
    @JvmStatic
    fun bootstrap(
        caller: MethodHandles.Lookup,
        @Suppress("UNUSED_PARAMETER") name: String,
        invokedType: MethodType,
        implMethod: MethodHandle,
    ): CallSite {
        val implInfo = caller.revealDirect(implMethod)
        require(implInfo.referenceKind == MethodHandleInfo.REF_invokeStatic) {
            "Phase 0b supports only static lambda bodies (REF_invokeStatic); got ${implInfo.referenceKind}"
        }

        val captureCount = invokedType.parameterCount()
        val implType = implMethod.type()
        require(implType.parameterCount() >= captureCount) {
            "implMethod has ${implType.parameterCount()} params but invokedType declares $captureCount captures"
        }
        for (i in 0 until captureCount) {
            require(implType.parameterType(i) == invokedType.parameterType(i)) {
                "capture #$i type mismatch: invokedType=${invokedType.parameterType(i)} vs implMethod=${implType.parameterType(i)}"
            }
        }

        val bytes = generateImplClass(caller.lookupClass(), invokedType, implType, implInfo)
        val hiddenClass = caller.defineHiddenClass(bytes, true).lookupClass()

        val ctorType = MethodType.methodType(Void.TYPE, invokedType.parameterArray())
        val ctorHandle = caller.findConstructor(hiddenClass, ctorType)
            .asType(invokedType.changeReturnType(JettaFunction::class.java))

        return ConstantCallSite(ctorHandle)
    }

    private fun generateImplClass(
        callerClass: Class<*>,
        invokedType: MethodType,
        implType: MethodType,
        implInfo: MethodHandleInfo,
    ): ByteArray {
        // Hidden classes must live in the caller's package (JLS / `defineHiddenClass` rule).
        val callerInternal = Type.getInternalName(callerClass)
        val pkg = callerInternal.substringBeforeLast('/', missingDelimiterValue = "")
        val pkgPrefix = if (pkg.isEmpty()) "" else "$pkg/"
        val className = "${pkgPrefix}JettaLambda\$${counter.getAndIncrement()}"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "java/lang/Object",
            arrayOf(JETTA_FUNCTION_INTERNAL_NAME),
        )

        val captureCount = invokedType.parameterCount()
        val captureTypes = invokedType.parameterArray()
        val samArgTypes = (captureCount until implType.parameterCount())
            .map { implType.parameterType(it) }
        val returnType = implType.returnType()

        for (i in 0 until captureCount) {
            cw.visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
                "cap$i",
                Type.getDescriptor(captureTypes[i]),
                null,
                null,
            ).visitEnd()
        }

        generateCtor(cw, className, captureTypes)
        generateApply(cw, className, captureCount, captureTypes, samArgTypes, returnType, implInfo)

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generateCtor(cw: ClassWriter, className: String, captureTypes: Array<Class<*>>) {
        val ctorDesc = MethodType.methodType(Void.TYPE, captureTypes).toMethodDescriptorString()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        var slot = 1
        captureTypes.forEachIndexed { i, c ->
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(loadOpcode(c), slot)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "cap$i", Type.getDescriptor(c))
            slot += slotSize(c)
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateApply(
        cw: ClassWriter,
        className: String,
        captureCount: Int,
        captureTypes: Array<Class<*>>,
        samArgTypes: List<Class<*>>,
        returnType: Class<*>,
        implInfo: MethodHandleInfo,
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "apply",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        )
        mv.visitCode()

        for (i in 0 until captureCount) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, className, "cap$i", Type.getDescriptor(captureTypes[i]))
        }

        samArgTypes.forEachIndexed { i, c ->
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            pushInt(mv, i)
            mv.visitInsn(Opcodes.AALOAD)
            emitUnboxOrCast(mv, c)
        }

        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(implInfo.declaringClass),
            implInfo.name,
            implInfo.methodType.toMethodDescriptorString(),
            implInfo.declaringClass.isInterface,
        )

        emitBox(mv, returnType)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun loadOpcode(c: Class<*>): Int = when (c) {
        Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Byte::class.javaPrimitiveType,
        Short::class.javaPrimitiveType,
        Char::class.javaPrimitiveType -> Opcodes.ILOAD
        Long::class.javaPrimitiveType -> Opcodes.LLOAD
        Float::class.javaPrimitiveType -> Opcodes.FLOAD
        Double::class.javaPrimitiveType -> Opcodes.DLOAD
        else -> Opcodes.ALOAD
    }

    private fun slotSize(c: Class<*>): Int =
        if (c == Long::class.javaPrimitiveType || c == Double::class.javaPrimitiveType) 2 else 1

    private fun pushInt(mv: MethodVisitor, value: Int) = when (value) {
        0 -> mv.visitInsn(Opcodes.ICONST_0)
        1 -> mv.visitInsn(Opcodes.ICONST_1)
        2 -> mv.visitInsn(Opcodes.ICONST_2)
        3 -> mv.visitInsn(Opcodes.ICONST_3)
        4 -> mv.visitInsn(Opcodes.ICONST_4)
        5 -> mv.visitInsn(Opcodes.ICONST_5)
        in 6..Byte.MAX_VALUE.toInt() -> mv.visitIntInsn(Opcodes.BIPUSH, value)
        in (Byte.MAX_VALUE + 1)..Short.MAX_VALUE.toInt() -> mv.visitIntInsn(Opcodes.SIPUSH, value)
        else -> mv.visitLdcInsn(value)
    }

    private fun emitUnboxOrCast(mv: MethodVisitor, target: Class<*>) {
        if (!target.isPrimitive) {
            if (target != Any::class.java) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(target))
            }
            return
        }
        when (target) {
            Int::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "intValue", "()I")
            Long::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "longValue", "()J")
            Double::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "doubleValue", "()D")
            Float::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "floatValue", "()F")
            Boolean::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Boolean", "booleanValue", "()Z")
            Byte::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "byteValue", "()B")
            Short::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Number", "shortValue", "()S")
            Char::class.javaPrimitiveType -> unboxPrimitive(mv, "java/lang/Character", "charValue", "()C")
            else -> error("Unsupported primitive: $target")
        }
    }

    private fun unboxPrimitive(mv: MethodVisitor, wrapperInternal: String, method: String, desc: String) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperInternal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperInternal, method, desc, false)
    }

    private fun emitBox(mv: MethodVisitor, source: Class<*>) {
        if (!source.isPrimitive) return
        when (source) {
            Int::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Integer", "(I)Ljava/lang/Integer;")
            Long::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Long", "(J)Ljava/lang/Long;")
            Double::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Double", "(D)Ljava/lang/Double;")
            Float::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Float", "(F)Ljava/lang/Float;")
            Boolean::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
            Byte::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Byte", "(B)Ljava/lang/Byte;")
            Short::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Short", "(S)Ljava/lang/Short;")
            Char::class.javaPrimitiveType -> boxPrimitive(mv, "java/lang/Character", "(C)Ljava/lang/Character;")
            Void.TYPE -> mv.visitInsn(Opcodes.ACONST_NULL)
            else -> error("Unsupported primitive: $source")
        }
    }

    private fun boxPrimitive(mv: MethodVisitor, wrapperInternal: String, desc: String) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapperInternal, "valueOf", desc, false)
    }

    const val JETTA_FUNCTION_INTERNAL_NAME = "net/singularity/jetta/runtime/functions/JettaFunction"
}
