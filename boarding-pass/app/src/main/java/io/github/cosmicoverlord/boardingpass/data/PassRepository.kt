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

    private fun load(): List<BoardingPass> = try {
        if (!file.baseFile.exists()) {
            emptyList()
        } else {
            AppJson.decodeFromString(serializer, file.readFully().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read passes", e)
        emptyList()
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
