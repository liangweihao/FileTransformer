package com.nothing.filetransformer.server

import com.nothing.filetransformer.storage.FileRepository
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class FileTransferServer(
    port: Int,
    private val fileRepository: FileRepository,
    private val webUiPages: Map<String, String>,
    private val defaultLang: String = "zh"
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIME_HTML = "text/html; charset=utf-8"
        const val MIME_JSON = "application/json; charset=utf-8"
        const val MIME_PLAINTEXT = "text/plain; charset=utf-8"

        val FAVICON_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
              <rect width="32" height="32" rx="4" fill="#222"/>
              <path d="M16 6L8 14h5v7h6v-7h5L16 6z" fill="white"/>
              <path d="M8 22v3h16v-3H8z" fill="white" opacity="0.6"/>
            </svg>
        """.trimIndent()
    }

    @Volatile var saveLocationType: String = "downloads"
    @Volatile var customTreeUri: String = ""

    /** Ensures only one upload is processed at a time. */
    private val uploadLock = AtomicBoolean(false)

    /** Callback invoked during file upload with progress updates. */
    @Volatile var onUploadProgress: ((UploadProgress) -> Unit)? = null

    private val uploadHandler = UploadHandler(fileRepository)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/')
            when {
                session.method == Method.GET && (uri == "" || uri == "/index.html") ->
                    serveWebUi(session)

                session.method == Method.GET && session.uri == "/favicon.ico" ->
                    newFixedLengthResponse(Response.Status.OK, "image/svg+xml", FAVICON_SVG)

                session.method == Method.POST && uri == "/upload" ->
                    handleUpload(session)

                else ->
                    newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Internal Server Error: ${e.message}"
            )
        }
    }

    private fun serveWebUi(session: IHTTPSession): Response {
        val html = detectLanguage(session).let { lang ->
            webUiPages[lang] ?: webUiPages[defaultLang] ?: webUiPages.values.firstOrNull() ?: ""
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun detectLanguage(session: IHTTPSession): String {
        val acceptLang = session.headers["accept-language"] ?: return defaultLang
        val firstLang = acceptLang.split(",").firstOrNull()?.trim() ?: return defaultLang
        val primary = firstLang.split("-").firstOrNull()?.split(";")?.firstOrNull()?.trim()?.lowercase()
            ?: return defaultLang
        return when (primary) { "zh" -> "zh"; "en" -> "en"; else -> defaultLang }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        // Single-file-at-a-time: reject concurrent uploads
        if (!uploadLock.compareAndSet(false, true)) {
            val json = JSONObject().apply {
                put("success", false)
                put("error", "Another upload is in progress. Please wait.")
            }
            return newFixedLengthResponse(
                Response.Status.CONFLICT, MIME_JSON, json.toString()
            )
        }

        try {
            val progressListener = onUploadProgress
            val result = uploadHandler.handleUpload(
                session, saveLocationType, customTreeUri
            ) { progress ->
                progressListener?.invoke(progress)
            }

            val json = JSONObject().apply {
                put("success", result.success)
                put("files", JSONArray().apply {
                    put(JSONObject().apply {
                        put("fileName", result.fileName)
                        put("success", result.success)
                        put("bytesWritten", result.bytesWritten)
                        if (result.error != null) put("error", result.error)
                    })
                })
            }

            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
        } finally {
            uploadLock.set(false)
        }
    }
}
