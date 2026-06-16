package com.nothing.filetransformer.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

class FileRepository(private val context: Context) {

    companion object {
        private const val BUFFER_SIZE = 8192
    }

    fun save(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        saveLocationType: String,
        customTreeUri: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Uri? {
        val tracked = if (onProgress != null && totalBytes > 0) {
            ProgressInputStream(inputStream, totalBytes, onProgress)
        } else {
            inputStream
        }

        return when (saveLocationType) {
            PreferencesManager.LOCATION_TYPE_CUSTOM -> {
                if (customTreeUri.isNotEmpty()) {
                    saveToDocumentTree(tracked, fileName, mimeType, Uri.parse(customTreeUri))
                } else {
                    saveToDownloads(tracked, fileName, mimeType)
                }
            }
            else -> saveToDownloads(tracked, fileName, mimeType)
        }
    }

    private fun saveToDownloads(input: InputStream, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out, BUFFER_SIZE) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToDocumentTree(input: InputStream, fileName: String, mimeType: String, treeUri: Uri): Uri? {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val created = doc.createFile(mimeType, fileName) ?: return null
        context.contentResolver.openOutputStream(created.uri)?.use { out -> input.copyTo(out, BUFFER_SIZE) }
        return created.uri
    }

    fun guessMimeType(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else "application/octet-stream"
    }
}
