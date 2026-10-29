package com.shilapi.xcertplay.util

import org.bouncycastle.util.encoders.Base64

/** Base64 operations available on the API 19 head unit without Java 8 library desugaring. */
object Base64Codec {
    fun encode(bytes: ByteArray): String = Base64.toBase64String(bytes)

    fun decode(value: String): ByteArray {
        require(value.all(::isBasicBase64Character)) { "Invalid Base64 data" }
        val padding = value.takeLastWhile { it == '=' }.length
        require(padding <= 2 && value.dropLast(padding).none { it == '=' }) { "Invalid Base64 padding" }
        require(if (padding == 0) value.length % 4 != 1 else value.length % 4 == 0) {
            "Invalid Base64 length"
        }
        return decodeBytes(value.toByteArray(Charsets.US_ASCII))
    }

    /** Mirrors Java's MIME decoder, which accepts line breaks and other non-alphabet separators. */
    fun decodeMime(value: ByteArray): ByteArray = decodeBytes(value)

    private fun decodeBytes(value: ByteArray): ByteArray = try {
        Base64.decode(value)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Invalid Base64 data", error)
    }

    private fun isBasicBase64Character(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '+' || character == '/' || character == '='

    fun encodeMime(bytes: ByteArray, lineLength: Int): String {
        require(lineLength > 0) { "lineLength must be positive" }
        return encode(bytes).chunked(lineLength).joinToString("\n")
    }
}
