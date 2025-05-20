package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.ir.SeqType
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.IO
import net.singularity.jetta.runtime.Random
import net.singularity.jetta.runtime.functions.Function1
import org.objectweb.asm.Type

fun registerExternals(context: Context) {
    val random = JvmMethod(
        owner = Type.getInternalName(Random::class.java),
        name = "random",
        descriptor = "()D"
    )
    val seed = JvmMethod(
        owner = Type.getInternalName(Random::class.java),
        name = "seed",
        descriptor = "(J)V"
    )
    context.addSystemFunction(ResolvedSymbol(random, null, false))
    context.addSystemFunction(ResolvedSymbol(seed, null, false))
    val generate = JvmMethod(
        owner = Type.getInternalName(Random::class.java),
        name = "generate",
        descriptor = "(L${Type.getInternalName(Function1::class.java)};DDD)Ljava/util/List;",
    )
    context.addSystemFunction(ResolvedSymbol(generate,
        ArrowType(ArrowType(GroundedType.DOUBLE, GroundedType.DOUBLE),
            GroundedType.DOUBLE, GroundedType.DOUBLE,
            GroundedType.DOUBLE, SeqType(GroundedType.DOUBLE)
        ), true))
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = Type.getInternalName(IO::class.java),
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            ), null, false
        )
    )
}