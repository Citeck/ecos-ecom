package ru.citeck.ecos.ecom.processor

import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.citeck.ecos.commons.data.DataValue
import ru.citeck.ecos.ecom.dto.MailDTO
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.Date

class CreateLeadProcessorTest {

    companion object {
        private const val SITE_FORM = "lead"
        private const val OTHER_EMAIL = "other"
        private const val ROBOT = "Citeck Site <noreply@citeck.ru>"
        private const val ROBOT_ADDRESS = "noreply@citeck.ru"
        private val COUNTERPARTY = EntityRef.valueOf("emodel/ecos-counterparty@romashka")
        private val COUNTERPARTY_WITH_CONTACT = EntityRef.valueOf("emodel/ecos-counterparty@lutik")
        private const val MAIL_SOURCE = "emodel/deal-request-source@mail"
    }

    private lateinit var camelCtx: DefaultCamelContext
    private lateinit var processor: CreateLeadProcessor
    private val counterpartyQueries = mutableListOf<RecordsQuery>()
    private lateinit var recordsService: RecordsService

    @BeforeEach
    fun setup() {
        camelCtx = DefaultCamelContext()
        camelCtx.start()

        recordsService = mock<RecordsService>()
        whenever(recordsService.queryOne(any<RecordsQuery>())).thenAnswer { invocation ->
            val query = invocation.getArgument<RecordsQuery>(0)
            if (query.sourceId == "emodel/ecos-counterparty") {
                counterpartyQueries.add(query)
                // "ромашка" is "ООО Ромашка" without contacts, "лютик" has a contact saved without a phone
                val predicate = query.query.toString()
                when {
                    predicate.contains("\"ромашка\"") -> COUNTERPARTY
                    predicate.contains("\"лютик\"") -> COUNTERPARTY_WITH_CONTACT
                    else -> null
                }
            } else {
                // A category or a source is found by its id
                EntityRef.valueOf("${query.sourceId}@${query.query.get("val").asText()}")
            }
        }

        whenever(recordsService.getAtt(eq(COUNTERPARTY), eq("fullOrganizationName")))
            .thenReturn(DataValue.createStr("ООО Ромашка"))
        whenever(recordsService.getAtt(eq(COUNTERPARTY), eq("contacts[]?json"))).thenReturn(DataValue.createArr())
        whenever(recordsService.getAtt(eq(COUNTERPARTY_WITH_CONTACT), eq("fullOrganizationName")))
            .thenReturn(DataValue.createStr("АО Лютик"))
        whenever(recordsService.getAtt(eq(COUNTERPARTY_WITH_CONTACT), eq("contacts[]?json"))).thenReturn(
            DataValue.createArr().add(
                DataValue.createObj().set("contactFio", "Пётр Сидоров").set("contactEmail", "petr@lutik.ru")
            )
        )

        processor = MailLeadTestConfig.createLeadProcessor(recordsService)
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
        assertThat(lead["createdAutomatically"]).isEqualTo(false)
    }

    @Test
    fun plainEmailContactIsNamedAfterSender() {
        val lead = process(
            from = "Leah Smith <leah@sendnovo.com>",
            fromAddress = "leah@sendnovo.com",
            subject = "Partnership",
            content = "Hi"
        )

        assertThat(lead["name"]).isEqualTo("Leah Smith")
        assertThat(mainContact(lead)["contactFio"]).isEqualTo("Leah Smith")
        assertThat(mainContact(lead)["contactEmail"]).isEqualTo("leah@sendnovo.com")
    }

    @Test
    fun plainEmailIgnoresFormFieldsQuotedInBody() {
        val lead = process(
            from = "Leah Smith <leah@sendnovo.com>",
            fromAddress = "leah@sendnovo.com",
            subject = "Re: заявка",
            content = "Спасибо!\n\nОт: Мария Сидорова\nКомпания: ромашка\nФИО: Иван Петров\n" +
                "E-mail: ivan@romashka.ru\nТелефон: +7 900 000-00-00\nДолжность: CEO\n"
        )

        assertThat(lead["name"]).isEqualTo("Leah Smith")
        assertThat(lead["counterparty"]).isNull()
        assertThat(lead["counterpartyText"]).isNull()
        assertThat(counterpartyQueries).isEmpty()
        assertThat(mainContact(lead)).containsEntry("contactFio", "Leah Smith")
            .containsEntry("contactEmail", "leah@sendnovo.com")
            .containsEntry("contactPhone", "")
            .containsEntry("contactPosition", "")
            .containsEntry("contactDepartment", "")
    }

    @Test
    fun plainEmailIgnoresTrackingAndCommentOfQuotedForm() {
        val lead = process(
            from = "Leah Smith <leah@sendnovo.com>",
            fromAddress = "leah@sendnovo.com",
            subject = "Hello",
            content = "Комментарий: старая заявка\nСтраница перехода\nYM_client_ID: 123\nGA_client_ID: 456\n" +
                "Страница заполнения заявки - https://citeck.ru/demo\n"
        )

        assertThat(lead["ymClientId"]).isEqualTo("")
        assertThat(lead["gaClientId"]).isEqualTo("")
        assertThat(lead["siteFrom"]).isEqualTo("")
        assertThat(lead["requestSource"]).isEqualTo(MAIL_SOURCE)
        assertThat(lead["description"] as String).startsWith(
            "<b>Тема письма:</b> Hello<br><br><b>Почтовое сообщение:</b><br>Комментарий: старая заявка"
        )
    }

    @Test
    fun plainEmailWithoutSenderNameIsNamedAfterAddress() {
        val lead = process(
            from = "leah@sendnovo.com",
            fromAddress = "leah@sendnovo.com",
            subject = "Hello",
            content = "ФИО: Leah Smith\n"
        )

        assertThat(lead["name"]).isEqualTo("leah@sendnovo.com")
        assertThat(mainContact(lead)["contactFio"]).isEqualTo("")
        assertThat(mainContact(lead)["contactEmail"]).isEqualTo("leah@sendnovo.com")
    }

    @Test
    fun plainEmailLongSenderNameIsCut() {
        val lead = process(
            from = "\"${"x".repeat(60)}\" <a@b.ru>",
            fromAddress = "a@b.ru",
            subject = "Hello",
            content = "Hi"
        )

        assertThat(lead["name"]).isEqualTo("x".repeat(MailLeadFields.MAX_NAME_LENGTH - 1) + "…")
    }

    @Test
    fun siteFormWithCompanyKeepsCompanyName() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка с сайта",
            content = "Компания: ООО Ромашка\nФИО: Иван Петров\nE-mail: ivan@romashka.ru\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("ООО Ромашка")
        assertThat(lead["counterpartyText"]).isEqualTo("ООО Ромашка")
        assertThat(lead["createdAutomatically"]).isEqualTo(true)
        assertThat(mainContact(lead)["contactFio"]).isEqualTo("Иван Петров")
    }

    @Test
    fun siteFormWithoutCompanyAndFullNameIsNamedAfterFormEmail() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Подписка на сообщество",
            content = "E-mail: ivan@romashka.ru\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("ivan@romashka.ru")
        assertThat(mainContact(lead)["contactEmail"]).isEqualTo("ivan@romashka.ru")
        // The site robot is not a person, so the contact stays without a name
        assertThat(mainContact(lead)["contactFio"]).isEqualTo("")
    }

    @Test
    fun siteFormSenderLineNamesOnlyTheContact() {
        // "От:" may be a quoted mail client header, so it is not used for the lead name
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Демо-доступ",
            content = "От: Citeck Site <noreply@citeck.ru>\nE-mail: ivan@romashka.ru\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("ivan@romashka.ru")
        assertThat(mainContact(lead)["contactFio"]).isEqualTo("Citeck Site <noreply@citeck.ru>")
    }

    @Test
    fun siteFormTrackingFieldsAreParsed() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Компания: ООО Ромашка\nYM_client_ID: 123\nGA_client_ID: 456\n" +
                "Страница заполнения заявки - https://citeck.ru/demo\n",
            route = SITE_FORM
        )

        assertThat(lead["ymClientId"]).isEqualTo("123")
        assertThat(lead["gaClientId"]).isEqualTo("456")
        assertThat(lead["siteFrom"]).isEqualTo("https://citeck.ru/demo")
        assertThat(lead["requestSource"]).isNull()
    }

    @Test
    fun siteFormMatchesCounterpartyWithIncompleteContact() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            // The same person as the saved contact, so the comparison reaches the missing phone
            content = "Компания: лютик\nФИО: Пётр Сидоров\nE-mail: petr@lutik.ru\nТелефон: +7 900\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("АО Лютик")
        val contacts = lead["contacts"] as List<*>
        assertThat(contacts).hasSize(2)
        @Suppress("UNCHECKED_CAST")
        assertThat(contacts[1] as Map<String, Any?>).containsEntry("contactFio", "Пётр Сидоров")
            .containsEntry("contactPhone", "+7 900")
            .containsEntry("contactMain", true)
    }

    @Test
    fun siteFormWithoutCompanyIsNamedAfterFullName() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "ФИО: Иван Петров\nОт: Иван\nE-mail: ivan@romashka.ru\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("Иван Петров")
    }

    @Test
    fun siteFormWithoutPersonIsNamedAfterSubject() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Скачивание Citeck Community",
            content = "Количество пользователей: 5\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("Скачивание Citeck Community")
    }

    @Test
    fun siteFormWithoutPersonAndSubjectIsNamedAfterRobot() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = null,
            content = "Количество пользователей: 5\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("Citeck Site")
    }

    @Test
    fun unknownFormWithTrackingIdKeepsItsDataAndCategory() {
        // ReadMailboxCRMProcessor routes it as a form, but with the "other" kind
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка на вебинар",
            content = "Компания: ООО Ромашка\nТелефон: +7 900\nYM_client_ID: 123\n",
            route = SITE_FORM,
            kind = "other"
        )

        assertThat(lead["name"]).isEqualTo("ООО Ромашка")
        assertThat(lead["ymClientId"]).isEqualTo("123")
        assertThat(lead["requestCategory"]).isEqualTo("emodel/deal-request-category@other")
        assertThat(lead["requestSource"]).isNull()
        assertThat(mainContact(lead)["contactPhone"]).isEqualTo("+7 900")
    }

    @Test
    fun siteFormLongCompanyIsKeptInFull() {
        val company = "Федеральное государственное унитарное предприятие «Научно-производственное объединение»"
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Компания: $company\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo(company)
    }

    @Test
    fun siteFormMatchedCounterpartyGivesItsOfficialName() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Компания: ромашка\nФИО: Иван Петров\n",
            route = SITE_FORM
        )

        assertThat(lead["name"]).isEqualTo("ООО Ромашка")
        assertThat(lead["counterparty"]).isEqualTo(COUNTERPARTY.toString())
        assertThat(lead["counterpartyText"]).isEqualTo("ООО Ромашка")
    }

    @Test
    fun bodyAndCommentAreEscapedInDescription() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Комментарий: <script>alert(1)</script>\nСтраница перехода\n<img src=x onerror=alert(2)>",
            route = SITE_FORM
        )

        val description = lead["description"] as String
        assertThat(description).doesNotContain("<script>", "<img")
        assertThat(description).contains(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "&lt;img src=x onerror=alert(2)&gt;"
        )
        assertThat(description).contains("<br><b>Почтовое сообщение:</b><br>")
    }

    @Test
    fun siteFormFromSavedContactWithoutPhoneAddsNoDuplicate() {
        // The saved contact has no phone (null), the form has no phone line (empty string)
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Компания: лютик\nФИО: Пётр Сидоров\nE-mail: petr@lutik.ru\n",
            route = SITE_FORM
        )

        assertThat(lead["contacts"] as List<*>).hasSize(1)
        verify(recordsService, never()).mutate(any<RecordAtts>())
    }

    @Test
    fun lineBreaksOfBodyAndCommentAreKept() {
        val lead = process(
            from = ROBOT,
            fromAddress = ROBOT_ADDRESS,
            subject = "Заявка",
            content = "Комментарий: строка 1\r\nстрока 2\nСтраница перехода\nФИО: Иван\n",
            route = SITE_FORM
        )

        assertThat(lead["description"] as String)
            .startsWith("<b>Тема письма:</b> Заявка<br>строка 1<br>строка 2<br><br><b>Почтовое сообщение:</b><br>")
            .endsWith("Страница перехода<br>ФИО: Иван<br>")
            .doesNotContain("\n", "\r")
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

    /**
     * @param route the "subject" exchange property as ReadMailboxCRMProcessor sets it:
     * "lead" for site form subjects, "other" for any other email
     */
    private fun process(
        from: String,
        fromAddress: String,
        subject: String?,
        content: String,
        route: String = OTHER_EMAIL,
        kind: String = if (route == OTHER_EMAIL) "other" else "consult"
    ): Map<*, *> {
        val mail = MailDTO()
        mail.from = from
        mail.fromAddress = fromAddress
        mail.subject = subject
        mail.content = content
        mail.date = Date()
        mail.kind = kind

        val exchange = DefaultExchange(camelCtx)
        exchange.setProperty("subject", route)
        exchange.getIn().body = mail
        processor.process(exchange)

        return exchange.getIn().body as Map<*, *>
    }

    @Suppress("UNCHECKED_CAST")
    private fun mainContact(lead: Map<*, *>): Map<String, Any?> {
        val contacts = lead["contacts"] as List<*>
        assertThat(contacts).hasSize(1)
        return contacts[0] as Map<String, Any?>
    }
}
