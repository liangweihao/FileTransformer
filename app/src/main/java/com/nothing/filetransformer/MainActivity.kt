package com.nothing.filetransformer

import android.content.*
import android.content.res.ColorStateList
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothing.filetransformer.databinding.ActivityMainBinding
import com.nothing.filetransformer.server.UploadProgress
import com.nothing.filetransformer.server.UploadStatus
import com.nothing.filetransformer.service.ServerForegroundService
import com.nothing.filetransformer.storage.HistoryRecord
import com.nothing.filetransformer.storage.PreferencesManager
import com.nothing.filetransformer.storage.UploadHistoryManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var historyManager: UploadHistoryManager
    private var service: ServerForegroundService? = null
    private var bound = false

    private val recentUploads = mutableListOf<HistoryRecord>()
    private lateinit var uploadsAdapter: UploadsAdapter

    // Shared files (phone → browser)
    private val sharedFiles = mutableListOf<SharedFile>()
    private lateinit var sharedFilesAdapter: SharedFilesAdapter

    data class SharedFile(val name: String, val uri: Uri, val size: Long)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ServerForegroundService.LocalBinder ?: return
            service = localBinder.getService()
            bound = true
            observeServerState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false
        }
    }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                lifecycleScope.launch {
                    preferencesManager.setCustomTreeUri(uri)
                    binding.saveLocationText.setText(R.string.location_custom)
                    service?.updateSaveLocation(PreferencesManager.LOCATION_TYPE_CUSTOM, uri.toString())
                }
            }
        }

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            for (uri in uris) {
                val name = getFileName(uri) ?: "unknown"
                val size = getFileSize(uri)
                // Copy file to app cache so it's accessible by the server
                val cacheFile = java.io.File(cacheDir, name)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    sharedFiles.add(SharedFile(name, Uri.fromFile(cacheFile), cacheFile.length()))
                    service?.addSharedFile(name, cacheFile.absolutePath)
                } catch (_: Exception) {}
            }
            sharedFilesAdapter.notifyDataSetChanged()
            updateSharedFilesVisibility()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager(this)
        historyManager = UploadHistoryManager(this)

        recentUploads.addAll(historyManager.getAll())
        uploadsAdapter = UploadsAdapter(recentUploads)
        sharedFilesAdapter = SharedFilesAdapter(sharedFiles)

        setupRecyclerViews()
        setupSwipeToDelete()
        setupButtons()
        updateEmptyState()
        updateSharedFilesVisibility()

        bindService(Intent(this, ServerForegroundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        lifecycleScope.launch {
            if (preferencesManager.saveLocationType.first() == PreferencesManager.LOCATION_TYPE_CUSTOM)
                binding.saveLocationText.setText(R.string.location_custom)
        }
    }

    private fun setupRecyclerViews() {
        binding.recentUploadsList.layoutManager = LinearLayoutManager(this)
        binding.recentUploadsList.adapter = uploadsAdapter

        binding.sharedFilesList.layoutManager = LinearLayoutManager(this)
        binding.sharedFilesList.adapter = sharedFilesAdapter
    }

    private fun setupSwipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (pos < 0 || pos >= recentUploads.size) return
                historyManager.delete(recentUploads[pos].id)
                recentUploads.removeAt(pos)
                uploadsAdapter.notifyItemRemoved(pos)
                updateEmptyState()
            }
        }).attachToRecyclerView(binding.recentUploadsList)
    }

    private fun setupButtons() {
        binding.startStopButton.setOnClickListener {
            if (service?.serverState?.value?.isRunning == true) stopServer() else startServer()
        }

        binding.copyLinkButton.setOnClickListener {
            val s = service?.serverState?.value ?: return@setOnClickListener
            if (s.isRunning) {
                val c = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                c.setPrimaryClip(ClipData.newPlainText("URL", "http://${s.ipAddress}:${s.port}"))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        binding.changeLocationButton.setOnClickListener { openDocumentTreeLauncher.launch(null) }

        // Add file to share
        binding.addSendFileButton.setOnClickListener { pickFilesLauncher.launch(arrayOf("*/*")) }

        // Push clipboard to browser
        binding.pushClipboardButton.setOnClickListener {
            val text = binding.clipboardInput.text.toString().trim()
            if (text.isNotEmpty()) {
                service?.pushClipboardToBrowser(text)
                Toast.makeText(this, R.string.clipboard_pushed, Toast.LENGTH_SHORT).show()
                binding.clipboardInput.text.clear()
            }
        }
    }

    private fun startServer() {
        startForegroundService(Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_START
        })
    }

    private fun stopServer() {
        startService(Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_STOP
        })
        // Clear shared files when stopping
        sharedFiles.clear()
        service?.clearSharedFiles()
        sharedFilesAdapter.notifyDataSetChanged()
        updateSharedFilesVisibility()
    }

    private fun observeServerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service?.serverState?.collect { state -> updateUi(state) }
            }
        }
    }

    private fun updateUi(state: ServerForegroundService.ServerState) {
        if (state.isRunning) {
            binding.statusIndicator.backgroundTintList =
                ColorStateList.valueOf(resources.getColor(R.color.status_running, theme))
            binding.statusText.setText(R.string.status_running)
            binding.ipAddressText.visibility = View.VISIBLE
            binding.ipAddressText.text = "http://${state.ipAddress}:${state.port}"
            binding.copyLinkButton.visibility = View.VISIBLE
            binding.startStopButton.setText(R.string.stop_server)
            binding.sendFilesCard.visibility = View.VISIBLE
            binding.clipboardCard.visibility = View.VISIBLE
        } else {
            binding.statusIndicator.backgroundTintList =
                ColorStateList.valueOf(resources.getColor(R.color.status_stopped, theme))
            binding.statusText.setText(R.string.status_stopped)
            binding.ipAddressText.visibility = View.GONE
            binding.copyLinkButton.visibility = View.GONE
            binding.startStopButton.setText(R.string.start_server)
            binding.receivingCard.visibility = View.GONE
            binding.sendFilesCard.visibility = View.GONE
            binding.clipboardCard.visibility = View.GONE
        }

        // Clipboard received from browser → copy to system clipboard
        if (state.receivedClipboardText.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("from browser", state.receivedClipboardText))
            Toast.makeText(this,
                getString(R.string.clipboard_received, state.receivedClipboardText.take(30)),
                Toast.LENGTH_SHORT).show()
            service?.clearReceivedClipboard()
        }

        updateReceivingProgress(state.uploadProgress)
    }

    private fun updateReceivingProgress(progress: UploadProgress) {
        when (progress.status) {
            UploadStatus.IDLE -> { binding.receivingCard.visibility = View.GONE }
            UploadStatus.RECEIVING, UploadStatus.SAVING -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_saving)
                val pct = if (progress.totalBytes > 0) (progress.bytesSoFar * 100 / progress.totalBytes).toInt() else 0
                binding.receivingProgressBar.progress = pct
                binding.receivingProgressText.text = "${formatSize(progress.bytesSoFar)} / ${formatSize(progress.totalBytes)}"
            }
            UploadStatus.COMPLETE -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_complete)
                binding.receivingProgressBar.progress = 100
                binding.receivingProgressText.text = formatSize(progress.totalBytes)
                if (progress.fileName.isNotEmpty() && progress.totalBytes > 0) {
                    val r = historyManager.add(progress.fileName, progress.totalBytes)
                    recentUploads.add(0, r)
                    uploadsAdapter.notifyDataSetChanged()
                    updateEmptyState()
                }
                binding.receivingCard.postDelayed({
                    if (service?.serverState?.value?.uploadProgress?.status == UploadStatus.COMPLETE)
                        binding.receivingCard.visibility = View.GONE
                }, 3000)
            }
            UploadStatus.ERROR -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_error)
                binding.receivingProgressBar.progress = 0
                binding.receivingProgressText.text = ""
                binding.receivingCard.postDelayed({
                    if (service?.serverState?.value?.uploadProgress?.status == UploadStatus.ERROR)
                        binding.receivingCard.visibility = View.GONE
                }, 3000)
            }
        }
    }

    private fun updateEmptyState() {
        binding.noUploadsText.visibility = if (recentUploads.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSharedFilesVisibility() {
        binding.sharedFilesList.visibility = if (sharedFiles.isEmpty()) View.GONE else View.VISIBLE
        binding.noSharedFilesText.visibility = if (sharedFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        if (name == null) name = uri.lastPathSegment
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) size = cursor.getLong(idx)
            }
        }
        return size
    }

    override fun onDestroy() {
        if (bound) { unbindService(serviceConnection); bound = false }
        super.onDestroy()
    }

    // ── Adapters ─────────────────────────────────────────────

    inner class UploadsAdapter(private val items: List<HistoryRecord>) :
        RecyclerView.Adapter<UploadsAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_upload_record, parent, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(items[p])
        override fun getItemCount() = items.size
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val n: TextView = itemView.findViewById(R.id.uploadFileName)
            private val s: TextView = itemView.findViewById(R.id.uploadFileSize)
            private val t: TextView = itemView.findViewById(R.id.uploadTimestamp)
            private val d: ImageButton = itemView.findViewById(R.id.uploadDeleteBtn)
            fun bind(r: HistoryRecord) {
                n.text = r.fileName; s.text = formatSize(r.fileSize); t.text = formatTimestamp(r.timestamp)
                d.setOnClickListener {
                    val pos = adapterPosition; if (pos < 0 || pos >= items.size) return@setOnClickListener
                    historyManager.delete(items[pos].id); recentUploads.removeAt(pos)
                    notifyItemRemoved(pos); updateEmptyState()
                }
                d.setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.delete_confirm_title))
                        .setMessage(getString(R.string.delete_confirm_msg, r.fileName))
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            val pos = adapterPosition; if (pos < 0 || pos >= items.size) return@setPositiveButton
                            historyManager.delete(items[pos].id); recentUploads.removeAt(pos)
                            notifyItemRemoved(pos); updateEmptyState()
                        }.setNegativeButton(getString(R.string.cancel), null).show()
                    true
                }
            }
        }
    }

    inner class SharedFilesAdapter(private val items: List<SharedFile>) :
        RecyclerView.Adapter<SharedFilesAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_shared_file, parent, false))
        override fun onBindViewHolder(h: VH, p: Int) = h.bind(items[p])
        override fun getItemCount() = items.size
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val n: TextView = itemView.findViewById(R.id.sharedFileName)
            private val s: TextView = itemView.findViewById(R.id.sharedFileSize)
            private val d: ImageButton = itemView.findViewById(R.id.sharedFileDelete)
            fun bind(f: SharedFile) {
                n.text = f.name; s.text = formatSize(f.size)
                d.setOnClickListener {
                    val pos = adapterPosition; if (pos < 0 || pos >= items.size) return@setOnClickListener
                    val sf = items[pos]
                    service?.removeSharedFile(sf.name)
                    java.io.File(cacheDir, sf.name).delete()
                    sharedFiles.removeAt(pos)
                    notifyItemRemoved(pos); updateSharedFilesVisibility()
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1073741824 -> "${"%.1f".format(bytes / 1048576.0)} MB"
        else -> "${"%.1f".format(bytes / 1073741824.0)} GB"
    }

    private fun formatTimestamp(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000 -> getString(R.string.time_just_now)
            diff < 3600_000 -> getString(R.string.time_min_ago, diff / 60_000)
            diff < 86400_000 -> getString(R.string.time_hour_ago, diff / 3600_000)
            else -> getString(R.string.time_day_ago, diff / 86400_000)
        }
    }
}
