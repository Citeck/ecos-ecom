package ru.citeck.ecos.ecom.service.pt

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Properties

@Component
class MailboxMessageMover {

    companion object {
        private const val DEFAULT_IMAP_PORT = 143
        private const val DEFAULT_IMAPS_PORT = 993
        private val log = KotlinLogging.logger {}
    }

    fun move(params: MoveParams): Boolean {
        val targetFolderName = params.targetFolder.trim()
        if (targetFolderName.isBlank()) {
            return false
        }
        val messageId = params.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null) {
            log.warn { "Cannot move: empty Message-ID for ${params.imapUrl}/${params.sourceFolder}" }
            return false
        }
        val uri = URI(params.imapUrl)
        val protocol = (uri.scheme ?: "imap").lowercase()
        val host = uri.host ?: error("IMAP URL has no host: ${params.imapUrl}")
        val port = if (uri.port > 0) uri.port else defaultPort(protocol)

        val props = Properties().apply {
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", host)
            setProperty("mail.$protocol.port", port.toString())
            setProperty("mail.$protocol.partialfetch", "false")
        }

        val session = Session.getInstance(props)
        val store = session.getStore(protocol)
        try {
            store.connect(host, port, params.username, params.password)
            val source = store.getFolder(params.sourceFolder.ifBlank { "INBOX" })
            source.open(Folder.READ_WRITE)
            try {
                val candidates = findByMessageId(source.messages, messageId)
                if (candidates.isEmpty()) {
                    log.warn {
                        "Message-ID '$messageId' not found in " +
                            "${params.sourceFolder} on $host — skipping move to $targetFolderName"
                    }
                    return false
                }
                val target = store.getFolder(targetFolderName)
                if (!target.exists()) {
                    target.create(Folder.HOLDS_MESSAGES)
                }
                source.copyMessages(candidates, target)
                candidates.forEach { it.setFlag(Flags.Flag.DELETED, true) }
                log.info {
                    "Moved ${candidates.size} message(s) with Message-ID '$messageId' " +
                        "from ${params.sourceFolder} to $targetFolderName on $host"
                }
                return true
            } finally {
                source.close(true)
            }
        } catch (e: Exception) {
            log.error(e) {
                "Failed to move Message-ID '$messageId' from ${params.sourceFolder} " +
                    "to $targetFolderName on $host"
            }
            return false
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Opens a short-lived IMAP connection to verify that credentials and host are valid.
     * Throws the underlying [jakarta.mail.MessagingException] if authentication or connect fails —
     * allowing the caller to surface the error (Camel's own polling consumer swallows connect errors).
     */
    fun probeConnection(imapUrl: String, username: String, password: String) {
        val uri = URI(imapUrl)
        val protocol = (uri.scheme ?: "imap").lowercase()
        val host = uri.host ?: error("IMAP URL has no host: $imapUrl")
        val port = if (uri.port > 0) uri.port else defaultPort(protocol)

        val props = Properties().apply {
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", host)
            setProperty("mail.$protocol.port", port.toString())
            setProperty("mail.$protocol.connectiontimeout", "5000")
            setProperty("mail.$protocol.timeout", "5000")
        }

        val session = Session.getInstance(props)
        val store = session.getStore(protocol)
        try {
            store.connect(host, port, username, password)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun findByMessageId(messages: Array<Message>, messageId: String): Array<Message> {
        val normalized = messageId.trim().removePrefix("<").removeSuffix(">")
        return messages.filter { message ->
            val headers = message.getHeader("Message-ID") ?: return@filter false
            headers.any { it.trim().removePrefix("<").removeSuffix(">") == normalized }
        }.toTypedArray()
    }

    private fun defaultPort(protocol: String): Int = when (protocol) {
        "imaps" -> DEFAULT_IMAPS_PORT
        else -> DEFAULT_IMAP_PORT
    }

    data class MoveParams(
        val imapUrl: String,
        val username: String,
        val password: String,
        val sourceFolder: String,
        val targetFolder: String,
        val messageId: String?
    )
}
