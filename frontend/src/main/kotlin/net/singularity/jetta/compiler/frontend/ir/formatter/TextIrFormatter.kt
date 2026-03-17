package net.singularity.jetta.compiler.frontend.ir.formatter

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*

class TextIrFormatter(
    private val indentSize: Int = 2
) : IrFormatter {

    override fun format(atom: Atom): String = buildString {
        formatAtom(atom, 0, this)
    }

    override fun format(source: ParsedSource): String = buildString {
        appendLine(";; source: ${source.filename}")
        appendLine(";; fully typed IR after all transformations")
        appendLine()
        source.code.forEachIndexed { i, atom ->
            if (i > 0) appendLine()
            formatAtom(atom, 0, this)
            appendLine()
        }
    }

    private fun formatAtom(atom: Atom, depth: Int, sb: StringBuilder) {
        when (atom) {
            is FunctionDefinition -> formatFunctionDef(atom, depth, sb)
            is Run -> formatAssertion(atom, depth, sb)
            is Lambda -> formatLambda(atom, depth, sb)
            is Match -> formatMatch(atom, depth, sb)
            is Expression -> formatExpression(atom, depth, sb)
            is Variable -> sb.append(formatVariable(atom))
            is Grounded<*> -> sb.append(formatGrounded(atom))
            is Symbol -> sb.append(formatSymbol(atom))
            is Special -> sb.append(atom.value)
            is ArrowType -> sb.append(formatArrowType(atom))
            is GroundedType -> sb.append(atom.toString())
            is SeqType -> sb.append("${formatInlineAtom(atom.elementType)}*")
            else -> TODO("atom=$atom")
        }
    }

    private fun formatVariable(v: Variable): String = buildString {
        append("\$${v.name}")
        v.type?.let { append(":${formatInlineType(it)}") }
    }

    private fun formatGrounded(g: Grounded<*>): String = buildString {
        when (g.value) {
            is String -> append("\"${g.value}\"")
            else -> append(g.value)
        }
        g.type?.let { append(":${formatInlineType(it)}") }
    }

    private fun formatSymbol(s: Symbol): String = buildString {
        append(s.name)
        s.type?.let { append(":${formatInlineType(it)}") }
    }

    private fun formatArrowType(a: ArrowType): String =
        "(-> ${a.types.joinToString(" ") { formatInlineAtom(it) }})"

    private fun formatInlineType(atom: Atom): String = when (atom) {
        is GroundedType -> atom.toString()
        is ArrowType -> formatArrowType(atom)
        is SeqType -> "${formatInlineAtom(atom.elementType)}*"
        else -> atom.toString()
    }

    private fun formatInlineAtom(atom: Atom): String = when (atom) {
        is Run -> buildString { formatAssertion(atom, 0, this) }
        is Variable -> formatVariable(atom)
        is Grounded<*> -> formatGrounded(atom)
        is Symbol -> formatSymbol(atom)
        is Special -> atom.value
        is GroundedType -> atom.toString()
        is ArrowType -> formatArrowType(atom)
        is SeqType -> "${formatInlineAtom(atom.elementType)}*"
        is Expression -> {
            if (isSimple(atom)) {
                buildString {
                    append("(")
                    atom.atoms.forEachIndexed { i, child ->
                        if (i > 0) append(" ")
                        append(formatInlineAtom(child))
                    }
                    append(")")
                    atom.type?.let { append(":${formatInlineType(it)}") }
                }
            } else atom.toString()
        }
        else -> atom.toString()
    }

    private fun formatFunctionDef(def: FunctionDefinition, depth: Int, sb: StringBuilder) {
        val indent = " ".repeat(depth * indentSize)
        val childIndent = " ".repeat((depth + 1) * indentSize)
        // Annotations
        def.annotations.forEach { ann ->
            sb.appendLine("$indent(@ ${def.name} ${formatInlineAtom(ann)})")
        }
        // Type signature
        def.arrowType?.let { arrow ->
            sb.appendLine("$indent(: ${def.name} ${formatArrowType(arrow)})")
        }
        // Definition
        sb.append("$indent(= (${def.name}")
        def.params.forEach { sb.append(" ${formatVariable(it)}") }
        sb.appendLine(")")
        if (isSimple(def.body)) {
            sb.append(childIndent)
            formatAtom(def.body, depth + 1, sb)
            sb.append(")")
        } else {
            sb.append(childIndent)
            formatAtom(def.body, depth + 1, sb)
            sb.append(")")
        }
    }

    private fun formatAssertion(run: Run, depth: Int, sb: StringBuilder) {
        sb.append("!")
        if (isSimple(run.expression)) {
            formatAtom(run.expression, depth, sb)
        } else {
            formatAtom(run.expression, depth, sb)
        }
    }

    private fun formatLambda(lambda: Lambda, depth: Int, sb: StringBuilder) {
        sb.append("(\\ (")
        lambda.params.forEachIndexed { i, p ->
            if (i > 0) sb.append(" ")
            sb.append(formatVariable(p))
        }
        sb.append(")")
        lambda.arrowType?.let { sb.append(" ${formatArrowType(it)}") }
        if (isSimple(lambda.body)) {
            sb.append(" ")
            formatAtom(lambda.body, 0, sb)
            sb.append(")")
        } else {
            sb.appendLine()
            val childIndent = " ".repeat((depth + 1) * indentSize)
            sb.append(childIndent)
            formatAtom(lambda.body, depth + 1, sb)
            sb.append(")")
        }
    }

    private fun formatExpression(expr: Expression, depth: Int, sb: StringBuilder) {
        val resolved = expr.resolved
        val resolvedComment = resolved?.let { " ;; -> ${it.jvmMethod.owner}.${it.jvmMethod.name}" } ?: ""
        if (isSimple(expr)) {
            sb.append("(")
            expr.atoms.forEachIndexed { i, child ->
                if (i > 0) sb.append(" ")
                formatAtom(child, 0, sb)
            }
            sb.append(")")
            expr.type?.let { sb.append(":${formatInlineType(it)}") }
            if (resolvedComment.isNotEmpty()) sb.append(resolvedComment)
        } else {
            sb.append("(")
            formatAtom(expr.atoms[0], 0, sb)
            val childIndent = " ".repeat((depth + 1) * indentSize)
            expr.atoms.drop(1).forEach { child ->
                sb.appendLine()
                sb.append(childIndent)
                formatAtom(child, depth + 1, sb)
            }
            sb.append(")")
            expr.type?.let { sb.append(":${formatInlineType(it)}") }
            if (resolvedComment.isNotEmpty()) sb.append(resolvedComment)
        }
    }

    private fun formatMatch(match: Match, depth: Int, sb: StringBuilder) {
        val childIndent = " ".repeat((depth + 1) * indentSize)
        val bodyIndent = " ".repeat((depth + 2) * indentSize)
        sb.append("(match")
        match.returnType?.let { sb.append(":${formatInlineType(it)}") }
        match.branches.forEach { branch ->
            sb.appendLine()
            sb.append(childIndent)
            if (branch.cond != null) {
                sb.append("(")
                formatAtom(branch.cond as Atom, depth + 1, sb)
                if (isSimple(branch.body)) {
                    sb.append(" => ")
                    formatAtom(branch.body, 0, sb)
                    sb.append(")")
                } else {
                    sb.appendLine(" =>")
                    sb.append(bodyIndent)
                    formatAtom(branch.body, depth + 2, sb)
                    sb.append(")")
                }
            } else {
                sb.append("(_ => ")
                if (isSimple(branch.body)) {
                    formatAtom(branch.body, 0, sb)
                } else {
                    sb.appendLine()
                    sb.append(bodyIndent)
                    formatAtom(branch.body, depth + 2, sb)
                }
                sb.append(")")
            }
            if (branch.destructuredBindings.isNotEmpty()) {
                sb.append("  ;; bindings: ${branch.destructuredBindings}")
            }
        }
        sb.append(")")
    }

    private fun isSimple(atom: Atom): Boolean = when (atom) {
        is Run -> isSimple(atom.expression)
        is Symbol, is Variable, is Grounded<*>, is Special, is GroundedType, is SeqType, is ArrowType -> true
        is Expression -> atom.atoms.all { isLeaf(it) } && atom.atoms.size <= 5
        else -> false
    }

    private fun isLeaf(atom: Atom): Boolean = when (atom) {
        is Symbol, is Variable, is Grounded<*>, is Special, is GroundedType -> true
        else -> false
    }
}