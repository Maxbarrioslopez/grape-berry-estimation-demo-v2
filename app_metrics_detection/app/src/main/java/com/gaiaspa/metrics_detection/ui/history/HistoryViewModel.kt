package com.gaiaspa.metrics_detection.ui.history

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.repository.LocalDeleteResult
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * ViewModel for the history tab.
 *
 * Loads Lote entities from Room with local pagination (pageSize = 4).
 * Supports multi-selection across pages for batch operations,
 * deletion (local or cloud-pending), and refresh.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val loteRepository = LoteRepository.getInstance(application)

    val lotes = MutableLiveData<List<Lote>>(emptyList())
    val selectedLote = MutableLiveData<Lote?>(null)

    // Complete list of all Lotes (local pagination, not server-paginated)
    var allLotes: List<Lote> = emptyList()

    var currentPage = 1
    val pageSize = 4
    private var totalLotesCount = 0

    // Multi-page selection persists across pagination boundaries
    private val selectedLoteIds = mutableSetOf<Long>() // or Int/String, según sea tu ID

    /** Result of a delete operation reported back to the UI. */
    data class DeleteUiResult(
        val success: Boolean,
        val missingLote: Boolean = false,
        val failedFileCount: Int = 0,
        val requiresRemoteDelete: Boolean = false
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            totalLotesCount = loteRepository.getLoteCount()
            allLotes = loteRepository.getAllLotes().toList()

            val startIndex = ((currentPage - 1) * pageSize).coerceAtLeast(0)
            val endIndex = (startIndex + pageSize).coerceAtMost(allLotes.size)
            if (startIndex < endIndex) {
                lotes.postValue(allLotes.subList(startIndex, endIndex))
            } else {
                lotes.postValue(emptyList())
            }
        }
    }

    // --- Selection methods (persist across pages) ---

    /**
     * Toggles selection state for a Lote by ID.
     * Allows batch operations across pagination boundaries.
     */
    fun selectLoteId(loteId: Long, selected: Boolean) {
        if (selected) {
            selectedLoteIds.add(loteId)
        } else {
            selectedLoteIds.remove(loteId)
        }
    }

    /** @return true if the Lote with the given ID is currently selected. */
    fun isLoteSelected(loteId: Long): Boolean {
        return selectedLoteIds.contains(loteId)
    }

    /** @return all currently selected Lotes from the full list. */
    fun getSelectedLotes(): List<Lote> {
        // Filtra los lotes globales
        return allLotes.filter { selectedLoteIds.contains(it.id) }
    }

    /** Clears all multi-page selections. */
    fun clearSelection() {
        selectedLoteIds.clear()
    }

    // --- Pagination ---

    /** Advances to the next page of Lotes. No-op if at the last page. */
    fun loadNextPage() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!hasNextPage()) return@launch

            currentPage++
            allLotes = loteRepository.getAllLotes().toList()

            val startIndex = ((currentPage - 1) * pageSize).coerceAtLeast(0)
            val endIndex = (startIndex + pageSize).coerceAtMost(allLotes.size)
            if (startIndex < endIndex) {
                lotes.postValue(allLotes.subList(startIndex, endIndex))
            } else {
                lotes.postValue(emptyList())
            }
        }
    }

    /** Returns to the previous page. No-op if already at page 1. */
    fun loadPreviousPage() {
        viewModelScope.launch(Dispatchers.IO) {
            if (currentPage <= 1) return@launch

            currentPage--
            allLotes = loteRepository.getAllLotes().toList()

            val startIndex = ((currentPage - 1) * pageSize).coerceAtLeast(0)
            val endIndex = (startIndex + pageSize).coerceAtMost(allLotes.size)
            if (startIndex < endIndex) {
                lotes.postValue(allLotes.subList(startIndex, endIndex))
            } else {
                lotes.postValue(emptyList())
            }
        }
    }

    /** Resets to page 1 and reloads all Lotes from Room. */
    fun resetPagination() {
        currentPage = 1
        viewModelScope.launch(Dispatchers.IO) {
            totalLotesCount = loteRepository.getLoteCount()
            allLotes = loteRepository.getAllLotes().toList()

            val startIndex = ((currentPage - 1) * pageSize).coerceAtLeast(0)
            val endIndex = (startIndex + pageSize).coerceAtMost(allLotes.size)
            if (startIndex < endIndex) {
                lotes.postValue(allLotes.subList(startIndex, endIndex))
            } else {
                lotes.postValue(emptyList())
            }
        }
    }

    /** Reloads the full list and refreshes the current page from Room. */
    fun refreshLotes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                totalLotesCount = loteRepository.getLoteCount()
                allLotes = loteRepository.getAllLotes().toList()

                val startIndex = ((currentPage - 1) * pageSize).coerceAtLeast(0)
                val endIndex = (startIndex + pageSize).coerceAtMost(allLotes.size)
                if (startIndex < endIndex) {
                    lotes.postValue(allLotes.subList(startIndex, endIndex))
                } else {
                    lotes.postValue(emptyList())
                }
            } catch (e: Exception) {
                Log.e("HistoryViewModel", "Error refrescando historial", e)
            }
        }
    }

    /** @return true if more pages are available. */
    fun hasNextPage(): Boolean {
        return (currentPage * pageSize) < totalLotesCount
    }

    /** @return true if there is a previous page to go back to. */
    fun hasPreviousPage(): Boolean {
        return currentPage > 1
    }

    /** Sets the selected Lote for detail view navigation. */
    fun selectLote(lote: Lote) {
        selectedLote.postValue(lote)
    }

    /**
     * Deletes a Lote. If already synced to the cloud, marks it for remote
     * deletion on next sync instead of removing locally. Refreshes the list
     * after the operation.
     */
    fun toDeleteLote(lote: Lote) {
        viewModelScope.launch(Dispatchers.IO) {
            if (lote.synced && lote.cloudId.isNotEmpty()) {
                loteRepository.markLoteAsNotSyncedAndToDelete(loteId = lote.id)
            } else {
                loteRepository.deleteLocalLote(lote.id)
            }
            // Refrescar
            refreshLotes()
        }
    }

    /**
     * Deletes the Lote only from the local Room database.
     * Does not affect the cloud copy. Reports partial failures
     * (e.g. individual file cleanup errors) via [onResult].
     */
    fun deleteLocalOnly(lote: Lote, onResult: (DeleteUiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localResult = loteRepository.deleteLocalLote(lote.id)
                refreshLotes()
                withContext(Dispatchers.Main) {
                    onResult(localResult.toUiResult())
                }
            } catch (e: Exception) {
                Log.e("HistoryViewModel", "Error eliminando solo local", e)
                withContext(Dispatchers.Main) {
                    onResult(DeleteUiResult(success = false))
                }
            }
        }
    }

    /**
     * Deletes the Lote both locally and (if synced) marks it for
     * remote deletion on the next WorkManager sync.
     *
     * @param onResult Callback with [DeleteUiResult]:
     *   - `requiresRemoteDelete = true` if the Lote was synced and
     *     needs cloud cleanup.
     */
    fun deleteLocalAndCloud(lote: Lote, onResult: (DeleteUiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val requiresRemoteDelete = lote.synced && lote.cloudId.isNotBlank()
                val result = if (requiresRemoteDelete) {
                    val updated = loteRepository.markLoteAsNotSyncedAndToDelete(loteId = lote.id) > 0
                    DeleteUiResult(
                        success = updated,
                        missingLote = !updated,
                        requiresRemoteDelete = true
                    )
                } else {
                    loteRepository.deleteLocalLote(lote.id).toUiResult()
                }
                refreshLotes()
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                Log.e("HistoryViewModel", "Error eliminando local y nube", e)
                withContext(Dispatchers.Main) {
                    onResult(DeleteUiResult(success = false))
                }
            }
        }
    }

    private fun LocalDeleteResult.toUiResult(): DeleteUiResult {
        return DeleteUiResult(
            success = deleted,
            missingLote = missingLote,
            failedFileCount = failedFileCount
        )
    }
}
