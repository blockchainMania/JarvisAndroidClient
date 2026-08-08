package com.meta.wearable.dat.externalsampleapps.cameraaccess.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonViewModel : ViewModel() {
    private val repository = PersonRepository()

    private val _uiState = MutableStateFlow(PersonUiState())
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()

    fun loadAll() {
        runLoad { repository.list() }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            loadAll()
            return
        }
        runLoad { repository.search(query) }
    }

    fun refreshCurrent() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) loadAll() else search()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun updatePerson(
        person: PersonItem,
        onSuccess: (PersonItem) -> Unit,
    ) {
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { repository.update(person) }
                onSuccess(updated)
                refreshCurrent()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update person") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun runLoad(block: () -> List<PersonItem>) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val people = withContext(Dispatchers.IO) { block() }
                _uiState.update { it.copy(isLoading = false, people = people) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load people",
                    )
                }
            }
        }
    }
}
