package io.github.cosmicoverlord.boardingpass.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.cosmicoverlord.boardingpass.core.model.AppJson
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import java.io.File

/** Boarding passes stored as one JSON file; there are only ever a handful of them. */
class PassRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "passes.json"))
    private val mutex = Mutex()
    private val serializer = ListSerializer(BoardingPass.serializer())
    private val _passes = MutableStateFlow(load())
    val passes: StateFlow<List<BoardingPass>> = _passes.asStateFlow()

    fun get(id: String): BoardingPass? = _passes.value.firstOrNull { it.id == id }

    suspend fun update(transform: (List<BoardingPass>) -> List<BoardingPass>) {
        mutex.withLock {
            val next = transform(_passes.value)
            if (next == _passes.value) return
            _passes.value = next
            withContext(Dispatchers.IO) { save(next) }
        }
    }

    suspend fun upsert(pass: BoardingPass) = update { list ->
        if (list.any { it.id == pass.id }) list.map { if (it.id == pass.id) pass else it } else list + pass
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun modify(id: String, change: (BoardingPass) -> BoardingPass) = update { list ->
        list.map { if (it.id == id) change(it) else it }
    }

    /**
     * Decodes passes one by one so a single bad entry doesn't cost the rest. If anything was
     * unreadable the original file is backed up first, since the next save rewrites it.
     */
    private fun load(): List<BoardingPass> {
        if (!file.baseFile.exists()) return emptyList()
        val text = try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read passes", e)
            return emptyList()
        }
        val items = try {
            AppJson.parseToJsonElement(text).jsonArray
        } catch (e: Exception) {
            Log.e(TAG, "passes.json is not a JSON array", e)
            backup(text)
            return emptyList()
        }
        val passes = items.mapNotNull { item ->
            try {
                AppJson.decodeFromJsonElement(BoardingPass.serializer(), item)
            } catch (e: Exception) {
                Log.e(TAG, "Skipping unreadable pass", e)
                null
            }
        }
        if (passes.size != items.size) backup(text)
        return passes
    }

    private fun backup(text: String) {
        try {
            File(file.baseFile.parentFile, "passes-backup-${System.currentTimeMillis()}.json").writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to back up passes", e)
        }
    }

    private fun save(list: List<BoardingPass>) {
        val out = file.startWrite()
        try {
            out.write(AppJson.encodeToString(serializer, list).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Failed to save passes", e)
        }
    }

    private companion object {
        const val TAG = "PassRepository"
    }
}
