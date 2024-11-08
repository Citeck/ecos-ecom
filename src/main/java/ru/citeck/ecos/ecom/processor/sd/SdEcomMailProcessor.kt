package ru.citeck.ecos.ecom.processor.sd

import io.github.oshai.kotlinlogging.KotlinLogging
import lombok.Data
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.apache.commons.lang3.StringUtils
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import ru.citeck.ecos.commons.data.DataValue
import ru.citeck.ecos.commons.data.ObjectData
import ru.citeck.ecos.commons.json.Json
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig
import ru.citeck.ecos.context.lib.auth.AuthContext
import ru.citeck.ecos.context.lib.auth.data.AuthData
import ru.citeck.ecos.context.lib.auth.data.SimpleAuthData
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.ecom.service.HtmlUtils
import ru.citeck.ecos.ecom.service.Utils
import ru.citeck.ecos.records2.RecordConstants
import ru.citeck.ecos.records2.predicate.PredicateService
import ru.citeck.ecos.records2.predicate.model.Predicates.eq
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.txn.lib.TxnContext
import ru.citeck.ecos.webapp.api.constants.AppName
import ru.citeck.ecos.webapp.api.content.EcosContentApi
import ru.citeck.ecos.webapp.api.content.EcosContentData
import ru.citeck.ecos.webapp.api.content.EcosContentWriter
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.function.Consumer
import java.util.regex.Pattern

@Component
class SdEcomMailProcessor(
    private val recordsService: RecordsService,
    private val ecosContentApi: EcosContentApi
) : Processor {

    companion object {

        private const val COMMENT_SRC_ID = "${AppName.EMODEL}/comment"

        private const val CLIENT_SK = "clients-type"
        private const val PERSON_SK = "person"

        private const val SD_CODE__PATTERN = "\\((SD-\\d+)\\)"

        private const val SD_RESPONSE_MAIL_PREFIX = "Re: (SD-"

        private val log = KotlinLogging.logger {}
    }

    @EcosConfig("app/service-desk\$default-client")
    private var defaultClient: EntityRef? = null

    @EcosConfig("app/service-desk\$critical-tags")
    private var tagsString: String? = null

    override fun process(exchange: Exchange) {
        val mail = exchange.getIn().getBody(EcomMail::class.java) ?: error("Body is null")
        AuthContext.runAsSystem {
            TxnContext.doInTxn {
                processImpl(mail)
            }
        }
    }

    private fun processImpl(mail: EcomMail) {

        val possibleClients: List<EntityRef> = getClientsByEmailDomain(mail.fromDomain)
        if (possibleClients.isEmpty()) {
            log.debug { "Client is not found for domain '${mail.fromDomain}'" }
            return
        }

        val userAndClient: UserWithClient = lookupUserAndClient(possibleClients, mail.fromAddress)
        if (userAndClient.isEmpty()) {
            log.debug { "Initiator is not found for email '${mail.fromAddress}'" }
            return
        }

        val mailKind = getMailKind(mail.subject)

        AuthContext.runAsFull(getAuthDataForUser(userAndClient.userRef)) {
            createAndUpdateData(mailKind, mail, userAndClient.clientRef, userAndClient.userRef)
        }
    }

    private fun createAndUpdateData(mailKind: MailKind, mail: EcomMail, client: EntityRef, initiator: EntityRef) {

        val sdRequestRef = getOrCreateSDRecord(mailKind, mail, client, initiator)

        val createdAttachments = ArrayList<Pair<EntityRef, EcosContentData>>()

        for (attachment in mail.attachments) {

            val docAtts = DataValue.createObj()
                .set(RecordConstants.ATT_PARENT, sdRequestRef)
                .set(RecordConstants.ATT_PARENT_ATT, "docs:documents")

            val docRef = attachment.readData({ attachData ->
                ecosContentApi.uploadFile()
                    .withEcosType("attachment")
                    .withName(attachment.getName())
                    .withAttributes(docAtts)
                    .writeContent { writer: EcosContentWriter ->
                        writer.writeStream(attachData)
                    }
            }) { null }
            if (docRef == null) {
                log.warn {
                    "Attachment content is empty. " +
                    "Attachment name: ${attachment.getName()} " +
                    "SD request $sdRequestRef mail date ${mail.date}"
                }
                continue
            }
            val meta = ecosContentApi.getContent(docRef)
                ?: error("Attachment was uploaded, but getContent return null. Mail: $mail")

            log.debug { "Saved document: ${attachment.getName()} - $docRef for SD request $sdRequestRef" }

            createdAttachments.add(docRef to meta)
        }

        if (mailKind == MailKind.REPLY) {
            createComment(sdRequestRef, mail, createdAttachments)
        }
    }

    private fun createComment(
        sdRecord: EntityRef,
        mail: EcomMail,
        attachments: List<Pair<EntityRef, EcosContentData>>
    ) {

        log.debug {
            "Create new comment for SD request $sdRecord by mail from ${mail.from}. Mail date: ${mail.date}"
        }

        var commentContent = HtmlUtils.convertHtmlToFormattedText(mail.content)

        if (attachments.isNotEmpty()) {

            val newContent = StringBuilder((commentContent.length * 1.5).toInt())

            newContent.append(commentContent)
            for (attachment in attachments) {
                newContent.append("<p><span>")
                val attachmentData = mapOf(
                    "type" to "lexical-file-node",
                    "size" to attachment.second.getSize(),
                    "name" to attachment.second.getName(),
                    "fileRecordId" to attachment.first
                )
                newContent.append(Json.mapper.toStringNotNull(attachmentData))
                newContent.append("</span></p>")
            }
            commentContent = newContent.toString()
        }

        val attributes = ObjectData.create()
            .set("record", sdRecord)
            .set("text", commentContent)

        recordsService.create(COMMENT_SRC_ID, attributes)
    }

    private fun getAuthDataForUser(userRef: EntityRef): AuthData {
        val authorities = recordsService.getAtt(userRef, "authorities.list[]").asStrList()
        return SimpleAuthData(userRef.getLocalId(), authorities)
    }

    private fun getOrCreateSDRecord(
        mailKind: MailKind,
        mail: EcomMail,
        client: EntityRef,
        initiator: EntityRef
    ): EntityRef {
        return when (mailKind) {
            MailKind.NEW -> {

                log.info {
                    "Create new SD request by mail from ${mail.from}. " +
                        "Subject: ${mail.subject} " +
                        "Date: ${mail.date} " +
                        "Initiator: $initiator " +
                        "Client: $client"
                }

                val hasCriticalTag = Utils.hasCriticalTagsInSubject(mail.subject, tagsString)

                val attributes = ObjectData.create()
                    .set(SdRequestDesc.ATT_CREATED_AUTOMATICALLY, true)
                    .set(SdRequestDesc.ATT_PRIORITY, if (hasCriticalTag) "urgent" else "medium")
                    .set(SdRequestDesc.ATT_LETTER_CONTENT_WO_TAGS, Jsoup.parse(mail.content).text())
                    .set(SdRequestDesc.ATT_CLIENT, client)
                    .set(SdRequestDesc.ATT_INITIATOR, initiator)
                    .set(SdRequestDesc.ATT_LETTER_CONTENT, HtmlUtils.convertHtmlToFormattedText(mail.content))
                    .set(SdRequestDesc.ATT_DATE_RECEIVED, mail.date)
                    .set(SdRequestDesc.ATT_AUTHOR, mail.from)
                    .set(SdRequestDesc.ATT_LETTER_TOPIC, mail.subject)

                val sdRequestRef = recordsService.create(SdRequestDesc.TYPE.toString(), attributes)

                log.debug { "SD request was created. Ref: $sdRequestRef" }

                sdRequestRef
            }

            MailKind.REPLY -> {
                AuthContext.runAsSystem {
                    findSdRequestByTitle(mail.subject)
                }
            }
        }
    }

    private fun getClientsByEmailDomain(emailDomain: String): List<EntityRef> {
        val query = RecordsQuery.create()
            .withSourceId(AppName.EMODEL + "/" + CLIENT_SK)
            .withLanguage(PredicateService.LANGUAGE_PREDICATE)
            .withQuery(
                eq("emailDomain", emailDomain)
            )
            .build()
        return recordsService.query(query).getRecords()
    }

    private fun lookupUserAndClient(possibleClients: List<EntityRef>, email: String): UserWithClient {

        val clientUsers: List<ClientUsers> = recordsService.getAtts(possibleClients, ClientUsers::class.java)

        for (client in clientUsers) {
            for (user in client.getAllClientUsers()) {
                if (email.equals(user.email, ignoreCase = true)) {
                    return UserWithClient(user.ref, client.id)
                }
            }
        }

        val defaultClient = this.defaultClient ?: return UserWithClient.EMPTY
        if (EntityRef.isNotEmpty(defaultClient) && possibleClients.any { it == defaultClient }) {
            val userRef = getFirstUserByEmail(email)
            if (userRef.isEmpty()) {
                return UserWithClient.EMPTY
            }
            return UserWithClient(userRef, defaultClient)
        }

        return UserWithClient.EMPTY
    }

    fun getFirstUserByEmail(email: String): EntityRef {
        val query = RecordsQuery.create()
            .withSourceId(AppName.EMODEL + "/" + PERSON_SK)
            .withLanguage(PredicateService.LANGUAGE_PREDICATE)
            .withQuery(
                eq("email", email)
            )
            .build()
        return recordsService.queryOne(query) ?: EntityRef.EMPTY
    }

    private fun getMailKind(title: String): MailKind {
        return if (StringUtils.startsWith(title, SD_RESPONSE_MAIL_PREFIX)) {
            MailKind.REPLY
        } else {
            MailKind.NEW
        }
    }

    private fun findSdRequestByTitle(title: String): EntityRef {

        val matcher = Pattern.compile(SD_CODE__PATTERN).matcher(title)

        if (!matcher.find()) {
            return EntityRef.EMPTY
        }

        val sdCode = matcher.group(1).trim { it <= ' ' }
        val sdNumber = StringUtils.substringAfter(sdCode, "SD-").toInt()

        val query = RecordsQuery.create()
            .withEcosType(SdRequestDesc.TYPE.getLocalId())
            .withLanguage(PredicateService.LANGUAGE_PREDICATE)
            .withQuery(
                eq("_docNum", sdNumber)
            )
            .build()

        return recordsService.queryOne(query) ?: EntityRef.EMPTY
    }

    @Data
    private class ClientUsers(
        val id: EntityRef,
        @AttName("users[]")
        val users: List<UserData>?,
        val authGroups: List<GroupData>?
    ) {

        fun getAllClientUsers(): List<UserData> {

            val allUsers: MutableMap<String, UserData> = LinkedHashMap()
            users?.forEach(
                Consumer { user: UserData ->
                    allUsers[user.userName] = user
                }
            )
            if (authGroups != null) {
                for (group in authGroups) {
                    if (group.containedUsers != null) {
                        group.containedUsers.forEach {
                            allUsers[it.userName] = it
                        }
                    }
                }
            }
            return ArrayList(allUsers.values)
        }
    }

    private class GroupData(
        val containedUsers: List<UserData>?
    )

    private class UserData(
        @AttName("?id")
        val ref: EntityRef,
        @AttName("id")
        val userName: String,
        val email: String?
    )

    private class UserWithClient(
        val userRef: EntityRef,
        val clientRef: EntityRef
    ) {
        companion object {
            val EMPTY = UserWithClient(EntityRef.EMPTY, EntityRef.EMPTY)
        }

        fun isEmpty(): Boolean {
            return userRef.isEmpty() || clientRef.isEmpty()
        }
    }

    private enum class MailKind {
        NEW,
        REPLY
    }
}
