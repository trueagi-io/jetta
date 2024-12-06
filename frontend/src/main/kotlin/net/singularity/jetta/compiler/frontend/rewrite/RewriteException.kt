package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ir.Atom

class RewriteException(val atom: Atom) : Exception(atom.toString())