package net.singularity.jetta.runtime.space

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Efficient binary writer with VarInt encoding for compact storage.
 */
class BinaryWriter {
    private val buffer = ByteArrayOutputStream()

    fun writeByte(value: Byte) {
        buffer.write(value.toInt())
    }

    fun writeInt(value: Int) {
        // VarInt encoding: uses 1-5 bytes depending on value magnitude
        var v = value
        while (v and 0x7F.inv() != 0) {
            buffer.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        buffer.write(v and 0x7F)
    }

    fun writeLong(value: Long) {
        // VarInt encoding for longs: uses 1-10 bytes
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buffer.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        buffer.write((v and 0x7FL).toInt())
    }

    fun writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        buffer.write(bytes)
    }

    fun writeBoolean(value: Boolean) {
        buffer.write(if (value) 1 else 0)
    }

    fun writeDouble(value: Double) {
        val bits = value.toBits()
        writeLong(bits)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}