package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.getJvmDescriptor
import net.singularity.jetta.compiler.frontend.resolve.getSignature
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

/**
 * @param autoTable when true (the AOT compile path), memoize functions the compiler proves
 *   pure/deterministic/state-independent and recursive — see [computeMemoizable]. Off for the
 *   REPL / JIT-eval paths, where runtime rule redefinition and space mutation would stale the
 *   cache (needs SwitchPoint epoch invalidation, not yet implemented).
 */
class Generator(
    val generateMain: Boolean = false,
    private val spaceNameOverride: String? = null,
    private val autoTable: Boolean = false,
    /**
     * The compile-time space fingerprint (see `SpaceDigest`). When both are non-null the
     * `__main` init call is emitted with them so the runtime can verify the loaded
     * artifacts match this program; null (REPL / JIT-eval / tests) emits the plain
     * `init(String)` that skips the check.
     */
    private val spaceAtomCount: Int? = null,
    private val spaceContentHash: String? = null,
) {
    private var lambdaCount = 1

    fun generate(source: ParsedSource): List<CompilationResult> {
        lambdaCount = 1
        val cw = ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES)
        val className = source.getJvmClassName()
        // The simple class name normally doubles as the module's space name: every match
        // call site generated below bakes this string in via LDC so the runtime registry
        // can route the call to the right space. [spaceNameOverride] decouples the two —
        // used by JIT-eval to give a uniquely-named synthetic class the CALLER's space
        // name, so eval'd `&self`/match/dispatch route to the running program's live space.
        val moduleSpaceName = spaceNameOverride ?: className.substringAfterLast('/')
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            null,
            Type.getInternalName(Object::class.java),
            null
        )
        cw.visitSource(source.filename, null)

        // Pass 1: discover lambdas and assign each a method name on the enclosing
        // class. Names must be set before any FunctionGenerator runs so that nested
        // lambda creation sites (in another lambda's body or in user code) can
        // resolve `lambda.resolvedMethodName`.
        val lambdas = findLambdas(source).toList()
        lambdas.forEach { (name, lambda) -> lambda.resolvedMethodName = name }

        // Pass 2: emit each lambda body as a `private static` method on cw.
        lambdas.forEach { (name, lambda) ->
            emitLambdaMethod(cw, className, name, lambda, moduleSpaceName)
        }

        val memoized = if (autoTable)
            computeMemoizable(source.code.filterIsInstance<FunctionDefinition>())
        else emptySet()

        source.code.forEach { node ->
            when (node) {
                is FunctionDefinition -> {
                    val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
                    val desc = node.getJvmDescriptor()
                    // A memoized function is emitted as a wrapper `name` (memo cache keyed on
                    // its boxed args) delegating to `name$impl` on a miss. The body is generated
                    // unchanged into `name$impl`; its recursive calls still target `name` (the
                    // wrapper), so the recursion is memoized. See emitMemoWrapper.
                    val bodyMethodName = if (node.name in memoized) node.name + "\$impl" else node.name
                    if (node.name in memoized) {
                        emitMemoWrapper(cw, className, node, desc)
                    }
                    val mv = LocalVariablesSorter(
                        access,
                        desc,
                        cw.visitMethod(
                            access,
                            bodyMethodName,
                            desc,
                            node.getSignature(),
                            null
                        )
                    )
                    if (node.name == FunctionRewriter.MAIN) {
                        mv.visitLdcInsn(moduleSpaceName)
                        if (spaceAtomCount != null && spaceContentHash != null) {
                            // init(name, expectedAtomCount, expectedContentHash) — verifies the
                            // loaded .jtsf/manifest carries this program's space fingerprint.
                            mv.visitLdcInsn(spaceAtomCount)
                            mv.visitLdcInsn(spaceContentHash)
                            mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "net/singularity/jetta/runtime/JettaProgram",
                                "init",
                                "(Ljava/lang/String;ILjava/lang/String;)V",
                                false
                            )
                        } else {
                            mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "net/singularity/jetta/runtime/JettaProgram",
                                "init",
                                "(Ljava/lang/String;)V",
                                false
                            )
                        }
                    }
                    FunctionGenerator(mv, node, true, null, moduleSpaceName, className).generate()
                    if (generateMain && node.name == FunctionRewriter.MAIN) {
                        val mainDesc = node.getJvmDescriptor()
                        val mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                            "main",
                            "([Ljava/lang/String;)V",
                            null,
                            null
                        )
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "__main", mainDesc, false)
                        if (!mainDesc.endsWith("V")) {
                            mv.visitInsn(Opcodes.POP)
                        }
                        mv.visitInsn(Opcodes.RETURN)
                        mv.visitMaxs(1, 1)
                    }
                }

                else -> TODO("Not implemented yet")
            }
        }
        return listOf(CompilationResult(className, cw.toByteArray()))
    }

    // --- auto-tabling -------------------------------------------------------------------

    // Grounded ops that read mutable state or have effects / non-determinism: a function
    // calling any of these is NOT memoizable (its result isn't a pure function of its args).
    private val impureGrounded = setOf(
        "match", "matchEval", "println", "print", "random", "seed", "generate",
        "superpose", "collapse", "eval", "assertEqual", "assertEqualToResult",
        "add-atom!", "remove-atom!", "new-space", "bind!", "get-state", "new-state",
        "change-state!", "get-type", "import!", "pragma!", "get-doc", "help!",
    )

    // Types we can use as a hashable memo key / box as a cached value.
    private val keyable = setOf(GroundedType.INT, GroundedType.LONG, GroundedType.DOUBLE, GroundedType.BOOLEAN)

    /**
     * Functions safe (and worth it) to memoize on the AOT path: pure, deterministic,
     * state-independent, and recursive. Pure ⇔ not multivalued, primitive args + result,
     * body free of Match / lambda / impure grounded op, and every user-function it calls is
     * itself memoizable (greatest fixpoint over the call graph). Recursive ⇔ transitively
     * calls itself — bounds the cache and is where memoization pays.
     */
    private fun computeMemoizable(functions: List<FunctionDefinition>): Set<String> {
        val byName = functions.associateBy { it.name }

        fun bodyPure(atom: Atom): Boolean = when (atom) {
            is Match, is Lambda -> false
            is Expression -> {
                val head = (atom.atoms.firstOrNull() as? Symbol)?.name
                if (head != null && head in impureGrounded) false
                else atom.atoms.all { bodyPure(it) }
            }
            else -> true
        }

        fun localOk(f: FunctionDefinition): Boolean =
            !f.name.startsWith("__") &&
                !f.isMultivalued() &&
                f.params.isNotEmpty() &&
                f.returnType in keyable &&
                f.params.all { it.type in keyable } &&
                bodyPure(f.body)

        fun callees(atom: Atom, acc: MutableSet<String>) {
            if (atom is Expression) {
                (atom.atoms.firstOrNull() as? Symbol)?.name?.let { if (it in byName) acc.add(it) }
                atom.atoms.forEach { callees(it, acc) }
            }
        }
        val calls = functions.associate { f -> f.name to mutableSetOf<String>().also { callees(f.body, it) } }

        val cand = functions.filter { localOk(it) }.map { it.name }.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            val remove = cand.filter { f -> calls[f]!!.any { g -> g in byName && g !in cand } }
            if (remove.isNotEmpty()) { cand.removeAll(remove.toSet()); changed = true }
        }

        fun reachesSelf(start: String): Boolean {
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque(calls[start] ?: emptySet())
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == start) return true
                if (seen.add(n)) calls[n]?.let { stack.addAll(it) }
            }
            return false
        }
        return cand.filter { reachesSelf(it) }.toSet()
    }

    /**
     * Emit the memo wrapper `node.name` for a memoized function: key on the boxed argument
     * list, return the cached value on a hit, else call `node.name$impl`, cache, and return.
     */
    private fun emitMemoWrapper(cw: ClassWriter, className: String, node: FunctionDefinition, desc: String) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, node.name, desc, node.getSignature(), null)
        val fnId = "$className.${node.name}"
        val params = node.params.map { it.type as GroundedType }
        val ret = node.returnType as GroundedType
        val paramsWidth = params.sumOf { primWidth(it) }
        val keyLocal = paramsWidth
        val valTmp = paramsWidth + 1

        // key = java.util.Arrays.asList(box(p0), box(p1), …)
        pushInt(mv, params.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = 0
        params.forEachIndexed { i, t ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            loadPrim(mv, t, slot)
            boxIfNeeded(mv, t)
            mv.visitInsn(Opcodes.AASTORE)
            slot += primWidth(t)
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false)
        mv.visitVarInsn(Opcodes.ASTORE, keyLocal)

        // r = JettaMemo.lookup(fnId, key)
        mv.visitLdcInsn(fnId)
        mv.visitVarInsn(Opcodes.ALOAD, keyLocal)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RuntimeNames.MEMO, "lookup", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false)

        val miss = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, miss)
        // hit: unbox and return the cached value
        unboxIfNeeded(mv, ret)
        returnPrim(mv, ret)

        // miss: compute impl, cache, return
        mv.visitLabel(miss)
        mv.visitInsn(Opcodes.POP) // discard the null lookup result
        slot = 0
        params.forEach { t -> loadPrim(mv, t, slot); slot += primWidth(t) }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, node.name + "\$impl", desc, false)
        dupPrim(mv, ret)
        boxIfNeeded(mv, ret)
        mv.visitVarInsn(Opcodes.ASTORE, valTmp)
        mv.visitLdcInsn(fnId)
        mv.visitVarInsn(Opcodes.ALOAD, keyLocal)
        mv.visitVarInsn(Opcodes.ALOAD, valTmp)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RuntimeNames.MEMO, "store", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false)
        returnPrim(mv, ret)

        mv.visitMaxs(0, 0)
    }

    private fun primWidth(t: GroundedType) = if (t == GroundedType.LONG || t == GroundedType.DOUBLE) 2 else 1

    private fun pushInt(mv: MethodVisitor, v: Int) =
        if (v in 0..5) mv.visitInsn(Opcodes.ICONST_0 + v) else mv.visitIntInsn(Opcodes.BIPUSH, v)

    private fun loadPrim(mv: MethodVisitor, t: GroundedType, slot: Int) = when (t) {
        GroundedType.INT, GroundedType.BOOLEAN -> mv.visitVarInsn(Opcodes.ILOAD, slot)
        GroundedType.LONG -> mv.visitVarInsn(Opcodes.LLOAD, slot)
        GroundedType.DOUBLE -> mv.visitVarInsn(Opcodes.DLOAD, slot)
        else -> error("non-primitive memo param $t")
    }

    private fun dupPrim(mv: MethodVisitor, t: GroundedType) =
        mv.visitInsn(if (t == GroundedType.LONG || t == GroundedType.DOUBLE) Opcodes.DUP2 else Opcodes.DUP)

    private fun returnPrim(mv: MethodVisitor, t: GroundedType) = mv.visitInsn(
        when (t) {
            GroundedType.INT, GroundedType.BOOLEAN -> Opcodes.IRETURN
            GroundedType.LONG -> Opcodes.LRETURN
            GroundedType.DOUBLE -> Opcodes.DRETURN
            else -> error("non-primitive memo return $t")
        }
    )

    private fun findLambdas(source: ParsedSource): Map<String, Lambda> {
        val result = mutableMapOf<String, Lambda>()
        source.code.forEach {
            val def = (it as FunctionDefinition)
            when (val body = def.body) {
                is Expression -> findLambdas(body, result)
                is Match -> {
                    body.branches.forEach { branch ->
                        findLambdas(branch.body, result)
                    }
                }
                else -> {}
            }
        }
        return result
    }

    private fun findLambdas(body: Atom, acc: MutableMap<String, Lambda>): Map<String, Lambda> {
        when (body) {
            is Expression -> {
                if (body.atoms.isEmpty()) {
                    return acc
                }
                if ((body.atoms.first() as? Special)?.value == Predefined.RUN_SEQ) {
                    body.atoms.drop(1).forEach {
                        findLambdas(it as Expression, acc)
                    }
                    return acc
                }
                body.atoms.forEach {
                    when (it) {
                        is Lambda -> {
                            val lambdaName = "lambda\$${lambdaCount++}"
                            acc[lambdaName] = it
                            findLambdas(it.body, acc)
                        }

                        is Expression -> {
                            findLambdas(it, acc)
                        }

                        else -> {}
                    }
                }
            }

            else -> {}
        }
        return acc
    }
}