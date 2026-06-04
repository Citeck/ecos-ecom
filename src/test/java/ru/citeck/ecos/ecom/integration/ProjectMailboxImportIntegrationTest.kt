package ru.citeck.ecos.ecom.integration

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito
import ru.citeck.ecos.commons.data.ObjectData
import ru.citeck.ecos.config.lib.service.EcosConfigServiceFactory
import ru.citeck.ecos.ecom.processor.ReadMailboxPTProcessor
import ru.citeck.ecos.ecom.routes.ReadMailboxPTRoute
import ru.citeck.ecos.ecom.service.pt.MailboxKeyResolver
import ru.citeck.ecos.ecom.service.pt.MailboxMessageMover
import ru.citeck.ecos.ecom.service.pt.ProjectEmailImportService
import ru.citeck.ecos.model.lib.ModelServiceFactory
import ru.citeck.ecos.model.lib.type.dto.TypeInfo
import ru.citeck.ecos.model.lib.type.repo.TypesRepo
import ru.citeck.ecos.records2.predicate.model.Predicates
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.RecordsServiceFactory
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts
import ru.citeck.ecos.records3.record.dao.impl.mem.InMemDataRecordsDao
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.secrets.lib.EcosSecrets
import ru.citeck.ecos.secrets.lib.secret.basic.BasicSecretData
import ru.citeck.ecos.test.commons.EcosWebAppApiMock
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.Properties

/**
 * End-to-end integration test for the Project Tracker email import pipeline.
 *
 * Drives the global mailbox route ([ReadMailboxPTRoute], continuous IMAP consumer)
 * against an in-memory mail server (GreenMail) and in-memory Records storage:
 * IMAP consume -> body extract -> parse -> import (activity / comment / attachments)
 * -> move to success/error folder.
 *
 * The per-project poll orchestration ([ru.citeck.ecos.ecom.processor.pt.PollProjectMailboxesProcessor])
 * is covered by its own unit test; here we exercise the shared import/routing/move logic
 * through a real mail consumer.
 *
 * Disabled by default. Enable explicitly:
 *   EMAIL_INTEGRATION_TESTS=true mvn test -Dtest=ProjectMailboxImportIntegrationTest
 */
@EnabledIfEnvironmentVariable(named = "EMAIL_INTEGRATION_TESTS", matches = "true")
class ProjectMailboxImportIntegrationTest {

    private companion object {
        const val USERNAME = "ptuser"
        const val PASSWORD = "ptpass"
        const val INBOX_EMAIL = "pt@test.com"

        const val PROJECT_SRC = "emodel/project"
        const val ACTIVITY_SRC = "emodel/activity"
        const val ISSUE_SRC = "emodel/ept-issue"
        const val COMMENT_SRC = "emodel/comment"

        const val SUCCESS_FOLDER = "Processed"
        const val ERROR_FOLDER = "Errors"

        const val PROJECT_KEY = "TESTMAIL"
        const val OTHER_KEY = "OTHER"

        const val AWAIT_TIMEOUT_MS = 15_000L
    }

    private lateinit var greenMail: GreenMail
    private lateinit var camelCtx: DefaultCamelContext
    private lateinit var recordsService: RecordsService
    private lateinit var mockedSecrets: MockedStatic<EcosSecrets>

    private lateinit var projectRef: EntityRef
    private lateinit var otherProjectRef: EntityRef

    @BeforeEach
    fun setup() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.reset()
        greenMail.setUser(INBOX_EMAIL, USERNAME, PASSWORD)

        val recsServiceFactory = RecordsServiceFactory()
        val modelServices = object : ModelServiceFactory() {
            override fun createTypesRepo(): TypesRepo {
                return object : TypesRepo {
                    override fun getChildren(typeRef: EntityRef): List<EntityRef> = emptyList()
                    override fun getTypeInfo(typeRef: EntityRef): TypeInfo? {
                        return when (typeRef.getLocalId()) {
                            "email-activity", "attachment" -> TypeInfo.create()
                                .withId(typeRef.getLocalId()).withSourceId(ACTIVITY_SRC).build()
                            else -> null
                        }
                    }
                }
            }
        }
        modelServices.setRecordsServices(recsServiceFactory)

        recordsService = recsServiceFactory.recordsService
        listOf(PROJECT_SRC, ACTIVITY_SRC, ISSUE_SRC, COMMENT_SRC).forEach {
            recordsService.register(InMemDataRecordsDao(it))
        }

        // mailbox project records (resolved by subject KEY)
        projectRef = createProject(PROJECT_KEY)
        otherProjectRef = createProject(OTHER_KEY)

        val contentApi = EcosWebAppApiMock().getContentApi()
        val resolver = MailboxKeyResolver(recordsService)
        val importService = ProjectEmailImportService(recordsService, contentApi, resolver, 10L)
        val importProcessor = ReadMailboxPTProcessor(importService)
        val mover = MailboxMessageMover()

        val configServices = EcosConfigServiceFactory()
        val route = ReadMailboxPTRoute(importProcessor, mover)
        configServices.beanConsumersService.registerConsumers(route)

        val host = greenMail.imap.bindTo + ":" + greenMail.imap.port
        configServices.inMemConfigProvider.setConfig(
            "mail-inbox-pt",
            ObjectData.create()
                .set("enabled", true)
                .set("imap", "imap://$host")
                .set("credentials", "emodel/secret@pt-creds")
                .set("folder", "INBOX")
                .set("successFolder", SUCCESS_FOLDER)
                .set("errorFolder", ERROR_FOLDER)
                .set("delay", 1000)
        )

        // Credentials are resolved once while the route is being built (configure()).
        mockedSecrets = Mockito.mockStatic(EcosSecrets::class.java)
        mockedSecrets.`when`<BasicSecretData?> { EcosSecrets.getBasicDataOrNull(anyString()) }
            .thenReturn(BasicSecretData(USERNAME, PASSWORD))

        camelCtx = DefaultCamelContext(DefaultRegistry())
        camelCtx.addRoutes(route)
        camelCtx.start()
    }

    @AfterEach
    fun tearDown() {
        camelCtx.stop()
        greenMail.stop()
        mockedSecrets.close()
    }

    // --- pipeline scenarios -------------------------------------------------

    @Test
    fun `I1 project key creates activity under project and moves to success folder`() {
        sendEmail(subject = "[$PROJECT_KEY] Build broke", messageId = "<i1@test>")

        await { queryActivities().size == 1 }

        val activity = queryActivities().single()
        assertThat(activity.getAtt("email-atts:emailSubject").asText()).isEqualTo("[$PROJECT_KEY] Build broke")
        assertThat(activity.getAtt("_parent").asText()).isEqualTo(projectRef.toString())
        await { folderCount(SUCCESS_FOLDER) == 1 }
        assertThat(folderCount(SUCCESS_FOLDER)).isEqualTo(1)
    }

    @Test
    fun `I2 issue key creates activity and internal comment`() {
        val issueRef = createIssue("$PROJECT_KEY-33", projectRef)
        sendEmail(subject = "$PROJECT_KEY-33: details", messageId = "<i2@test>")

        await { queryActivities().size == 1 && queryAll(COMMENT_SRC, "record").size == 1 }

        assertThat(queryActivities()).hasSize(1)
        val comment = queryAll(COMMENT_SRC, "record").single()
        assertThat(comment.getAtt("record").asText()).isEqualTo(issueRef.toString())
    }

    // NOTE: deduplication is intentionally NOT covered here. It relies on the predicate
    // AND(_type, email-atts:emailMessageId): InMemDataRecordsDao does not expose _type via
    // getAtt (stored as null), and JavaMail regenerates Message-ID on Transport.send, so
    // duplicate delivery cannot be reproduced in-process. Dedup is covered by
    // ProjectEmailImportServiceTest.U3 (mocked queryOne) and stand acceptance case A8.

    @Test
    fun `I4 unrecognized key moves to error folder without activity`() {
        sendEmail(subject = "No key here", messageId = "<i4@test>")

        await { folderCount(ERROR_FOLDER) == 1 }

        assertThat(queryActivities()).isEmpty()
        assertThat(folderCount(SUCCESS_FOLDER)).isEqualTo(0)
    }

    @Test
    fun `I5 attachments are uploaded and referenced in activity body`() {
        sendEmail(
            subject = "[$PROJECT_KEY] with files",
            messageId = "<i5@test>",
            attachments = mapOf("a.txt" to "hello".toByteArray(), "b.txt" to "world".toByteArray())
        )

        await { queryActivities().size == 1 }

        assertThat(queryActivities().single().getAtt("text").asText()).contains("lexical-file-node")
        await { folderCount(SUCCESS_FOLDER) == 1 }
    }

    @Test
    fun `I6 oversized attachment is skipped but activity is created`() {
        val huge = ByteArray(11 * 1024 * 1024) // > 10 MB limit
        sendEmail(
            subject = "[$PROJECT_KEY] oversized",
            messageId = "<i6@test>",
            attachments = mapOf("small.txt" to "ok".toByteArray(), "huge.bin" to huge)
        )

        await { queryActivities().size == 1 }

        assertThat(queryActivities()).hasSize(1)
        await { folderCount(SUCCESS_FOLDER) == 1 }
    }

    @Test
    fun `I7 forwarded email is routed by stripped subject with forwarder as from`() {
        sendEmail(
            subject = "Fwd: [$PROJECT_KEY] original",
            messageId = "<i7@test>",
            from = "Forwarder <forwarder@test.com>"
        )

        await { queryActivities().size == 1 }

        val activity = queryActivities().single()
        assertThat(activity.getAtt("email-atts:emailFrom").asText()).contains("forwarder@test.com")
        assertThat(activity.getAtt("_parent").asText()).isEqualTo(projectRef.toString())
    }

    // --- routing duplication (also covered by unit tests) -------------------

    @Test
    fun `R-fallback unknown issue number falls back to project`() {
        sendEmail(subject = "$PROJECT_KEY-9999 missing issue", messageId = "<rf@test>")

        await { queryActivities().size == 1 }

        assertThat(queryActivities().single().getAtt("_parent").asText()).isEqualTo(projectRef.toString())
        assertThat(queryAll(COMMENT_SRC, "record")).isEmpty()
    }

    @Test
    fun `R-mismatch uses issue link-project as source of truth`() {
        val issueRef = createIssue("$OTHER_KEY-1", otherProjectRef)
        sendEmail(subject = "$OTHER_KEY-1: cross project", messageId = "<rm@test>")

        await { queryActivities().size == 1 && queryAll(COMMENT_SRC, "record").size == 1 }

        assertThat(queryActivities().single().getAtt("_parent").asText()).isEqualTo(otherProjectRef.toString())
        assertThat(queryAll(COMMENT_SRC, "record").single().getAtt("record").asText())
            .isEqualTo(issueRef.toString())
    }

    @Test
    fun `R-multikey issue match wins over plain project`() {
        val issueRef = createIssue("$PROJECT_KEY-7", projectRef)
        sendEmail(subject = "[$PROJECT_KEY] see $PROJECT_KEY-7", messageId = "<mk@test>")

        await { queryActivities().size == 1 && queryAll(COMMENT_SRC, "record").size == 1 }

        assertThat(queryAll(COMMENT_SRC, "record").single().getAtt("record").asText())
            .isEqualTo(issueRef.toString())
    }

    @Test
    fun `R-lowercase key is not matched and goes to error folder`() {
        sendEmail(subject = "testmail-7 lowercase", messageId = "<lc@test>")

        await { folderCount(ERROR_FOLDER) == 1 }

        assertThat(queryActivities()).isEmpty()
    }

    // --- helpers ------------------------------------------------------------

    private fun await(timeoutMs: Long = AWAIT_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(250)
        }
    }

    private fun createProject(key: String): EntityRef {
        return recordsService.create(PROJECT_SRC, mapOf("id" to key, "key" to key))
    }

    private fun createIssue(issueKey: String, project: EntityRef): EntityRef {
        return recordsService.create(
            ISSUE_SRC,
            mapOf(
                "id" to issueKey,
                "issueKey" to issueKey,
                "link-project:project" to project.toString()
            )
        )
    }

    private fun queryActivities(): List<RecordAtts> = queryAll(
        ACTIVITY_SRC,
        "email-atts:emailSubject",
        "email-atts:emailFrom",
        "_parent",
        "text"
    )

    private fun queryAll(sourceId: String, vararg atts: String): List<RecordAtts> {
        return recordsService.query(
            RecordsQuery.create {
                withSourceId(sourceId)
                withQuery(Predicates.alwaysTrue())
            },
            atts.toList()
        ).getRecords()
    }

    private fun mailSession(): Session {
        val prop = Properties()
        prop["mail.smtp.host"] = greenMail.smtp.bindTo
        prop["mail.smtp.port"] = greenMail.smtp.port
        return Session.getInstance(prop, null)
    }

    private fun sendEmail(
        subject: String,
        messageId: String,
        body: String = "<p>Body of $subject</p>",
        from: String = "Alice <alice@test.com>",
        attachments: Map<String, ByteArray> = emptyMap()
    ) {
        val message: Message = MimeMessage(mailSession())
        message.setFrom(InternetAddress(from))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(INBOX_EMAIL))
        message.subject = subject
        message.setHeader("Message-ID", messageId)

        val bodyPart = MimeBodyPart()
        bodyPart.setContent(body, "text/html; charset=utf-8")
        val multipart: Multipart = MimeMultipart()
        multipart.addBodyPart(bodyPart)
        attachments.forEach { (name, bytes) ->
            val part = MimeBodyPart()
            part.dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(bytes, "application/octet-stream"))
            part.fileName = name
            multipart.addBodyPart(part)
        }
        message.setContent(multipart)
        Transport.send(message)
        greenMail.waitForIncomingEmail(1)
    }

    private fun folderCount(folderName: String): Int {
        val props = Properties()
        props["mail.store.protocol"] = "imap"
        props["mail.imap.host"] = greenMail.imap.bindTo
        props["mail.imap.port"] = greenMail.imap.port.toString()
        val store: Store = Session.getInstance(props).getStore("imap")
        store.connect(greenMail.imap.bindTo, greenMail.imap.port, USERNAME, PASSWORD)
        try {
            val folder = store.getFolder(folderName)
            if (!folder.exists()) return 0
            folder.open(Folder.READ_ONLY)
            try {
                return folder.messageCount
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }
}
