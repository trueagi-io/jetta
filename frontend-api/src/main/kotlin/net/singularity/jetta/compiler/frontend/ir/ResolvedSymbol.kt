package net.singularity.jetta.compiler.frontend.ir

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod

data class ResolvedSymbol(val jvmMethod: JvmMethod, val arrowType: ArrowType?, val isMultiValued: Boolean)