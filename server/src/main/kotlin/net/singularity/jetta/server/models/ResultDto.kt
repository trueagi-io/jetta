package net.singularity.jetta.server.models

import kotlinx.serialization.Serializable
import net.singularity.jetta.repl.EvalResult

@Serializable
data class ResultDto(
    @Serializable(with = AnySerializer::class) val result: Any?,
    val type: String?,
    val messages: List<String>,
    val isSuccess: Boolean
)

fun EvalResult.toDto() =
    ResultDto(
        result,
        result?.let { it::class.java.canonicalName },
        messages,
        isSuccess
    )