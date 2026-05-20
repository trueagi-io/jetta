package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod

class DefaultRuntime : JettaRuntime {
    override val mapImpl: JvmMethod
        get() = JvmMethod(
            owner = "net/singularity/jetta/runtime/UtilKt",
            name = "simpleMap",
            descriptor = "(Lnet/singularity/jetta/runtime/functions/JettaFunction;Ljava/util/List;)Ljava/util/List;",
        )

    override val flatMapImpl: JvmMethod
        get() = JvmMethod(
            owner = "net/singularity/jetta/runtime/UtilKt",
            name = "simpleFlatMap",
            descriptor = "(Lnet/singularity/jetta/runtime/functions/JettaFunction;Ljava/util/List;)Ljava/util/List;",
        )
}
