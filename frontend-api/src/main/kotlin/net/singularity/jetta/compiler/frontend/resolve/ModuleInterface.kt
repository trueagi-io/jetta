package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.SeqType

/**
 * One entry of a module's INTERFACE: everything a call site in a different compilation unit needs
 * in order to link against a compiled function without seeing its source.
 *
 * The first four fields are the original P1 linker table (`<program>.jctx`), which the RUNTIME
 * reads to bind `($f x)` to an already-loaded method. The rest is what the COMPILER additionally
 * needs, and the reason the artifact grew: resolving a call means answering questions the JVM
 * descriptor cannot: what MeTTa type the function declares (arity, boxing, and the type stamped
 * on the call expression), and which of its `Atom` parameters take their argument as a TERM rather
 * than as a value. Without them an imported module has to be re-parsed and re-resolved from source
 * just to be called — see [net.singularity.jetta.compiler.frontend.resolve.JvmMethod].
 *
 * The two parameter sets cannot be recomputed from anything in the artifact: they come out of an
 * analysis of the function's BODY (which declared-`Atom` parameter escapes into a result, which one
 * a clause takes apart), so they have to travel with the interface.
 */
data class ModuleInterfaceEntry(
    val name: String,
    val owner: String,
    val descriptor: String,
    val multivalued: Boolean,
    val signature: String? = null,
    val declaredType: ArrowType? = null,
    val inertAtomParams: Set<Int> = emptySet(),
    val templateAtomParams: Set<Int> = emptySet(),
) {
    /** The call-site view of this entry — what codegen emits its `INVOKESTATIC` from. */
    fun toJvmMethod(): JvmMethod = JvmMethod(
        owner = owner,
        name = name,
        descriptor = descriptor,
        signature = signature,
        inertAtomParams = inertAtomParams,
        templateAtomParams = templateAtomParams,
    )
}

/**
 * The `.jctx` codec: tab-separated lines, one function per line, columns in [ModuleInterfaceEntry]
 * order. Plain text on purpose — it keeps the artifact diffable and parseable at runtime without
 * pulling a serialization dependency into the runtime module.
 *
 * Columns past the fourth are additive: [net.singularity.jetta.runtime.functions.JettaLinkRegistry]
 * reads the first four and ignores the rest, so an older runtime still loads a newer table.
 */
object ModuleInterface {
    /** Column value for "no such thing here" — a null signature, an absent type, an empty set. */
    const val ABSENT = "-"

    fun renderAll(entries: List<ModuleInterfaceEntry>): String =
        entries.joinToString("\n") { render(it) }

    fun render(entry: ModuleInterfaceEntry): String = listOf(
        entry.name,
        entry.owner,
        entry.descriptor,
        entry.multivalued.toString(),
        entry.signature ?: ABSENT,
        TypeCodec.render(entry.declaredType),
        renderIndices(entry.inertAtomParams),
        renderIndices(entry.templateAtomParams),
    ).joinToString("\t")

    fun parseAll(text: String): List<ModuleInterfaceEntry> =
        text.lineSequence().mapNotNull { parse(it) }.toList()

    /** Parse one line, or null when it is blank or too short to name a linkable method. */
    fun parse(line: String): ModuleInterfaceEntry? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 4) return null
        return ModuleInterfaceEntry(
            name = parts[0],
            owner = parts[1],
            descriptor = parts[2],
            multivalued = parts[3].toBoolean(),
            signature = parts.getOrNull(4)?.takeUnless { it == ABSENT },
            declaredType = parts.getOrNull(5)?.let { TypeCodec.parse(it) } as? ArrowType,
            inertAtomParams = parseIndices(parts.getOrNull(6)),
            templateAtomParams = parseIndices(parts.getOrNull(7)),
        )
    }

    private fun renderIndices(indices: Set<Int>): String =
        if (indices.isEmpty()) ABSENT else indices.sorted().joinToString(",")

    private fun parseIndices(column: String?): Set<Int> {
        if (column == null || column == ABSENT || column.isBlank()) return emptySet()
        return column.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toIntOrNull() }
    }
}

/**
 * Text form of a resolved type. The grammar is tiny because `FunctionRewriter.asType()` has already
 * ERASED everything a MeTTa program can write down to a closed set: a [GroundedType], a nested
 * [ArrowType], or a [SeqType] (the bag a multivalued function returns). A user type, a type
 * variable and a type application all arrive here as `Atom`, so there is nothing else to encode —
 * which is why this needs neither the parser nor a general IR serializer.
 *
 *   `(-> Int Int)` · `(-> (-> Int Int) [Int])` · `Atom`
 */
object TypeCodec {
    fun render(type: Atom?): String = when (type) {
        null -> ModuleInterface.ABSENT
        is ArrowType -> "(-> " + type.types.joinToString(" ") { render(it) } + ")"
        is SeqType -> "[" + render(type.elementType) + "]"
        is GroundedType -> type.toString()
        // Unreachable for a resolved arrow type, and erasing rather than failing is the
        // conservative answer: `Atom` is what asType() would have produced anyway.
        else -> GroundedType.ATOM.toString()
    }

    fun parse(text: String): Atom? {
        if (text.isBlank() || text == ModuleInterface.ABSENT) return null
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val cursor = intArrayOf(0)
        return parseType(tokens, cursor)
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                tokens.add(word.toString())
                word.clear()
            }
        }
        for (ch in text) {
            when {
                ch == '(' || ch == ')' || ch == '[' || ch == ']' -> {
                    flush()
                    tokens.add(ch.toString())
                }
                ch.isWhitespace() -> flush()
                else -> word.append(ch)
            }
        }
        flush()
        return tokens
    }

    private fun parseType(tokens: List<String>, cursor: IntArray): Atom? {
        val token = tokens.getOrNull(cursor[0]) ?: return null
        cursor[0]++
        return when (token) {
            "(" -> {
                // `(-> …)`; anything else parenthesised never survives asType() erasure.
                if (tokens.getOrNull(cursor[0]) == "->") cursor[0]++
                val components = mutableListOf<Atom>()
                while (cursor[0] < tokens.size && tokens[cursor[0]] != ")") {
                    components.add(parseType(tokens, cursor) ?: break)
                }
                if (tokens.getOrNull(cursor[0]) == ")") cursor[0]++
                ArrowType(components)
            }
            "[" -> {
                val element = parseType(tokens, cursor) ?: GroundedType.ATOM
                if (tokens.getOrNull(cursor[0]) == "]") cursor[0]++
                SeqType(element)
            }
            else -> GroundedType.entries.firstOrNull { it.toString() == token } ?: GroundedType.ATOM
        }
    }
}
