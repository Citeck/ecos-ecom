package ru.citeck.ecos.ecom.processor.pt

import org.apache.camel.CamelContext
import org.apache.camel.ConsumerTemplate
import org.apache.camel.Endpoint
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import org.apache.camel.spi.Registry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.citeck.ecos.ecom.service.pt.MailboxMessageMover
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.records3.record.dao.query.dto.res.RecsQueryRes
import ru.citeck.ecos.secrets.lib.EcosSecrets
import ru.citeck.ecos.secrets.lib.secret.basic.BasicSecretData
import ru.citeck.ecos.webapp.api.entity.EntityRef

class PollProjectMailboxesProcessorTest {

    private val recordsService: RecordsService = mock()
    private val camelContext: CamelContext = mock()
    private val consumerTemplate: ConsumerTemplate = mock()
    private val producerTemplate: ProducerTemplate = mock()
    private val mover: MailboxMessageMover = mock()

    private val processor = PollProjectMailboxesProcessor(
        recordsService,
        camelContext,
        consumerTemplate,
        producerTemplate,
        mover
    )

    private fun project(
        id: String,
        imap: String = "imap://mail-$id:143",
        credentials: String = "emodel/secret@cred-$id"
    ) = PollProjectMailboxesProcessor.ProjectMailboxInfo(
        id = EntityRef.valueOf("emodel/project@$id"),
        imap = imap,
        credentials = EntityRef.valueOf(credentials),
        folder = "INBOX",
        successFolder = "Processed",
        errorFolder = "Errors"
    )

    private fun stubEnabledProjects(vararg projects: PollProjectMailboxesProcessor.ProjectMailboxInfo) {
        val res = RecsQueryRes<PollProjectMailboxesProcessor.ProjectMailboxInfo>()
        res.setRecords(projects.toList())
        whenever(
            recordsService.query(
                any<RecordsQuery>(),
                eq(PollProjectMailboxesProcessor.ProjectMailboxInfo::class.java)
            )
        ).thenReturn(res)
    }

    private fun stubCamelInfra() {
        whenever(camelContext.registry).thenReturn(mock<Registry>())
        whenever(camelContext.getEndpoint(anyString())).thenReturn(mock<Endpoint>())
        whenever(consumerTemplate.receive(any<Endpoint>(), anyLong())).thenReturn(null)
    }

    private inline fun withSecrets(
        username: String = "user",
        password: String = "pass",
        block: () -> Unit
    ) {
        Mockito.mockStatic(EcosSecrets::class.java).use { mocked ->
            mocked.`when`<BasicSecretData?> { EcosSecrets.getBasicDataOrNull(anyString()) }
                .thenReturn(BasicSecretData(username, password))
            block()
        }
    }

    @Test
    fun `T1 all enabled projects are polled and last sync is updated`() {
        stubEnabledProjects(project("P1"), project("P2"), project("P3"))
        stubCamelInfra()

        withSecrets { processor.process(mock<Exchange>()) }

        val refCaptor = argumentCaptor<EntityRef>()
        val attsCaptor = argumentCaptor<Map<String, Any?>>()
        verify(recordsService, times(3)).mutate(refCaptor.capture(), attsCaptor.capture())
        attsCaptor.allValues.forEach { atts ->
            assertThat(atts["ept-project-mailbox:mailboxLastSync"]).isNotNull
            assertThat(atts["ept-project-mailbox:mailboxLastError"]).isEqualTo("")
        }
        assertThat(refCaptor.allValues.map { it.toString() })
            .containsExactlyInAnyOrder(
                "emodel/project@P1",
                "emodel/project@P2",
                "emodel/project@P3"
            )
    }

    @Test
    fun `T2 one failing mailbox does not block others and records last error`() {
        stubEnabledProjects(project("P1"), project("P2"), project("P3"))
        stubCamelInfra()
        whenever(mover.probeConnection(eq("imap://mail-P2:143"), anyString(), anyString()))
            .thenThrow(RuntimeException("imap down"))

        withSecrets { processor.process(mock<Exchange>()) }

        val refCaptor = argumentCaptor<EntityRef>()
        val attsCaptor = argumentCaptor<Map<String, Any?>>()
        verify(recordsService, times(3)).mutate(refCaptor.capture(), attsCaptor.capture())
        val byRef = refCaptor.allValues.map { it.toString() }.zip(attsCaptor.allValues).toMap()
        assertThat(byRef["emodel/project@P2"]!!["ept-project-mailbox:mailboxLastError"])
            .isEqualTo("imap down")
        assertThat(byRef["emodel/project@P1"]!!["ept-project-mailbox:mailboxLastError"]).isEqualTo("")
        assertThat(byRef["emodel/project@P3"]!!["ept-project-mailbox:mailboxLastError"]).isEqualTo("")
    }

    @Test
    fun `T3 empty mailbox skips import dispatch`() {
        stubEnabledProjects(project("P1"))
        stubCamelInfra()

        withSecrets { processor.process(mock<Exchange>()) }

        verify(producerTemplate, never()).send(any<String>(), any<Exchange>())
        verify(recordsService).mutate(any<EntityRef>(), any<Map<String, *>>())
    }

    @Test
    fun `blank imap project is skipped but still marked synced`() {
        stubEnabledProjects(project("P1", imap = ""))
        stubCamelInfra()

        withSecrets { processor.process(mock<Exchange>()) }

        verify(mover, never()).probeConnection(anyString(), anyString(), anyString())
        verify(recordsService).mutate(any<EntityRef>(), any<Map<String, *>>())
    }

    @Test
    fun `no enabled projects performs no work`() {
        stubEnabledProjects()

        withSecrets { processor.process(mock<Exchange>()) }

        verify(recordsService, never()).mutate(any<EntityRef>(), any<Map<String, *>>())
    }
}
