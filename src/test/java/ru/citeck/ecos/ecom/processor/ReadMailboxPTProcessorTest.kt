package ru.citeck.ecos.ecom.processor

import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.ecom.service.pt.ProjectEmailImportRequest
import ru.citeck.ecos.ecom.service.pt.ProjectEmailImportService
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.time.Instant

class ReadMailboxPTProcessorTest {

    private lateinit var camelCtx: DefaultCamelContext
    private lateinit var importService: ProjectEmailImportService
    private lateinit var processor: ReadMailboxPTProcessor

    private val activityRef = EntityRef.valueOf("emodel/activity@A-1")
    private val projectRef = EntityRef.valueOf("emodel/project@PRJCTMNG")

    @BeforeEach
    fun setup() {
        camelCtx = DefaultCamelContext()
        camelCtx.start()
        importService = mock()
        processor = ReadMailboxPTProcessor(importService)
    }

    @AfterEach
    fun tearDown() {
        camelCtx.stop()
    }

    private fun mail(subject: String = "[PRJCTMNG] Hi"): EcomMail = EcomMail(
        from = "Alice <alice@example.com>",
        fromAddress = "alice@example.com",
        fromDomain = "example.com",
        subject = subject,
        content = "<p>Body</p>",
        date = Instant.parse("2026-04-17T10:00:00Z"),
        attachments = emptyList()
    )

    private fun exchange(
        body: Any? = mail(),
        messageId: String? = "<m1@example.com>",
        inReplyTo: String? = null,
        to: String? = "pt@x",
        cc: String? = null,
        projectProp: Any? = null
    ): DefaultExchange {
        val ex = DefaultExchange(camelCtx)
        ex.getIn().body = body
        messageId?.let { ex.getIn().setHeader("Message-ID", it) }
        inReplyTo?.let { ex.getIn().setHeader("In-Reply-To", it) }
        to?.let { ex.getIn().setHeader("To", it) }
        cc?.let { ex.getIn().setHeader("Cc", it) }
        projectProp?.let { ex.setProperty(ReadMailboxPTProcessor.PROJECT_REF_PROPERTY, it) }
        return ex
    }

    private fun outcome(ex: DefaultExchange): ReadMailboxPTProcessor.ImportOutcome? = ex.getProperty(
        ReadMailboxPTProcessor.IMPORT_RESULT_PROPERTY,
        ReadMailboxPTProcessor.ImportOutcome::class.java
    )

    @Test
    fun `imported result maps to IMPORTED and forwards mail fields`() {
        whenever(importService.importEmail(any())).thenReturn(
            ProjectEmailImportService.ImportResult.Imported(activityRef)
        )
        val ex = exchange(inReplyTo = "<parent@x>", cc = "watch@x", projectProp = projectRef)

        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.IMPORTED)
        val captor = argumentCaptor<ProjectEmailImportRequest>()
        verify(importService).importEmail(captor.capture())
        val req = captor.firstValue
        assertThat(req.messageId).isEqualTo("<m1@example.com>")
        assertThat(req.inReplyTo).isEqualTo("<parent@x>")
        assertThat(req.to).isEqualTo("pt@x")
        assertThat(req.cc).isEqualTo("watch@x")
        assertThat(req.mail.subject).isEqualTo("[PRJCTMNG] Hi")
        assertThat(req.explicitProjectRef).isEqualTo(projectRef)
    }

    @Test
    fun `duplicate result maps to DUPLICATE`() {
        whenever(importService.importEmail(any()))
            .thenReturn(ProjectEmailImportService.ImportResult.Duplicate)

        val ex = exchange()
        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.DUPLICATE)
    }

    @Test
    fun `no target result maps to NO_TARGET`() {
        whenever(importService.importEmail(any()))
            .thenReturn(ProjectEmailImportService.ImportResult.NoTarget)

        val ex = exchange()
        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.NO_TARGET)
    }

    @Test
    fun `failed result maps to FAILED`() {
        whenever(importService.importEmail(any()))
            .thenReturn(ProjectEmailImportService.ImportResult.Failed("boom"))

        val ex = exchange()
        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.FAILED)
    }

    @Test
    fun `import exception maps to FAILED`() {
        whenever(importService.importEmail(any())).thenThrow(RuntimeException("kaboom"))

        val ex = exchange()
        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.FAILED)
    }

    @Test
    fun `null body sets NO_TARGET and skips import`() {
        val ex = exchange(body = null)

        processor.process(ex)

        assertThat(outcome(ex)).isEqualTo(ReadMailboxPTProcessor.ImportOutcome.NO_TARGET)
        verify(importService, never()).importEmail(any())
    }

    @Test
    fun `project ref property as string is parsed into request`() {
        whenever(importService.importEmail(any()))
            .thenReturn(ProjectEmailImportService.ImportResult.Imported(activityRef))

        val ex = exchange(projectProp = projectRef.toString())
        processor.process(ex)

        val captor = argumentCaptor<ProjectEmailImportRequest>()
        verify(importService).importEmail(captor.capture())
        assertThat(captor.firstValue.explicitProjectRef).isEqualTo(projectRef)
    }

    @Test
    fun `missing project ref property yields empty explicit ref`() {
        whenever(importService.importEmail(any()))
            .thenReturn(ProjectEmailImportService.ImportResult.Imported(activityRef))

        val ex = exchange(projectProp = null)
        processor.process(ex)

        val captor = argumentCaptor<ProjectEmailImportRequest>()
        verify(importService).importEmail(captor.capture())
        assertThat(EntityRef.isEmpty(captor.firstValue.explicitProjectRef)).isTrue()
    }
}
