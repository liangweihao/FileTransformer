package com.nothing.filetransformer.server

import com.nothing.filetransformer.storage.FileRepository
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
        const val MIME_OCTET = "application/octet-stream"

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

    private val uploadLock = AtomicBoolean(false)

    @Volatile var onUploadProgress: ((UploadProgress) -> Unit)? = null

    /** Callback when browser pushes clipboard text to phone. */
    @Volatile var onClipboardReceived: ((String) -> Unit)? = null

    // -- Shared state (phone → browser) --

    /** Files the phone user wants to share (displayName → absolutePath). */
    val sharedFiles = ConcurrentHashMap<String, String>()

    /** Clipboard text pushed from phone to be shown on the web page. */
    @Volatile var phoneClipboardText: String = ""

    private val uploadHandler = UploadHandler(fileRepository)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/')
            when {
                // Web UI
                session.method == Method.GET && (uri == "" || uri == "/index.html") ->
                    serveWebUi(session)

                // Favicon
                session.method == Method.GET && session.uri == "/favicon.ico" ->
                    newFixedLengthResponse(Response.Status.OK, "image/svg+xml", FAVICON_SVG)

                // Upload (browser → phone)
                session.method == Method.POST && uri == "/upload" ->
                    handleUpload(session)

                // List shared files (phone → browser)
                session.method == Method.GET && uri == "/files" ->
                    serveFileList()

                // Download shared file (phone → browser)
                session.method == Method.GET && uri.startsWith("/download/") ->
                    serveDownload(uri)

                // Clipboard: browser → phone
                session.method == Method.POST && uri == "/clipboard" ->
                    receiveClipboard(session)

                // Clipboard: phone → browser (poll)
                session.method == Method.GET && uri == "/clipboard" ->
                    serveClipboard()

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

    // ── Web UI ──────────────────────────────────────────────

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

    // ── Upload (browser → phone) ────────────────────────────

    private fun handleUpload(session: IHTTPSession): Response {
        if (!uploadLock.compareAndSet(false, true)) {
            val json = JSONObject().apply {
                put("success", false)
                put("error", "Another upload is in progress.")
            }
            return newFixedLengthResponse(Response.Status.CONFLICT, MIME_JSON, json.toString())
        }
        try {
            val progressListener = onUploadProgress
            val result = uploadHandler.handleUpload(
                session, saveLocationType, customTreeUri
            ) { progress -> progressListener?.invoke(progress) }

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

    // ── Download (phone → browser) ──────────────────────────

    private fun serveFileList(): Response {
        val arr = JSONArray()
        for ((name, path) in sharedFiles) {
            val f = java.io.File(path)
            arr.put(JSONObject().apply {
                put("name", name)
                put("size", f.length())
            })
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, arr.toString())
    }

    private fun serveDownload(uri: String): Response {
        val encodedName = uri.substringAfter("/download/")
        val fileName = java.net.URLDecoder.decode(encodedName, "UTF-8")
        val path = sharedFiles[fileName] ?: run {
            // Try case-insensitive match
            sharedFiles.entries.firstOrNull { it.key.equals(fileName, ignoreCase = true) }?.value
        }
        if (path == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"File not found"}"""
            )
        }
        val file = java.io.File(path)
        if (!file.exists()) {
            sharedFiles.remove(fileName)
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"error":"File no longer exists"}"""
            )
        }
        try {
            val fis = FileInputStream(file)
            val mime = fileRepository.guessMimeType(fileName)
            return newChunkedResponse(Response.Status.OK, mime, fis)
        } catch (e: IOException) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Failed to read file: ${e.message}"
            )
        }
    }

    /** Add a file to the shared list. */
    fun addSharedFile(displayName: String, absolutePath: String) {
        sharedFiles[displayName] = absolutePath
    }

    /** Remove a file from the shared list. */
    fun removeSharedFile(displayName: String) {
        sharedFiles.remove(displayName)
    }

    /** Clear all shared files. */
    fun clearSharedFiles() {
        sharedFiles.clear()
    }

    // ── Clipboard (bidirectional) ────────────────────────────

    private fun receiveClipboard(session: IHTTPSession): Response {
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val text = session.parms?.get("text") ?: ""
            if (text.isNotBlank()) {
                onClipboardReceived?.invoke(text)
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"success":true}""")
            }
        } catch (_: Exception) {}
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON, """{"success":false,"error":"Empty text"}"""
        )
    }

    private fun serveClipboard(): Response {
        val text = phoneClipboardText
        return newFixedLengthResponse(
            Response.Status.OK, MIME_JSON,
            JSONObject().apply { put("text", text) }.toString()
        )
    }
}
