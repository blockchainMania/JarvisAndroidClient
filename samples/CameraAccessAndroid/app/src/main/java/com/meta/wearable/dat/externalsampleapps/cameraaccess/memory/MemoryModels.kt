package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

data class MemoryItem(
    val id: String,
    val capturedAt: String,
    val text: String,
    val userNote: String?,
    val aiInterpretation: String?,
    val peopleText: String?,
    val imageFilename: String?,
)

data class MemoryUiState(
    val isLoading: Boolean = false,
    val query: String = "",
    val memories: List<MemoryItem> = emptyList(),
    val errorMessage: String? = null,
)
