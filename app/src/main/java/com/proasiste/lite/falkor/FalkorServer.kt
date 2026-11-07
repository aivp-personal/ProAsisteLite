package com.proasiste.lite.falkor

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Runs Redis with the FalkorDB module as a child process, reachable only through a socket in the app's private storage.
 *
 * These are FalkorDB's official Linux builds, which expect glibc rather than Android's C library, so the app carries its
 * own copy. Android's sandbox refuses a few system calls glibc makes: native/launcher.c and native/patch_glibc.py deal with
 * that, and this class is the only code that knows about any of it. Android stops the server along with the app.
 */
class FalkorServer(context: Context) {
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    private val filesDir = context.filesDir
    private val cacheDir = context.cacheDir
    private val socket = File(filesDir, "falkor.sock")
    private val logFile = File(filesDir, "falkor.log")
    private val dataDir = File(filesDir, "graph")
    private var process: Process? = null

    /** Starts the server if nothing is answering yet, and waits until it has loaded its data. */
    @Synchronized
    fun start() {
        if (answers()) return
        process?.destroyForcibly()
        socket.delete()
        dataDir.mkdirs()
        val started = ProcessBuilder(command())
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        process = started
        repeat(START_ATTEMPTS) {
            if (!started.isAlive) throw IOException("The graph server stopped (code ${started.exitValue()}). ${lastLogLine()}")
            if (answers()) return
            Thread.sleep(START_WAIT_MILLIS)
        }
        started.destroyForcibly()
        throw IOException("The graph server didn't start in time. ${lastLogLine()}")
    }

    /** Asks the server to finish writing and exit. */
    @Synchronized
    fun stop() {
        try {
            connect().use { connection -> connection.call("SHUTDOWN") }
        } catch (expected: IOException) {
            // The connection closing is how SHUTDOWN succeeds, and a server that's already gone needs nothing more.
        } catch (error: RedisError) {
            process?.destroyForcibly()
        }
        process?.waitFor()
        process = null
    }

    /** Ends the server at once, as a crash would. Whatever hadn't reached disk is lost. */
    @Synchronized
    fun kill() {
        process?.destroyForcibly()?.waitFor()
        process = null
        socket.delete()
    }

    fun connect(): FalkorConnection {
        val local = LocalSocket()
        try {
            local.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            local.soTimeout = READ_TIMEOUT_MILLIS
        } catch (error: IOException) {
            local.close()
            throw error
        }
        return FalkorConnection(local)
    }

    private fun answers(): Boolean =
        try {
            connect().use { it.call("PING") == "PONG" }
        } catch (notUp: IOException) {
            false
        } catch (stillLoading: RedisError) {
            false
        }

    private fun command(): List<String> {
        val links = File(filesDir, "falkor-libs").apply {
            deleteRecursively()
            mkdirs()
        }
        // A new install moves the native folder, so these are rebuilt on every start.
        for ((packaged, real) in LIBRARIES) Os.symlink("$nativeDir/$packaged", File(links, real).path)
        return listOf(
            "$nativeDir/libfalkor_launcher.so", "$nativeDir/libfalkor_loader.so", "--library-path", links.path,
            "$nativeDir/libfalkor_redis.so",
            "--port", "0",
            "--unixsocket", socket.path,
            "--unixsocketperm", "700",
            "--dir", dataDir.path,
            "--save", "",
            "--appendonly", "yes",
            "--appendfsync", "everysec",
            "--loadmodule", "$nativeDir/libfalkor_module.so", "TEMP_FOLDER", cacheDir.path,
        )
    }

    private fun lastLogLine(): String =
        logFile.takeIf { it.isFile }?.readLines()?.lastOrNull { !it.startsWith("[launcher]") }?.take(200).orEmpty()

    private companion object {
        const val START_ATTEMPTS = 300
        const val START_WAIT_MILLIS = 100L
        const val READ_TIMEOUT_MILLIS = 60_000
        val LIBRARIES = mapOf(
            "libfalkor_c.so" to "libc.so.6",
            "libfalkor_m.so" to "libm.so.6",
            "libfalkor_gcc.so" to "libgcc_s.so.1",
            "libfalkor_stdcpp.so" to "libstdc++.so.6",
            "libfalkor_ssl.so" to "libssl.so.3",
            "libfalkor_crypto.so" to "libcrypto.so.3",
            "libfalkor_z.so" to "libz.so.1",
            "libfalkor_zstd.so" to "libzstd.so.1",
        )
    }
}

class FalkorConnection internal constructor(private val socket: LocalSocket) : AutoCloseable {
    private val client = RespClient(socket.inputStream, socket.outputStream)

    fun call(vararg arguments: String): Any? = client.call(*arguments)

    override fun close() = socket.close()
}

/** Sends commands over one connection, starting the server or reconnecting when needed, for instance after Android freezes the app. */
class FalkorClient(private val server: FalkorServer) : AutoCloseable {
    private var connection: FalkorConnection? = null

    @Synchronized
    fun call(vararg arguments: String): Any? {
        try {
            return connected().call(*arguments)
        } catch (timedOut: InterruptedIOException) {
            // The server may still be working on this command, so running it again could apply it twice.
            disconnect()
            throw timedOut
        } catch (lost: IOException) {
            disconnect()
            server.start()
            return connected().call(*arguments)
        }
    }

    @Synchronized
    override fun close() = disconnect()

    private fun connected(): FalkorConnection =
        connection ?: try {
            server.connect()
        } catch (notRunning: IOException) {
            server.start()
            server.connect()
        }.also { connection = it }

    private fun disconnect() {
        try {
            connection?.close()
        } catch (ignored: IOException) {
            // It's being thrown away either way.
        }
        connection = null
    }
}
