package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod

class DefaultRuntime : JettaRuntime {
    override val mapImpl: JvmMethod
        get() = JvmMethod(
            owner = "net/singularity/jetta/runtime/UtilKt",
            name = "simpleMap",
            descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
            signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;TR;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
        )

    override val flatMapImpl: JvmMethod
        get() = JvmMethod(
            owner = "net/singularity/jetta/runtime/UtilKt",
            name = "simpleFlatMap",
            descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
            signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;Ljava/util/List<TR;>;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
        )
}