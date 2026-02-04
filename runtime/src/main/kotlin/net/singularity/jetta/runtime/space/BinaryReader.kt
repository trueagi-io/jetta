package net.singularity.jetta.runtime.space

import java.nio.charset.StandardCharsets

/**
 * Binary reader counterpart to BinaryWriter.
 */
class BinaryReader(private val bytes: ByteArray) {
    private var position = 0

    fun readByte(): Byte {
        if (position >= bytes.size) throw IllegalStateException("Unexpected end of stream")
        return bytes[position++]
    }

    fun readInt(): Int {
        var result = 0
        var shift = 0
        var b: Int

        do {
            if (position >= bytes.size) throw IllegalStateException("Unexpected end of stream")
            b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)

        return result
    }

    fun readLong(): Long {
        var result = 0L
        var shift = 0
        var b: Int

        do {
            if (position >= bytes.size) throw IllegalStateException("Unexpected end of stream")
            b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
        } while (b and 0x80 != 0)

        return result
    }

    fun readString(): String {
        val length = readInt()
        if (position + length > bytes.size) throw IllegalStateException("Unexpected end of stream")
        val str = String(bytes, position, length, StandardCharsets.UTF_8)
        position += length
        return str
    }

    fun readBoolean(): Boolean {
        return readByte() != 0.toByte()
    }

    fun readDouble(): Double {
        val bits = readLong()
        return Double.fromBits(bits)
    }

    fun hasMore(): Boolean = position < bytes.size
}