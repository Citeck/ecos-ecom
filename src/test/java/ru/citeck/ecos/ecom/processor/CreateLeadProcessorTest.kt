package ru.citeck.ecos.ecom.processor

import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.citeck.ecos.ecom.dto.MailDTO
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.Date

class CreateLeadProcessorTest {

    private lateinit var camelCtx: DefaultCamelContext
    private lateinit var processor: CreateLeadProcessor

    @BeforeEach
    fun setup() {
        camelCtx = DefaultCamelContext()
        camelCtx.start()

        val recordsService = mock<RecordsService>()
        whenever(recordsService.queryOne(any<RecordsQuery>())).thenAnswer { invocation ->
            val query = invocation.getArgument<RecordsQuery>(0)
            if (query.sourceId == "emodel/ecos-counterparty") {
                null
            } else {
                EntityRef.valueOf("${query.sourceId}@mail")
            }
        }

        val patterns = listOf(
            "(?m)(?<=От:).*$", // from
            "(?m)(?<=Компания:).*$", // company
            "(?m)(?<=Тема:).*$", // subject
            "(?m)(?<=ФИО:).*$", // fio
            "(?m)(?<=Должность:).*$", // position
            "(?m)(?<=Департамент:).*$", // department
            "(?m)(?<=Телефон:).*$", // phone
            "(?m)(?<=E-mail:).*$", // email
            "Комментарий:([\\s\\S\\n]+)Страница перехода", // comment
            "(?m)(?<=Страница перехода:).*$", // siteFrom
            "(?m)(?<=Количество пользователей:).*$", // numberOfUsers
            "(?m)(?<=GA:).*$", // gaClientId
            "(?m)(?<=YM:).*$" // ymClientId
        )
        val constructor = CreateLeadProcessor::class.java.getDeclaredConstructor(
            *Array(patterns.size) { String::class.java }
        )
        constructor.isAccessible = true
        processor = constructor.newInstance(*patterns.toTypedArray())
        processor.setRecordsService(recordsService)
    }

    @AfterEach
    fun tearDown() {
        camelCtx.stop()
    }

    @Test
    fun plainEmailIsNamedAfterSenderAndDescriptionStartsWithSubject() {
        val lead = process(
            from = "\"CFO Russia\" <info@cfo-russia.ru>",
            fromAddress = "info@cfo-russia.ru",
            subject = "Главные материалы августа",
            content = "Уважаемые коллеги!"
        )

        assertThat(lead["name"]).isEqualTo("CFO Russia")
        assertThat(lead["description"] as String).startsWith(
            "<b>Тема письма:</b> Главные материалы августа<br>" +
                "<br><b>Почтовое сообщение:</b><br>Уважаемые коллеги!"
        )
    }

    @Test
    fun siteFormWithCompanyKeepsCompanyName() {
        val lead = process(
            from = "Citeck Site <noreply@citeck.ru>",
            fromAddress = "noreply@citeck.ru",
            subject = "Заявка с сайта",
            content = "Компания: ООО Ромашка\nФИО: Иван Петров\nE-mail: ivan@romashka.ru\n"
        )

        assertThat(lead["name"]).isEqualTo("ООО Ромашка")
        assertThat(lead["counterpartyText"]).isEqualTo("ООО Ромашка")
    }

    @Test
    fun emailWithoutSubjectStartsWithMailBlock() {
        val lead = process(
            from = "leah@sendnovo.com",
            fromAddress = "leah@sendnovo.com",
            subject = null,
            content = "Hi"
        )

        assertThat(lead["name"]).isEqualTo("leah@sendnovo.com")
        assertThat(lead["description"] as String).startsWith("<br><b>Почтовое сообщение:</b><br>Hi")
    }

    private fun process(from: String, fromAddress: String, subject: String?, content: String): Map<*, *> {
        val mail = MailDTO()
        mail.from = from
        mail.fromAddress = fromAddress
        mail.subject = subject
        mail.content = content
        mail.date = Date()
        mail.kind = "email"

        val exchange = DefaultExchange(camelCtx)
        exchange.setProperty("subject", "lead")
        exchange.getIn().body = mail
        processor.process(exchange)

        return exchange.getIn().body as Map<*, *>
    }
}
