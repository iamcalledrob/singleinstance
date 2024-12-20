package com.github.iamcalledrob.singleinstance

import kotlinx.coroutines.*
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.*
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.system.exitProcess

class SingleInstance(
    private val socketPath: String,
    private val onExit: () -> Unit = { exitProcess(0) },
) {

    fun args(args: Array<String>, onArgsReceived: suspend (Array<String>) -> Unit) {
        // Ensure the parent folder of `socketPath` exists, otherwise attempts to write the lock (and sock)
        // wil throw. Prevents a crash-at-launch-but-not-on-dev-machine issue.
        File(socketPath).parentFile.mkdirs()

        // There is a race condition here, where another instance holds the lock, but terminates before dial
        // is able to complete successfully.
        //
        // This is intentionally unhandled because this situation could happen even if dial succeeds -- the dial
        // could succeed just *before* the other instance terminates.
        try {
            // Throws AlreadyLockedException if lock can't be acquired
            val listener = tryListen()

            // Listening, accept incoming dials indefinitely
            CoroutineScope(Dispatchers.IO).launch {
                accept(listener, onArgsReceived)
            }

        } catch (e: AlreadyLockedException) {
            // Another instance is listening, dial and send args
            dial(args)
            onExit()
        }
    }

    private fun tryListen(): ServerSocketChannel {
        // Acquire an exclusive lock, which will be auto-released by the OS on process death.
        // Lock protects the domain socket
        val lockPath = "$socketPath.lock"
        val fileChannel = FileChannel.open(Path(lockPath), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        lock = fileChannel.tryLock() ?: throw AlreadyLockedException(lockPath)

        // Lock acquired, no other instances are running. Safe to clean up existing socket to allow for bind.
        File(socketPath).delete()

        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val address = UnixDomainSocketAddress.of(socketPath)
        channel.bind(address)

        return channel
    }
    // Keep a reference to the lock, which prevents the channel from being auto-closed (which would release the lock)
    private var lock: FileLock? = null

    private fun dial(args: Array<String>) {
        val address = UnixDomainSocketAddress.of(socketPath)
        val channel = SocketChannel.open(address)
        writeArgs(channel, args)
    }

    private fun accept(listener: ServerSocketChannel, onArgsReceived: suspend (Array<String>) -> Unit) {
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
            stream.writeInt(args.size)
            for (arg in args) {
                val bytes = arg.toByteArray(Charsets.UTF_8)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
        }
    }

    private fun readArgs(channel: SocketChannel): Array<String> {
        Channels.newInputStream(channel).use { stream ->
            val rcvdArgs = mutableListOf<String>()
            val count = stream.readInt()

            // Sanity check
            check(count in 0..1024) { "arg count out of range: $count" }

            repeat(count) {
                val len = stream.readInt()
                check(len in 1..1024) { "arg len out of range: $len" }

                val arg = stream.readNBytes(len).toString(Charsets.UTF_8)
                rcvdArgs.add(arg)
            }

            return rcvdArgs.toTypedArray()
        }
    }
}

internal class AlreadyLockedException(path: String) : Exception("$path already locked")

/** Provides a socket path in the temp dir for the provided identifier.
 *  Identifier must not contain characters which are unsuitable for a file name.
 *
 *  Note: no guarantee is made that the result will be short enough to satisfy UNIX_PATH_MAX.
 *  This can be a problem for packaged Windows MSIX apps, which may use a very long virtualized java.io.tmpdir path.
 *  A future version may improve handling or provide error checking here.
 **/
fun socketPath(identifier: String): String =
    System.getProperty("java.io.tmpdir") + File.separator + identifier + ".sock"
