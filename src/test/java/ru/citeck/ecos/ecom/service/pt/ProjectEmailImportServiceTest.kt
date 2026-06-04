package ru.citeck.ecos.ecom.service.pt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.citeck.ecos.commons.data.ObjectData
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.content.EcosContentApi
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.time.Instant

class ProjectEmailImportServiceTest {

    private lateinit var recordsService: RecordsService
    private lateinit var ecosContentApi: EcosContentApi
    private lateinit var resolver: MailboxKeyResolver
    private lateinit var service: ProjectEmailImportService

    private val projectRef = EntityRef.valueOf("emodel/project@PRJCTMNG")
    private val issueRef = EntityRef.valueOf("emodel/ept-issue@PRJCTMNG-33")
    private val activityRef = EntityRef.valueOf("emodel/activity@A-1")
    private val commentRef = EntityRef.valueOf("emodel/comment@C-1")

    @BeforeEach
    fun setup() {
        recordsService = mock()
        ecosContentApi = mock()
        resolver = mock()
        service = ProjectEmailImportService(recordsService, ecosContentApi, resolver, 10L)

        whenever(recordsService.create(eq("emodel/activity"), any<ObjectData>()))
            .thenReturn(activityRef)
        whenever(recordsService.create(eq("emodel/comment"), any<ObjectData>()))
            .thenReturn(commentRef)
        // By default no duplicate
        whenever(recordsService.queryOne(any<RecordsQuery>())).thenReturn(EntityRef.EMPTY)
    }

    private fun mail(
        subject: String = "[PRJCTMNG] Hi",
        date: Instant = Instant.parse("2026-04-17T10:00:00Z")
    ): EcomMail = EcomMail(
        from = "Alice <alice@example.com>",
        fromAddress = "alice@example.com",
        fromDomain = "example.com",
        subject = subject,
        content = "<p>Body</p>",
        date = date,
        attachments = emptyList()
    )

    @Test
    fun `U1 project key creates email-activity under project`() {
        whenever(resolver.resolve(any())).thenReturn(
            ResolvedMailboxKey(projectRef = projectRef, projectKey = "PRJCTMNG")
        )

        val req = ProjectEmailImportRequest(
            mail = mail("[PRJCTMNG] Hi"),
            messageId = "<m1@example.com>",
            inReplyTo = null,
            to = "pt@x",
            cc = null
        )
        val result = service.importEmail(req)

        assertThat(result).isInstanceOf(ProjectEmailImportService.ImportResult.Imported::class.java)
        val activityAtts = argumentCaptor<ObjectData>()
        verify(recordsService).create(eq("emodel/activity"), activityAtts.capture())
        val atts = activityAtts.firstValue
        assertThat(atts.get("_type").asText()).isEqualTo("emodel/type@email-activity")
        assertThat(atts.get("_parent").asText()).isEqualTo(projectRef.toString())
        assertThat(atts.get("_parentAtt").asText()).isEqualTo("has-ecos-activities:ecosActivities")
        assertThat(atts.get("email-atts:emailMessageId").asText()).isEqualTo("m1@example.com")
        verify(recordsService, never()).create(eq("emodel/comment"), any<ObjectData>())
    }

    @Test
    fun `U2 issue key creates activity and comment in same pass`() {
        whenever(resolver.resolve(any())).thenReturn(
            ResolvedMailboxKey(
                projectRef = projectRef,
                issueRef = issueRef,
                projectKey = "PRJCTMNG",
                issueKey = "PRJCTMNG-33"
            )
        )

        val result = service.importEmail(
            ProjectEmailImportRequest(
                mail = mail("PRJCTMNG-33: details"),
                messageId = "<m2@example.com>",
                inReplyTo = null,
                to = null,
                cc = null
            )
        )

        assertThat(result).isInstanceOf(ProjectEmailImportService.ImportResult.Imported::class.java)
        verify(recordsService).create(eq("emodel/activity"), any<ObjectData>())
        val commentAtts = argumentCaptor<ObjectData>()
        verify(recordsService).create(eq("emodel/comment"), commentAtts.capture())
        assertThat(commentAtts.firstValue.get("record").asText()).isEqualTo(issueRef.toString())
    }

    @Test
    fun `U3 duplicate message id is skipped`() {
        whenever(resolver.resolve(any())).thenReturn(
            ResolvedMailboxKey(projectRef = projectRef, projectKey = "PRJCTMNG")
        )
        // simulate existing activity
        whenever(recordsService.queryOne(any<RecordsQuery>())).thenReturn(activityRef)

        val result = service.importEmail(
            ProjectEmailImportRequest(
                mail = mail(),
                messageId = "<dup@x>",
                inReplyTo = null,
                to = null,
                cc = null
            )
        )

        assertThat(result).isEqualTo(ProjectEmailImportService.ImportResult.Duplicate)
        verify(recordsService, never()).create(any<String>(), any<ObjectData>())
    }

    @Test
    fun `U4 unresolved key returns NoTarget without side effects`() {
        whenever(resolver.resolve(any())).thenReturn(null)

        val result = service.importEmail(
            ProjectEmailImportRequest(
                mail = mail("no key here"),
                messageId = "<m@x>",
                inReplyTo = null,
                to = null,
                cc = null
            )
        )

        assertThat(result).isEqualTo(ProjectEmailImportService.ImportResult.NoTarget)
        verify(recordsService, never()).create(any<String>(), any<ObjectData>())
    }

    @Test
    fun `U13 missing Date header falls back to now for activityDate and emailReceivedAt`() {
        whenever(resolver.resolve(any())).thenReturn(
            ResolvedMailboxKey(projectRef = projectRef, projectKey = "PRJCTMNG")
        )
        val before = Instant.now()

        service.importEmail(
            ProjectEmailImportRequest(
                mail = mail(date = Instant.EPOCH),
                messageId = "<no-date@x>",
                inReplyTo = null,
                to = null,
                cc = null
            )
        )

        val atts = argumentCaptor<ObjectData>()
        verify(recordsService).create(eq("emodel/activity"), atts.capture())
        val activityDate = Instant.parse(atts.firstValue.get("activityDate").asText())
        val receivedAt = Instant.parse(atts.firstValue.get("email-atts:emailReceivedAt").asText())
        assertThat(activityDate).isNotEqualTo(Instant.EPOCH)
        assertThat(activityDate).isAfterOrEqualTo(before)
        assertThat(receivedAt).isEqualTo(activityDate)
    }

    @Test
    fun `explicit project skips subject resolution when no issue match`() {
        whenever(resolver.resolve(any())).thenReturn(null)

        service.importEmail(
            ProjectEmailImportRequest(
                mail = mail("Random subject"),
                messageId = "<m@x>",
                inReplyTo = null,
                to = null,
                cc = null,
                explicitProjectRef = projectRef
            )
        )

        verify(recordsService).create(eq("emodel/activity"), any<ObjectData>())
        verify(recordsService, never()).create(eq("emodel/comment"), any<ObjectData>())
    }

    @Test
    fun `explicit project uses own ref when issue links to different project`() {
        val otherProject = EntityRef.valueOf("emodel/project@OTHER")
        whenever(resolver.resolve(any())).thenReturn(
            ResolvedMailboxKey(
                projectRef = otherProject,
                issueRef = issueRef,
                projectKey = "OTHER",
                issueKey = "OTHER-1"
            )
        )

        service.importEmail(
            ProjectEmailImportRequest(
                mail = mail("[OTHER-1] mismatch"),
                messageId = "<m@x>",
                inReplyTo = null,
                to = null,
                cc = null,
                explicitProjectRef = projectRef
            )
        )

        val atts = argumentCaptor<ObjectData>()
        verify(recordsService, times(1)).create(eq("emodel/activity"), atts.capture())
        assertThat(atts.firstValue.get("_parent").asText()).isEqualTo(projectRef.toString())
        // Comment skipped when issue belongs to different project than mailbox
        verify(recordsService, never()).create(eq("emodel/comment"), any<ObjectData>())
    }
}
