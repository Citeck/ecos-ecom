package ru.citeck.ecos.ecom.service.pt

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class MailboxMessageMoverTest {

    private lateinit var greenMail: GreenMail
    private lateinit var mover: MailboxMessageMover
    private val user = "alice@greenmail"
    private val password = "alicepass"

    @BeforeEach
    fun setUp() {
        greenMail = GreenMail(
            arrayOf(
                ServerSetup.SMTP.dynamicPort(),
                ServerSetup.IMAP.dynamicPort()
            )
        )
        greenMail.start()
        greenMail.setUser(user, password)
        mover = MailboxMessageMover()
    }

    @AfterEach
    fun tearDown() {
        greenMail.stop()
    }

    private fun imapUrl(): String = "imap://localhost:${greenMail.imap.port}"

    private fun deliverMessage(messageId: String, subject: String = "Hello") {
        val session = Session.getInstance(Properties())
        val msg = MimeMessage(session).apply {
            setFrom("sender@example.com")
            setRecipients(jakarta.mail.Message.RecipientType.TO, user)
            setSubject(subject)
            setText("body")
            saveChanges()
            setHeader("Message-ID", "<$messageId>")
        }
        greenMail.userManager.getUser(user).deliver(msg)
    }

    private fun folderCount(folderName: String): Int {
        val session = Session.getInstance(
            Properties().apply {
                setProperty("mail.store.protocol", "imap")
            }
        )
        val store = session.getStore("imap")
        store.connect("localhost", greenMail.imap.port, user, password)
        try {
            val folder = store.getFolder(folderName)
            if (!folder.exists()) return 0
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            folder.close(false)
            return count
        } finally {
            store.close()
        }
    }

    @Test
    fun `move copies message to target folder and marks source as deleted`() {
        deliverMessage("m-1@test")

        val moved = mover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = imapUrl(),
                username = user,
                password = password,
                sourceFolder = "INBOX",
                targetFolder = "Processed",
                messageId = "m-1@test"
            )
        )

        assertThat(moved).isTrue
        assertThat(folderCount("INBOX")).isEqualTo(0)
        assertThat(folderCount("Processed")).isEqualTo(1)
    }

    @Test
    fun `move creates target folder if it does not exist`() {
        deliverMessage("m-2@test")

        mover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = imapUrl(),
                username = user,
                password = password,
                sourceFolder = "INBOX",
                targetFolder = "Auto-Created",
                messageId = "m-2@test"
            )
        )

        assertThat(folderCount("Auto-Created")).isEqualTo(1)
    }

    @Test
    fun `move returns false and does not touch mailbox when messageId not found`() {
        deliverMessage("m-3@test")

        val moved = mover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = imapUrl(),
                username = user,
                password = password,
                sourceFolder = "INBOX",
                targetFolder = "Processed",
                messageId = "no-such-id@test"
            )
        )

        assertThat(moved).isFalse
        assertThat(folderCount("INBOX")).isEqualTo(1)
    }

    @Test
    fun `move returns false when target folder is blank`() {
        deliverMessage("m-4@test")

        val moved = mover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = imapUrl(),
                username = user,
                password = password,
                sourceFolder = "INBOX",
                targetFolder = "  ",
                messageId = "m-4@test"
            )
        )

        assertThat(moved).isFalse
        assertThat(folderCount("INBOX")).isEqualTo(1)
    }

    @Test
    fun `move returns false when messageId is blank`() {
        deliverMessage("m-5@test")

        val moved = mover.move(
            MailboxMessageMover.MoveParams(
                imapUrl = imapUrl(),
                username = user,
                password = password,
                sourceFolder = "INBOX",
                targetFolder = "Processed",
                messageId = ""
            )
        )

        assertThat(moved).isFalse
        assertThat(folderCount("INBOX")).isEqualTo(1)
    }
}
