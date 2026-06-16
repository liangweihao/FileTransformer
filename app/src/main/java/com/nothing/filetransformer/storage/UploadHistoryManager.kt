package com.nothing.filetransformer.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryRecord(
    val id: Long,
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long
)

class UploadHistoryManager(context: Context) {

    private val file = File(context.filesDir, "upload_history.json")
    private var records = mutableListOf<HistoryRecord>()
    private var nextId = 1L

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val json = file.readText()
            val arr = JSONArray(json)
            records.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(
                    HistoryRecord(
                        id = obj.getLong("id"),
                        fileName = obj.getString("name"),
                        fileSize = obj.getLong("size"),
                        timestamp = obj.getLong("ts")
                    )
                )
            }
            nextId = (records.maxOfOrNull { it.id } ?: 0) + 1
        } catch (_: Exception) {
            // Corrupted file — start fresh
            records.clear()
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (r in records) {
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.fileName)
                    put("size", r.fileSize)
                    put("ts", r.timestamp)
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }

    fun getAll(): List<HistoryRecord> = records.toList()

    fun add(fileName: String, fileSize: Long): HistoryRecord {
        val record = HistoryRecord(
            id = nextId++,
            fileName = fileName,
            fileSize = fileSize,
            timestamp = System.currentTimeMillis()
        )
        records.add(0, record)
        if (records.size > 200) records.removeAt(records.size - 1)
        save()
        return record
    }

    fun delete(id: Long) {
        records.removeAll { it.id == id }
        save()
    }

    fun clear() {
        records.clear()
        save()
    }
}
