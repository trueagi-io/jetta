package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod

interface JettaRuntime {
    val mapImpl: JvmMethod
    val flatMapImpl: JvmMethod
}