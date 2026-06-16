package com.nothing.filetransformer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ServerForegroundService.LocalBinder ?: return
            service = localBinder.getService()
            bound = true
            observeServerState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                lifecycleScope.launch {
                    preferencesManager.setCustomTreeUri(uri)
                    binding.saveLocationText.setText(R.string.location_custom)
                    service?.updateSaveLocation(
                        PreferencesManager.LOCATION_TYPE_CUSTOM,
                        uri.toString()
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager(this)
        historyManager = UploadHistoryManager(this)

        // Load persisted history
        recentUploads.addAll(historyManager.getAll())
        uploadsAdapter = UploadsAdapter(recentUploads)

        setupRecyclerView()
        setupSwipeToDelete()
        setupButtons()
        updateEmptyState()

        bindService(
            Intent(this, ServerForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        lifecycleScope.launch {
            val locationType = preferencesManager.saveLocationType.first()
            if (locationType == PreferencesManager.LOCATION_TYPE_CUSTOM) {
                binding.saveLocationText.setText(R.string.location_custom)
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recentUploadsList.layoutManager = LinearLayoutManager(this)
        binding.recentUploadsList.adapter = uploadsAdapter
    }

    private fun setupSwipeToDelete() {
        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position < 0 || position >= recentUploads.size) return
                val record = recentUploads[position]
                historyManager.delete(record.id)
                recentUploads.removeAt(position)
                uploadsAdapter.notifyItemRemoved(position)
                updateEmptyState()
            }
        })
        swipeHelper.attachToRecyclerView(binding.recentUploadsList)
    }

    private fun setupButtons() {
        binding.startStopButton.setOnClickListener {
            if (service?.serverState?.value?.isRunning == true) {
                stopServer()
            } else {
                startServer()
            }
        }

        binding.copyLinkButton.setOnClickListener {
            val state = service?.serverState?.value ?: return@setOnClickListener
            if (state.isRunning) {
                val url = "http://${state.ipAddress}:${state.port}"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", url))
            }
        }

        binding.changeLocationButton.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }
    }

    private fun startServer() {
        val intent = Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopServer() {
        // Use startService (not stopService) so ACTION_STOP reaches onStartCommand().
        // stopService() skips onStartCommand and onDestroy() won't fire while bound.
        val intent = Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun observeServerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service?.serverState?.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    private fun updateUi(state: ServerForegroundService.ServerState) {
        if (state.isRunning) {
            binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(
                resources.getColor(R.color.status_running, theme)
            )
            binding.statusText.setText(R.string.status_running)
            binding.ipAddressText.visibility = View.VISIBLE
            binding.ipAddressText.text = "http://${state.ipAddress}:${state.port}"
            binding.copyLinkButton.visibility = View.VISIBLE
            binding.startStopButton.setText(R.string.stop_server)
        } else {
            binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(
                resources.getColor(R.color.status_stopped, theme)
            )
            binding.statusText.setText(R.string.status_stopped)
            binding.ipAddressText.visibility = View.GONE
            binding.copyLinkButton.visibility = View.GONE
            binding.startStopButton.setText(R.string.start_server)
            binding.receivingCard.visibility = View.GONE
        }

        updateReceivingProgress(state.uploadProgress)
    }

    private fun updateReceivingProgress(progress: UploadProgress) {
        when (progress.status) {
            UploadStatus.IDLE -> {
                binding.receivingCard.visibility = View.GONE
            }
            UploadStatus.RECEIVING, UploadStatus.SAVING -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_saving)

                val pct = if (progress.totalBytes > 0) {
                    (progress.bytesSoFar * 100 / progress.totalBytes).toInt()
                } else 0
                binding.receivingProgressBar.progress = pct
                binding.receivingProgressText.text =
                    "${formatSize(progress.bytesSoFar)} / ${formatSize(progress.totalBytes)}"
            }
            UploadStatus.COMPLETE -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_complete)
                binding.receivingProgressBar.progress = 100
                binding.receivingProgressText.text = formatSize(progress.totalBytes)

                if (progress.fileName.isNotEmpty() && progress.totalBytes > 0) {
                    val record = historyManager.add(progress.fileName, progress.totalBytes)
                    recentUploads.add(0, record)
                    uploadsAdapter.notifyItemInserted(0)
                    updateEmptyState()
                }

                binding.receivingCard.postDelayed({
                    if (binding.receivingCard.visibility == View.VISIBLE &&
                        service?.serverState?.value?.uploadProgress?.status == UploadStatus.COMPLETE
                    ) {
                        binding.receivingCard.visibility = View.GONE
                    }
                }, 3000)
            }
            UploadStatus.ERROR -> {
                binding.receivingCard.visibility = View.VISIBLE
                binding.receivingFileName.text = progress.fileName
                binding.receivingStatus.setText(R.string.receiving_status_error)
                binding.receivingProgressBar.progress = 0
                binding.receivingProgressText.text = ""

                binding.receivingCard.postDelayed({
                    if (binding.receivingCard.visibility == View.VISIBLE &&
                        service?.serverState?.value?.uploadProgress?.status == UploadStatus.ERROR
                    ) {
                        binding.receivingCard.visibility = View.GONE
                    }
                }, 3000)
            }
        }
    }

    private fun updateEmptyState() {
        binding.noUploadsText.visibility =
            if (recentUploads.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    // --- RecyclerView Adapter ---

    inner class UploadsAdapter(
        private val items: List<HistoryRecord>
    ) : RecyclerView.Adapter<UploadsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_upload_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.uploadFileName)
            private val sizeView: TextView = itemView.findViewById(R.id.uploadFileSize)
            private val timestampView: TextView = itemView.findViewById(R.id.uploadTimestamp)
            private val deleteBtn: ImageButton = itemView.findViewById(R.id.uploadDeleteBtn)

            fun bind(record: HistoryRecord) {
                nameView.text = record.fileName
                sizeView.text = formatSize(record.fileSize)
                timestampView.text = formatTimestamp(record.timestamp)
                deleteBtn.setOnClickListener {
                    val pos = adapterPosition
                    if (pos < 0 || pos >= items.size) return@setOnClickListener
                    val rec = items[pos]
                    historyManager.delete(rec.id)
                    recentUploads.removeAt(pos)
                    notifyItemRemoved(pos)
                    updateEmptyState()
                }
                deleteBtn.setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.delete_confirm_title))
                        .setMessage(getString(R.string.delete_confirm_msg, record.fileName))
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            val pos = adapterPosition
                            if (pos < 0 || pos >= items.size) return@setPositiveButton
                            val rec = items[pos]
                            historyManager.delete(rec.id)
                            recentUploads.removeAt(pos)
                            notifyItemRemoved(pos)
                            updateEmptyState()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                    true
                }
            }
        }
    }

    // --- Formatting helpers ---

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
