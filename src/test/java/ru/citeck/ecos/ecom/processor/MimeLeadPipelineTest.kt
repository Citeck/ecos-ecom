package ru.citeck.ecos.ecom.processor

import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import org.apache.camel.Exchange
import org.apache.camel.component.mail.MailBinding
import org.apache.camel.component.mail.MailMessage
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.citeck.ecos.ecom.dto.MailDTO
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.Properties

class MimeLeadPipelineTest {
    private val context = DefaultCamelContext().apply { start() }

    @AfterEach
    fun close() = context.close()

    @ParameterizedTest
    @CsvSource(
        "Заявка на демо-доступ к Citeck ECOS в облаке, cloud",
        "Подписка на рассылку о Community, community-subscription"
    )
    fun foldedSubjectMatchesUnfoldedSubject(subject: String, category: String) {
        for (tracking in listOf("", "YM_client_ID: 123456.604")) {
            val folded = subject.replace("о Community", "о\r\n Community")
                .replace("ECOS в", "ECOS\r\n в")
            val body = "ФИО: Иван Проверка\nE-mail: person@example.invalid\n$tracking"
            for (header in listOf(subject, folded)) {
                val exchange = read(message(header, body))
                val mail = exchange.getIn().body as MailDTO
                assertThat(mail.subject).isEqualTo(subject)
                assertThat(mail.kind).isEqualTo(category)
                assertThat(exchange.getProperty("subject")).isEqualTo("lead")
                val lead = create(exchange)
                assertThat(lead["name"]).isEqualTo("Иван Проверка")
                assertThat(lead["createdAutomatically"]).isEqualTo(true)
            }
        }
    }

    @Test
    fun plainTextAndFormCommentKeepLiteralMarkup() {
        val literal = "Текст <img src=x onerror=alert(604)> &amp; & \"кавычки\"\n<script>alert(604)</script>\nПоследняя строка"
        for (siteForm in listOf(false, true)) {
            val body = if (siteForm) "ФИО: Иван\nКомментарий: $literal\nСтраница перехода: https://example.invalid" else literal
            val exchange = read(message(if (siteForm) "Демо-доступ" else "Обычное письмо", body))
            assertThat((exchange.getIn().body as MailDTO).content).isEqualTo(body)
            val lead = create(exchange)
            assertThat(lead["emessage"]).isEqualTo(body)
            assertThat(lead["description"].toString()).contains("&lt;img", "&lt;script&gt;", "&amp;amp;")
                .doesNotContain("<img", "<script>")
        }
    }

    @Test
    fun multipartPlainTextKeepsLiteralMarkupAndAttachment() {
        val message = message("Обычное письмо", "")
        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText("<img src=x>\n&copy;", "UTF-8") })
        multipart.addBodyPart(
            MimeBodyPart().apply {
                setText("Attachment content", "UTF-8")
                fileName = "example.txt"
                disposition = "attachment"
            }
        )
        message.setContent(multipart)
        message.saveChanges()
        val exchange = read(message)
        val mail = exchange.getIn().body as MailDTO
        assertThat(mail.content).isEqualTo("<img src=x>\n&copy;")
        assertThat(mail.attachments).hasSize(1)
        assertThat(mail.attachments.first().getName()).isEqualTo("example.txt")
    }

    @Test
    fun multipartAlternativeKeepsHtmlPreferenceAndRemovesScripts() {
        val message = message("Обычное письмо", "")
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText("Plain alternative", "UTF-8") })
        alternative.addBodyPart(
            MimeBodyPart().apply {
                setContent("<p>HTML alternative &amp; текст</p><script>alert(604)</script>", "text/html; charset=UTF-8")
            }
        )
        message.setContent(alternative)
        message.saveChanges()
        val mail = read(message).getIn().body as MailDTO
        assertThat(mail.content).isEqualTo("HTML alternative & текст")
    }

    @Test
    fun encodedFoldedSenderAndSubjectAreDecoded() {
        val message = message("", "Обычный текст")
        val name = "Имя отправителя"
        message.setHeader("From", MimeUtility.encodeText(name, "UTF-8", "B") + "\r\n <sender@example.invalid>")
        message.setHeader("Subject", MimeUtility.encodeText("Обычная", "UTF-8", "B") + "\r\n " + MimeUtility.encodeText(" тема", "UTF-8", "B"))
        val exchange = read(message)
        val mail = exchange.getIn().body as MailDTO
        assertThat(mail.subject).isEqualTo("Обычная тема")
        assertThat(mail.fromAddress).isEqualTo("sender@example.invalid")
        assertThat(create(exchange)["name"]).isEqualTo(name)
    }

    private fun message(subject: String, body: String): MimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
        setText(body, "UTF-8")
        setHeader("From", "Sender <sender@example.invalid>")
        setHeader("Subject", subject)
        saveChanges()
    }

    private fun read(message: MimeMessage): DefaultExchange {
        val exchange = DefaultExchange(context)
        exchange.exchangeExtension.fromEndpoint = context.getEndpoint("imap://localhost")
        exchange.setProperty(Exchange.BINDING, MailBinding())
        exchange.setIn(MailMessage(exchange, message, false))
        MailBodyExtractor().extract(exchange)
        EcomMailReaderProcessor().process(exchange)
        assertThat(exchange.getIn().body).isInstanceOf(EcomMail::class.java)
        MailLeadTestConfig.createReadMailboxProcessor().process(exchange)
        return exchange
    }

    private fun create(exchange: DefaultExchange): Map<*, *> {
        val records = mock<RecordsService>()
        whenever(records.queryOne(any<RecordsQuery>())).thenAnswer {
            val query = it.getArgument<RecordsQuery>(0)
            EntityRef.valueOf("${query.sourceId}@${query.query.get("val").asText()}")
        }
        MailLeadTestConfig.createLeadProcessor(records).process(exchange)
        return exchange.getIn().body as Map<*, *>
    }
}
