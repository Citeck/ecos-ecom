package ru.citeck.ecos.ecom.dto

data class FindRecordDTO(
    val sourceId: String,
    val searchValue: String,
    val searchAtt: String? = null
)
