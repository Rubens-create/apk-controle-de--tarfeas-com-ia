package com.taskai.app.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded stream reading using APIs available on Android 8.0, unlike InputStream.readNBytes. */
internal object BackupInput {
    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            if (size == -1) return output.toByteArray()
            require(size <= maxBytes - output.size()) { "O backup excede o tamanho máximo permitido." }
            output.write(buffer, 0, size)
        }
    }
}
