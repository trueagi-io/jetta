package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.SourcePosition
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotInferTypeMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.IncompatibleTypesMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.UndefinedVariableMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableButFoundMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableOrConstantButFoundMessage
import net.singularity.jetta.compiler.parser.messages.ParseErrorMessage

class DefaultMessageRenderer : MessageRenderer {
    override fun render(message: Message): String {
        return when (message) {
            is CannotInferTypeMessage -> "Can not infer type of ${message.atom} in function ${message.functionDefinition.name}"
            is CannotResolveSymbolMessage -> "Can not resolve symbol ${message.symbol}"
            is IncompatibleTypesMessage -> "Incompatible types: required ${message.requiredType} but found ${message.foundType}"
            is UndefinedVariableMessage -> "Undefined variable ${message.name}"
            is ParseErrorMessage -> "Parse error: ${message.message}"
            is ExpectVariableButFoundMessage -> "Expect variable but found ${message.atom}"
            is ExpectVariableOrConstantButFoundMessage -> "Expect variable or constant but found ${message.atom}"
            else -> message.toString()
        } + " at ${render(message.position)}"
    }

    private fun render(sourcePosition: SourcePosition?): String {
        return if (sourcePosition == null) "unknown source"
        else "${sourcePosition.filename}:${sourcePosition.start.line}:${sourcePosition.start.column}-${sourcePosition.end.line}:${sourcePosition.end.column}"
    }
}