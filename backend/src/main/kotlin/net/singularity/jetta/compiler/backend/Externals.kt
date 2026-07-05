package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.ir.SeqType
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import org.objectweb.asm.Type

fun registerExternals(context: Context) {
    val random = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "random",
        descriptor = "()D"
    )
    val seed = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "seed",
        descriptor = "(J)V"
    )
    context.addSystemFunction(ResolvedSymbol(random, null, false))
    context.addSystemFunction(ResolvedSymbol(seed, null, false))
    val generate = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "generate",
        descriptor = "(L${RuntimeNames.JETTA_FUNCTION};DDD)Ljava/util/List;",
    )
    context.addSystemFunction(ResolvedSymbol(generate,
        ArrowType(ArrowType(GroundedType.DOUBLE, GroundedType.DOUBLE),
            GroundedType.DOUBLE, GroundedType.DOUBLE,
            GroundedType.DOUBLE, SeqType(GroundedType.DOUBLE)
        ), true))
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.IO,
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            ), null, false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.ASSERTIONS,
                name = "assertEqual",
                descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ANY, GroundedType.UNIT),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.ASSERTIONS,
                name = "assertEqualToResult",
                descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ANY, GroundedType.UNIT),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "match",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Expression;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "superpose",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // Space mutation built-ins. First arg is the space (an ATOM in the arrow type; `&self`
    // lowers to the module's space-name String at the call site, exactly like `match`). The
    // atom arg is typed ATOM so the resolver does NOT reduce it — `add-atom` stores data
    // verbatim (hyperon's `add-reduct` is the reducing variant). `add-atom`/`remove-atom`
    // return the unit atom `()` (an ATOM value, as in hyperon — NOT void: a void/UNIT
    // return can't be unboxed when the call is nested in a lambda-lifted context); not
    // multivalued. `get-atoms` returns the space's atom bag (multivalued).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "add-atom",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "remove-atom",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "get-atoms",
                descriptor = "(Ljava/lang/String;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "collapse",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // eval — the JIT-eval primitive. `(eval (quote EXPR))` hands the inert EXPR to
    // JettaJit, which compiles+loads+invokes it at call time and returns the result bag.
    // The argument must arrive as DATA (hence `quote`): the param type Atom keeps the
    // resolver from reducing it at the call site. Multivalued — returns a List<Atom>.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/functions/JettaJit",
                name = "eval",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "matchEval",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Expression;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/runtime/functions/JettaFunction;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM, ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)), SeqType(GroundedType.ATOM)),
            true
        )
    )
}