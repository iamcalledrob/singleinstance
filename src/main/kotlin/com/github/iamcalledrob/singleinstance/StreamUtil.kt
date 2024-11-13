package com.github.iamcalledrob.singleinstance

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

// Utils for reading/writing Ints to a stream, because
// OutputStream.write(value: Int) *silently truncates* the value to 1 byte. Sigh.

internal fun OutputStream.writeInt(value: Int) {
    val byteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
    write(byteArray)
}

internal fun InputStream.readInt(): Int {
    val byteArray = readNBytes(Int.SIZE_BYTES)
    return ByteBuffer.wrap(byteArray).getInt()
}