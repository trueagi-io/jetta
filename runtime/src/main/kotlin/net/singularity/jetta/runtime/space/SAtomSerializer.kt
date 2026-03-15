package net.singularity.jetta.runtime.space

import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.SExpression
import net.singularity.jetta.runtime.space.atoms.SGrounded
import net.singularity.jetta.runtime.space.atoms.SSpecial
import net.singularity.jetta.runtime.space.atoms.SSymbol
import net.singularity.jetta.runtime.space.atoms.SVariable

/**
 * Serializes and deserializes SAtom instances.
 */
object SAtomSerializer {
    // Type tags
    private const val TAG_SYMBOL: Byte = 0
    private const val TAG_EXPRESSION: Byte = 1
    private const val TAG_VARIABLE: Byte = 3
    private const val TAG_SPECIAL: Byte = 4
    private const val TAG_GROUNDED_LONG: Byte = 20
    private const val TAG_GROUNDED_DOUBLE: Byte = 21
    private const val TAG_GROUNDED_STRING: Byte = 22
    private const val TAG_GROUNDED_BOOLEAN: Byte = 23
    private const val TAG_GROUNDED_INT: Byte = 24

    fun write(writer: BinaryWriter, atom: SAtom) {
        when (atom) {
            is SSymbol -> {
                writer.writeByte(TAG_SYMBOL)
                writer.writeString(atom.name)
            }
            is SVariable -> {
                writer.writeByte(TAG_VARIABLE)
                writer.writeString(atom.name)
            }
            is SSpecial -> {
                writer.writeByte(TAG_SPECIAL)
                writer.writeString(atom.value)
            }
            is SExpression -> {
                writer.writeByte(TAG_EXPRESSION)
                writer.writeInt(atom.atoms.size)
                atom.atoms.forEach { write(writer, it) }
            }
            is SGrounded<*> -> {
                writeGrounded(writer, atom)
            }
            else -> throw IllegalArgumentException("Unsupported SAtom type: ${atom::class.simpleName}")
        }
    }

    private fun writeGrounded(writer: BinaryWriter, grounded: SGrounded<*>) {
        when (val value = grounded.value) {
            is Long -> {
                writer.writeByte(TAG_GROUNDED_LONG)
                writer.writeLong(value)
            }
            is Int -> {
                writer.writeByte(TAG_GROUNDED_INT)
                writer.writeInt(value)
            }
            is Double -> {
                writer.writeByte(TAG_GROUNDED_DOUBLE)
                writer.writeDouble(value)
            }
            is String -> {
                writer.writeByte(TAG_GROUNDED_STRING)
                writer.writeString(value)
            }
            is Boolean -> {
                writer.writeByte(TAG_GROUNDED_BOOLEAN)
                writer.writeBoolean(value)
            }
            else -> throw IllegalArgumentException("Unsupported grounded type: ${value?.let { it::class.simpleName }}")
        }
    }

    fun read(reader: BinaryReader): SAtom {
        return when (val tag = reader.readByte()) {
            TAG_SYMBOL -> {
                val name = reader.readString()
                SSymbol(name)
            }
            TAG_EXPRESSION -> {
                val size = reader.readInt()
                val atoms = List(size) { read(reader) }
                SExpression(atoms)
            }
            TAG_VARIABLE -> {
                val name = reader.readString()
                SVariable(name)
            }
            TAG_SPECIAL -> {
                val value = reader.readString()
                SSpecial(value)
            }
            TAG_GROUNDED_LONG -> {
                val value = reader.readLong()
                SGrounded(value)
            }
            TAG_GROUNDED_INT -> {
                val value = reader.readInt()
                SGrounded(value)
            }
            TAG_GROUNDED_DOUBLE -> {
                val value = reader.readDouble()
                SGrounded(value)
            }
            TAG_GROUNDED_STRING -> {
                val value = reader.readString()
                SGrounded(value)
            }
            TAG_GROUNDED_BOOLEAN -> {
                val value = reader.readBoolean()
                SGrounded(value)
            }
            else -> throw IllegalArgumentException("Unknown SAtom tag: $tag")
        }
    }
}