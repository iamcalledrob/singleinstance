package com.github.iamcalledrob.singleinstance

import kotlinx.coroutines.*
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.system.exitProcess

class SingleInstance(
    private val socketPath: String,
    private val onExit: () -> Unit = { exitProcess(0) },
) {

    fun args(args: Array<String>, onArgsReceived: (Array<String>) -> Unit) {
        // There is a race condition here, where another instance holds the lock, but terminates before dial
        // is able to complete successfully.
        //
        // This is intentionally unhandled because this situation could happen even if dial succeeds -- the dial
        // could succeed just *before* the other instance terminates.
        tryListen()?.let { listener ->
            // Listening, accept incoming dials indefinitely
            CoroutineScope(Dispatchers.IO).launch {
                accept(listener, onArgsReceived)
            }
        } ?: run {
            // Another instance is listening, dial and send args
            dial(args)
            onExit()
        }
    }

    private fun tryListen(): ServerSocketChannel? {
        // Acquire an exclusive lock, which will be auto-released by the OS on process death.
        // Lock protects the domain socket
        val lockPath = "$socketPath.lock"
        FileChannel.open(Path(lockPath), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            .tryLock() ?: return null


        // Lock acquired, no other instances are running. Safe to clean up existing socket to allow for bind.
        File(socketPath).delete()

        return ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
        }
    }

    private fun dial(args: Array<String>) {
        writeArgs(SocketChannel.open(UnixDomainSocketAddress.of(socketPath)), args)
    }

    private fun accept(listener: ServerSocketChannel, onArgsReceived: (Array<String>) -> Unit) {
        while (true) {
            listener.accept().let { channel ->
                // Use a separate coroutine so that a misbehaving peer can't DOS others.
                CoroutineScope(Dispatchers.IO).launch {
                    val rcvdArgs = readArgs(channel)
                    onArgsReceived(rcvdArgs)
                }
            }
        }
    }

    // Wire format:
    // [Number of args]
    // [Length of arg]
    // [Arg value]
    // [Length of arg]
    // ...

    private fun writeArgs(channel: SocketChannel, args: Array<String>) {
        Channels.newOutputStream(channel).use { stream ->
            stream.write(args.size)
            for (arg in args) {
                val bytes = arg.toByteArray(Charsets.UTF_8)
                stream.write(bytes.size)
                stream.write(bytes)
            }
        }
    }

    private fun readArgs(channel: SocketChannel): Array<String> {
        Channels.newInputStream(channel).use { stream ->
            val rcvdArgs = mutableListOf<String>()
            val count = stream.readNBytes(1).first().toInt()

            // Sanity check
            check(count <= 1024) { "arg count out of range: $count" }

            repeat(count) {
                val len = stream.readNBytes(1).first().toInt()
                check(len <= 1024) { "arg len out of range: $len" }

                val arg = stream.readNBytes(len).toString(Charsets.UTF_8)
                rcvdArgs.add(arg)
            }

            return rcvdArgs.toTypedArray()
        }
    }
}

/** Provides a socket path in the temp dir for the provided identifier.
 *  Identifier must not contain characters which are unsuitable for a file name. */
fun socketPath(identifier: String): String =
    System.getProperty("java.io.tmpdir") + File.separator + identifier + ".sock"
