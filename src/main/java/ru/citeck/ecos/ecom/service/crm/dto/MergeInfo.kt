package ru.citeck.ecos.ecom.service.crm.dto

import ru.citeck.ecos.webapp.api.entity.EntityRef

data class MergeInfo(
    val mergeFrom: EntityRef,
    val mergeIn: EntityRef
)
