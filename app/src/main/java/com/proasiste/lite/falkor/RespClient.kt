package com.proasiste.lite.falkor

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** An error reply from the server, as opposed to a lost connection, which is an IOException. */
class RedisError(message: String) : Exception(message)

/** Speaks the Redis protocol over any pair of streams. Replies become String, Long, List or null. */
class RespClient(input: InputStream, private val output: OutputStream) {
    private val input = BufferedInputStream(input)

    fun call(vararg arguments: String): Any? {
        val request = ByteArrayOutputStream()
        request.write("*${arguments.size}\r\n".toByteArray())
        for (argument in arguments) {
            val bytes = argument.toByteArray()
            request.write("$${bytes.size}\r\n".toByteArray())
            request.write(bytes)
            request.write(CRLF)
        }
        output.write(request.toByteArray())
        output.flush()
        return readReply()
    }

    private fun readReply(): Any? {
        val line = readLine()
        val body = line.substring(1)
        return when (line[0]) {
            '+' -> body
            '-' -> throw RedisError(body)
            ':' -> body.toLong()
            '$' -> readBulk(body.toInt())
            '*' -> readArray(body.toInt())
            else -> error("Unexpected reply: $line")
        }
    }

    private fun readBulk(size: Int): String? {
        if (size < 0) return null
        val bytes = input.readNBytes(size)
        if (bytes.size < size || input.readNBytes(CRLF.size).size < CRLF.size) throw EOFException()
        return String(bytes)
    }

    /** An error inside an array is held until the array is fully read, so the next reply still lines up. */
    private fun readArray(size: Int): List<Any?>? {
        if (size < 0) return null
        var failure: RedisError? = null
        val items = ArrayList<Any?>(size)
        repeat(size) {
            try {
                items += readReply()
            } catch (error: RedisError) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
        return items
    }

    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value == '\r'.code) {
                if (input.read() < 0) throw EOFException()
                return line.toString()
            }
            line.write(value)
        }
    }

    private companion object {
        val CRLF = "\r\n".toByteArray()
    }
}
