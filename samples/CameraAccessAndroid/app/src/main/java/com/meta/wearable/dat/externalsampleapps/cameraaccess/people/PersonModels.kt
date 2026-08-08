package com.meta.wearable.dat.externalsampleapps.cameraaccess.people

data class PersonItem(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val org: String?,
    val role: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notesSummary: String?,
    val updatedAtDisplay: String,
)

data class PersonUiState(
    val isLoading: Boolean = false,
    val query: String = "",
    val people: List<PersonItem> = emptyList(),
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
)
