package ru.citeck.ecos.ecom.service.pt

import ru.citeck.ecos.webapp.api.entity.EntityRef

data class ResolvedMailboxKey(
    val projectRef: EntityRef,
    val issueRef: EntityRef = EntityRef.EMPTY,
    val projectKey: String = "",
    val issueKey: String = ""
) {
    fun hasIssue(): Boolean = EntityRef.isNotEmpty(issueRef)
}
