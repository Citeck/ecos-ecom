package ru.citeck.ecos.ecom.processor.pt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.camel.CamelContext
import org.apache.camel.ConsumerTemplate
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.apache.camel.ProducerTemplate
import org.apache.commons.lang3.StringUtils
import org.springframework.stereotype.Component
import ru.citeck.ecos.context.lib.auth.AuthContext
import ru.citeck.ecos.ecom.processor.ReadMailboxPTProcessor
import ru.citeck.ecos.ecom.routes.ReadProjectMailboxesRoute
import ru.citeck.ecos.ecom.service.pt.MailboxMessageMover
import ru.citeck.ecos.records2.predicate.PredicateService
import ru.citeck.ecos.records2.predicate.model.Predicates
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.secrets.lib.EcosSecrets
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Properties

@Component
class PollProjectMailboxesProcessor(
    private val recordsService: RecordsService,
    private val camelContext: CamelContext,
    private val consumerTemplate: ConsumerTemplate,
    private val producerTemplate: ProducerTemplate,
    private val mailboxMessageMover: MailboxMessageMover
) : Processor {

    companion object {
        const val PROJECT_SOURCE_ID = "emodel/project"
        const val RECEIVE_TIMEOUT_MS = 5_000L
        const val MAX_MESSAGES_PER_PROJECT = 100

        private val log = KotlinLogging.logger {}
    }

    override fun process(exchange: Exchange) {
        val projects = AuthContext.runAsSystem { queryEnabledProjects() }
        if (projects.isEmpty()) {
            return
        }
        log.debug { "Polling ${projects.size} project mailbox(es)" }
        for (project in projects) {
            try {
                val count = pollProject(project)
                AuthContext.runAsSystem { updateProjectStatus(project.id, null) }
                if (count > 0) {
                    log.info { "Imported $count email(s) for project ${project.id}" }
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to poll mailbox for project ${project.id}" }
                AuthContext.runAsSystem { updateProjectStatus(project.id, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun queryEnabledProjects(): List<ProjectMailboxInfo> {
        return recordsService.query(
            RecordsQuery.create()
                .withSourceId(PROJECT_SOURCE_ID)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("ept-project-mailbox:mailboxEnabled", true))
                .withMaxItems(1000)
                .build(),
            ProjectMailboxInfo::class.java
        ).getRecords()
    }

    private fun pollProject(project: ProjectMailboxInfo): Int {
        val context = AuthContext.runAsSystem { buildMailboxContext(project) } ?: return 0
        mailboxMessageMover.probeConnection(context.baseImapUrl, context.username, context.password)
        val endpoint = camelContext.getEndpoint(context.endpointUri)

        var processedCount = 0
        while (processedCount < MAX_MESSAGES_PER_PROJECT) {
            val received = consumerTemplate.receive(endpoint, RECEIVE_TIMEOUT_MS) ?: break
            try {
                prepareExchange(received, project.id)
                producerTemplate.send(ReadProjectMailboxesRoute.IMPORT_ENDPOINT, received)
                moveAfterImport(received, context)
                processedCount++
            } finally {
                consumerTemplate.doneUoW(received)
            }
        }
        return processedCount
    }

    private fun prepareExchange(exchange: Exchange, projectRef: EntityRef) {
        exchange.setProperty(ReadMailboxPTProcessor.PROJECT_REF_PROPERTY, projectRef)
    }

    private fun moveAfterImport(exchange: Exchange, context: MailboxContext) {
        val outcome = exchange.getProperty(ReadMailboxPTProcessor.IMPORT_RESULT_PROPERTY)
            as? ReadMailboxPTProcessor.ImportOutcome
            ?: return
        val targetFolder = when (outcome) {
            ReadMailboxPTProcessor.ImportOutcome.IMPORTED,
            ReadMailboxPTProcessor.ImportOutcome.DUPLICATE -> context.successFolder
            ReadMailboxPTProcessor.ImportOutcome.NO_TARGET,
            ReadMailboxPTProcessor.ImportOutcome.FAILED -> context.errorFolder
        }?.takeIf { it.isNotBlank() } ?: return

        val messageId = exchange.getIn().getHeader("Message-ID", String::class.java)?.let { raw ->
            raw.trim().removePrefix("<").removeSuffix(">")
        }
        mailboxMessageMover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = context.baseImapUrl,
                username = context.username,
                password = context.password,
                sourceFolder = context.sourceFolder,
                targetFolder = targetFolder,
                messageId = messageId
            )
        )
    }

    private fun buildMailboxContext(project: ProjectMailboxInfo): MailboxContext? {
        if (StringUtils.isBlank(project.imap)) {
            log.warn { "Project ${project.id} has no IMAP URL" }
            return null
        }
        if (EntityRef.isEmpty(project.credentials)) {
            log.warn { "Project ${project.id} has no credentials" }
            return null
        }
        val secretData = EcosSecrets.getBasicDataOrNull(project.credentials.getLocalId())
        if (secretData == null) {
            log.warn { "Project ${project.id} secret ${project.credentials} not found" }
            return null
        }
        val username = secretData.username
        val password = secretData.password
        if (username.isBlank() || password.isBlank()) {
            log.warn { "Project ${project.id} credentials ${project.credentials} are incomplete" }
            return null
        }

        val sourceFolder = project.folder?.takeIf { it.isNotBlank() } ?: "INBOX"
        val sep = if (project.imap.contains("?")) "&" else "?"
        val additionalProps = Properties().apply {
            setProperty("mail.imap.partialfetch", "false")
            setProperty("mail.imaps.partialfetch", "false")
        }
        val registryKey = "ptMailProps_${project.id.getLocalId()}"
        camelContext.registry.bind(registryKey, additionalProps)

        val endpointUri = project.imap + sep +
            "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
            "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8) +
            "&folderName=" + URLEncoder.encode(sourceFolder, StandardCharsets.UTF_8) +
            "&delete=false&unseen=true&mapMailMessage=true" +
            "&additionalJavaMailProperties=#$registryKey"

        return MailboxContext(
            endpointUri = endpointUri,
            baseImapUrl = project.imap,
            username = username,
            password = password,
            sourceFolder = sourceFolder,
            successFolder = project.successFolder,
            errorFolder = project.errorFolder
        )
    }

    private fun updateProjectStatus(projectRef: EntityRef, error: String?) {
        val atts = mapOf(
            "ept-project-mailbox:mailboxLastSync" to Instant.now(),
            "ept-project-mailbox:mailboxLastError" to (error ?: "")
        )
        recordsService.mutate(projectRef, atts)
    }

    private data class MailboxContext(
        val endpointUri: String,
        val baseImapUrl: String,
        val username: String,
        val password: String,
        val sourceFolder: String,
        val successFolder: String?,
        val errorFolder: String?
    )

    data class ProjectMailboxInfo(
        @AttName("?id")
        val id: EntityRef = EntityRef.EMPTY,
        @AttName("ept-project-mailbox:mailboxImap")
        val imap: String = "",
        @AttName("ept-project-mailbox:mailboxCredentials?id")
        val credentials: EntityRef = EntityRef.EMPTY,
        @AttName("ept-project-mailbox:mailboxFolder")
        val folder: String? = null,
        @AttName("ept-project-mailbox:mailboxSuccessFolder")
        val successFolder: String? = null,
        @AttName("ept-project-mailbox:mailboxErrorFolder")
        val errorFolder: String? = null
    )
}
