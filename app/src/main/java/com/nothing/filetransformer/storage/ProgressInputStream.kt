package com.nothing.filetransformer.storage

import java.io.InputStream

/**
 * Wraps an [InputStream] and reports progress via [onProgress] callback.
 * The callback receives (bytesRead, totalBytes) on each read.
 */
class ProgressInputStream(
    private val delegate: InputStream,
    private val totalBytes: Long,
    private val onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
) : InputStream() {

    private var bytesRead: Long = 0

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) {
            bytesRead++
            onProgress(bytesRead, totalBytes)
        }
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate.read(buffer, offset, length)
        if (count > 0) {
            bytesRead += count
            onProgress(bytesRead, totalBytes)
        }
        return count
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
    override fun markSupported(): Boolean = false
}
