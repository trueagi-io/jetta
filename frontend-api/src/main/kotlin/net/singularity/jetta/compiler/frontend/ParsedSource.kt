package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Atom

data class ParsedSource(val filename: String, val code: List<Atom>)