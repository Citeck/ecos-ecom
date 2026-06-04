package ru.citeck.ecos.ecom.service.pt

import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.webapp.api.entity.EntityRef

data class ProjectEmailImportRequest(
    val mail: EcomMail,
    val messageId: String?,
    val inReplyTo: String?,
    val to: String?,
    val cc: String?,
    val explicitProjectRef: EntityRef = EntityRef.EMPTY
)
