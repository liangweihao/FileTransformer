package com.nothing.filetransformer.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.nothing.filetransformer.network.NetworkUtils
import com.nothing.filetransformer.server.FileTransferServer
import com.nothing.filetransformer.server.UploadProgress
import com.nothing.filetransformer.server.UploadStatus
import com.nothing.filetransformer.storage.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.nothing.filetransformer.action.START"
        const val ACTION_STOP = "com.nothing.filetransformer.action.STOP"
        private const val TAG = "ServerForegroundSvc"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ServerForegroundService = this@ServerForegroundService
    }

    private val binder = LocalBinder()
    private var server: FileTransferServer? = null

    data class ServerState(
        val isRunning: Boolean = false,
        val ipAddress: String = "",
        val port: Int = FileTransferServer.DEFAULT_PORT,
        val uploadProgress: UploadProgress = UploadProgress()
    )

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return

        val ip = NetworkUtils.getPrimaryLocalIp() ?: run {
            Log.e(TAG, "No LAN IP available")
            _serverState.value = ServerState(isRunning = false)
            stopSelf()
            return
        }

        val port = findAvailablePort(startingPort = FileTransferServer.DEFAULT_PORT, maxTries = 10)

        val webUiPages = mutableMapOf<String, String>()
        try {
            webUiPages["zh"] = assets.open("web/upload-zh.html").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load upload-zh.html", e)
        }
        try {
            webUiPages["en"] = assets.open("web/upload-en.html").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load upload-en.html", e)
        }
        if (webUiPages.isEmpty()) {
            webUiPages["zh"] = "<html><body><h1>Error loading upload page</h1></body></html>"
        }

        val fileRepository = FileRepository(this)

        server = FileTransferServer(port, fileRepository, webUiPages, defaultLang = "zh").apply {
            saveLocationType = "downloads"
            customTreeUri = ""

            // Wire progress callback to update the StateFlow
            onUploadProgress = { progress ->
                val current = _serverState.value
                _serverState.value = current.copy(uploadProgress = progress)

                // Add completed uploads to recent list
                if (progress.status == UploadStatus.COMPLETE && progress.totalBytes > 0) {
                    Log.i(TAG, "Upload complete: ${progress.fileName} (${progress.totalBytes} bytes)")
                }
            }

            try {
                start()
                Log.i(TAG, "Server started on port $port")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server on port $port", e)
                _serverState.value = ServerState(isRunning = false)
                stopSelf()
                return@apply
            }
        }

        val notification = NotificationHelper.buildNotification(this, ip, port)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        _serverState.value = ServerState(isRunning = true, ipAddress = ip, port = port)
    }

    private fun stopServer() {
        server?.stop()
        server = null
        _serverState.value = ServerState(isRunning = false)
        Log.i(TAG, "Server stopped")
    }

    fun updateSaveLocation(saveLocationType: String, customTreeUri: String) {
        server?.saveLocationType = saveLocationType
        server?.customTreeUri = customTreeUri
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun findAvailablePort(startingPort: Int, maxTries: Int): Int = startingPort
}
