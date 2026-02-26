package net.singularity.jetta.compiler.frontend.ir.formatter

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom

interface IrFormatter {
    fun format(atom: Atom): String
    fun format(source: ParsedSource): String
}