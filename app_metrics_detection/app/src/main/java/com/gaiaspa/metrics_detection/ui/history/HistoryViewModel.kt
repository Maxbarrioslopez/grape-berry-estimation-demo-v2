package com.gaiaspa.metrics_detection.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val loteRepository = LoteRepository.getInstance(application)

    val lotes = MutableLiveData<List<Lote>>(emptyList())
    val selectedLote = MutableLiveData<Lote?>(null)

    // Lista completa de lotes (paginación local)
    var allLotes: List<Lote> = emptyList()

    var currentPage = 1
    val pageSize = 4
    private var totalLotesCount = 0

    // --- SELECCIÓN MULTIPÁGINA ---
    private val selectedLoteIds = mutableSetOf<Long>() // o Int/String, según sea tu ID

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

    // --- Métodos de selección (persisten entre páginas) ---
    fun selectLoteId(loteId: Long, selected: Boolean) {
        if (selected) {
            selectedLoteIds.add(loteId)
        } else {
            selectedLoteIds.remove(loteId)
        }
    }

    fun isLoteSelected(loteId: Long): Boolean {
        return selectedLoteIds.contains(loteId)
    }

    fun getSelectedLotes(): List<Lote> {
        // Filtra los lotes globales
        return allLotes.filter { selectedLoteIds.contains(it.id) }
    }

    fun clearSelection() {
        selectedLoteIds.clear()
    }

    // --- Paginación ---
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
                e.printStackTrace()
            }
        }
    }

    fun hasNextPage(): Boolean {
        return (currentPage * pageSize) < totalLotesCount
    }

    fun hasPreviousPage(): Boolean {
        return currentPage > 1
    }

    fun selectLote(lote: Lote) {
        selectedLote.postValue(lote)
    }

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
}
