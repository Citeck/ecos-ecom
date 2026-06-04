package ru.citeck.ecos.ecom.service.pt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import ru.citeck.ecos.commons.data.DataValue
import ru.citeck.ecos.commons.data.ObjectData
import ru.citeck.ecos.commons.json.Json
import ru.citeck.ecos.context.lib.auth.AuthContext
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.ecom.processor.mail.EcomMailAttachment
import ru.citeck.ecos.records2.RecordConstants
import ru.citeck.ecos.records2.predicate.PredicateService
import ru.citeck.ecos.records2.predicate.model.Predicates
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.txn.lib.TxnContext
import ru.citeck.ecos.webapp.api.content.EcosContentApi
import ru.citeck.ecos.webapp.api.content.EcosContentData
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

@Component
class ProjectEmailImportService(
    private val recordsService: RecordsService,
    private val ecosContentApi: EcosContentApi,
    private val mailboxKeyResolver: MailboxKeyResolver,
    @Value("\${mail.attachment.max-size-mb}") private val attachmentMaxSizeMb: Long
) {

    companion object {
        const val ACTIVITY_SOURCE_ID = "emodel/activity"
        const val COMMENT_SOURCE_ID = "emodel/comment"
        const val EMAIL_ACTIVITY_TYPE = "email-activity"
        const val ATTACHMENT_TYPE = "attachment"
        const val PROJECT_ACTIVITIES_PARENT_ATT = "has-ecos-activities:ecosActivities"

        private val log = KotlinLogging.logger {}
    }

    private val attachmentMaxSizeBytes: Long = attachmentMaxSizeMb * 1024L * 1024L

    fun importEmail(request: ProjectEmailImportRequest): ImportResult {
        val mail = request.mail
        val resolution = resolveTarget(request) ?: run {
            log.warn {
                "Project/issue key not found for subject=\"${mail.subject}\" " +
                    "from=\"${mail.fromAddress}\""
            }
            return ImportResult.NoTarget
        }

        return AuthContext.runAsSystem {
            TxnContext.doInTxn {
                importImpl(request, resolution)
            }
        }
    }

    private fun resolveTarget(request: ProjectEmailImportRequest): ResolvedMailboxKey? {
        if (EntityRef.isNotEmpty(request.explicitProjectRef)) {
            val resolvedBySubject = mailboxKeyResolver.resolve(request.mail.subject)
            if (resolvedBySubject != null && resolvedBySubject.hasIssue()) {
                return if (resolvedBySubject.projectRef == request.explicitProjectRef) {
                    resolvedBySubject
                } else {
                    log.warn {
                        "Subject references issue ${resolvedBySubject.issueKey} linked to project " +
                            "${resolvedBySubject.projectRef} but mailbox belongs to " +
                            "${request.explicitProjectRef}. Using mailbox project; comment skipped."
                    }
                    ResolvedMailboxKey(projectRef = request.explicitProjectRef)
                }
            }
            return ResolvedMailboxKey(projectRef = request.explicitProjectRef)
        }
        return mailboxKeyResolver.resolve(request.mail.subject)
    }

    private fun importImpl(
        request: ProjectEmailImportRequest,
        resolution: ResolvedMailboxKey
    ): ImportResult {
        val messageId = normalizeMessageId(request.messageId)
        if (messageId.isNotBlank() && existsByMessageId(messageId)) {
            log.info { "Email with Message-ID=$messageId already imported, skipping" }
            return ImportResult.Duplicate
        }

        val attachments = uploadAttachments(resolution.projectRef, request.mail)
        val activityRef = createEmailActivity(resolution.projectRef, request, messageId, attachments)

        if (resolution.hasIssue()) {
            createIssueComment(resolution.issueRef, request.mail)
        }

        return ImportResult.Imported(activityRef)
    }

    private fun existsByMessageId(messageId: String): Boolean {
        val found = recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId(ACTIVITY_SOURCE_ID)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                    Predicates.and(
                        Predicates.eq("_type", "emodel/type@$EMAIL_ACTIVITY_TYPE"),
                        Predicates.eq("email-atts:emailMessageId", messageId)
                    )
                )
                .build()
        )
        return EntityRef.isNotEmpty(found)
    }

    private fun uploadAttachments(
        projectRef: EntityRef,
        mail: EcomMail
    ): Map<EntityRef, EcosContentData> {
        val result = LinkedHashMap<EntityRef, EcosContentData>()
        for (attachment in mail.attachments) {
            val uploaded = uploadSingleAttachment(projectRef, attachment, mail)
            if (uploaded != null) {
                result[uploaded.first] = uploaded.second
            }
        }
        return result
    }

    private fun uploadSingleAttachment(
        projectRef: EntityRef,
        attachment: EcomMailAttachment,
        mail: EcomMail
    ): Pair<EntityRef, EcosContentData>? {
        val buffered = bufferAttachment(attachment) ?: return null

        val docAtts = DataValue.createObj()
            .set(RecordConstants.ATT_PARENT, projectRef)
            .set(RecordConstants.ATT_PARENT_ATT, "docs:documents")

        val docRef = ecosContentApi.uploadFile()
            .withEcosType(ATTACHMENT_TYPE)
            .withName(attachment.getName())
            .withAttributes(docAtts)
            .writeContent { writer ->
                ByteArrayInputStream(buffered).use { it.copyTo(writer.getOutputStream()) }
            }

        val meta = ecosContentApi.getContent(docRef)
            ?: error("Attachment uploaded but getContent returned null. Mail: $mail")
        log.debug { "Uploaded attachment: ${attachment.getName()} -> $docRef (project=$projectRef)" }
        return docRef to meta
    }

    private fun bufferAttachment(attachment: EcomMailAttachment): ByteArray? {
        return attachment.readData({ input ->
            readLimited(input, attachment.getName())
        }, { null })
    }

    private fun readLimited(input: InputStream, name: String): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            if (total > attachmentMaxSizeBytes) {
                log.warn {
                    "Attachment '$name' exceeds size limit $attachmentMaxSizeMb MB — skipped"
                }
                return null
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun createEmailActivity(
        projectRef: EntityRef,
        request: ProjectEmailImportRequest,
        messageId: String,
        attachments: Map<EntityRef, EcosContentData>
    ): EntityRef {
        val mail = request.mail
        val text = buildBodyWithAttachments(mail.content, attachments)
        val effectiveDate = if (mail.date == Instant.EPOCH) Instant.now() else mail.date

        val attributes = ObjectData.create()
            .set("_type", "emodel/type@$EMAIL_ACTIVITY_TYPE")
            .set("activityDate", effectiveDate)
            .set("text", text)
            .set(RecordConstants.ATT_PARENT, projectRef)
            .set(RecordConstants.ATT_PARENT_ATT, PROJECT_ACTIVITIES_PARENT_ATT)
            .set("email-atts:emailFrom", mail.fromAddress)
            .set("email-atts:emailTo", request.to.orEmpty())
            .set("email-atts:emailCc", request.cc.orEmpty())
            .set("email-atts:emailSubject", mail.subject)
            .set("email-atts:emailMessageId", messageId)
            .set("email-atts:emailInReplyTo", request.inReplyTo.orEmpty())
            .set("email-atts:emailReceivedAt", effectiveDate)

        val activityRef = recordsService.create(ACTIVITY_SOURCE_ID, attributes)
        log.debug { "Email activity created: $activityRef in project $projectRef" }
        return activityRef
    }

    private fun buildBodyWithAttachments(
        body: String,
        attachments: Map<EntityRef, EcosContentData>
    ): String {
        if (attachments.isEmpty()) return body
        val text = StringBuilder(body)
        attachments.forEach { (docRef, meta) ->
            text.append("<p><span>")
            val attachmentData = mapOf(
                "type" to "lexical-file-node",
                "size" to meta.getSize().toString(),
                "name" to meta.getName(),
                "fileRecordId" to docRef.toString()
            )
            text.append(Json.mapper.toStringNotNull(attachmentData))
            text.append("</span></p>")
        }
        return text.toString()
    }

    private fun createIssueComment(issueRef: EntityRef, mail: EcomMail) {
        val attributes = ObjectData.create()
            .set("record", issueRef)
            .set("text", mail.content)
        val commentRef = recordsService.create(COMMENT_SOURCE_ID, attributes)
        log.debug { "Internal comment created: $commentRef for issue $issueRef" }
    }

    private fun normalizeMessageId(messageId: String?): String {
        if (messageId.isNullOrBlank()) return ""
        return messageId.trim().removePrefix("<").removeSuffix(">")
    }

    sealed class ImportResult {
        data class Imported(val activityRef: EntityRef) : ImportResult()
        data object Duplicate : ImportResult()
        data object NoTarget : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }
}
