package ru.citeck.ecos.ecom.processor

import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import ru.citeck.ecos.ecom.dto.MailDTO
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import java.time.Instant

class ReadMailboxCRMProcessorTest {

    private lateinit var camelCtx: DefaultCamelContext
    private lateinit var processor: ReadMailboxCRMProcessor

    @BeforeEach
    fun setup() {
        camelCtx = DefaultCamelContext()
        camelCtx.start()
        processor = MailLeadTestConfig.createReadMailboxProcessor()
    }

    @AfterEach
    fun tearDown() {
        camelCtx.stop()
    }

    @ParameterizedTest
    @CsvSource(
        "Демо-доступ, demo-access",
        "Заявка на странице Цены, consult",
        "Стать партнером, partnership-request",
        "Request: Демо-доступ, demo-access",
        "[Акция] Скидки на лицензии, special-offer"
    )
    fun siteFormSubjectIsParsedAsForm(subject: String, kind: String) {
        val (route, mail) = classify(subject)

        assertThat(route).isEqualTo("lead")
        assertThat(mail.kind).isEqualTo(kind)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Re: Демо-доступ",
            "RE: Акция",
            "Fwd: Стать партнером",
            "FW: Запрос стоимости",
            "Re[2]: Демо-доступ",
            "Ответ: Получить консультацию",
            "ОТВ: Акция",
            "Пересл.: Демо-доступ",
            "  re : Акция",
            "[EXT] Re: Демо-доступ",
            "[EXT] [External] FW: Акция",
            "AW: Демо-доступ",
            "WG: Акция",
            "SV: Стать партнером",
            "TR: Запрос стоимости"
        ]
    )
    fun replyOrForwardWithFormSubjectIsOrdinaryEmail(subject: String) {
        val (route, mail) = classify(subject)

        assertThat(route).isEqualTo("other")
        assertThat(mail.kind).isEqualTo(ReadMailboxCRMProcessor.OTHER_KIND)
    }

    @Test
    fun subjectWithoutFormKeywordIsOrdinaryEmail() {
        val (route, mail) = classify("Главные материалы августа")

        assertThat(route).isEqualTo("other")
        assertThat(mail.kind).isEqualTo(ReadMailboxCRMProcessor.OTHER_KIND)
    }

    @ParameterizedTest
    @ValueSource(strings = ["<p>Имя: Иван</p>\n<p>YM_client_ID: 123</p>", "<p>GA_client_ID: GA1.2.3</p>"])
    fun unknownSubjectWithTrackingIdIsParsedAsForm(content: String) {
        val (route, mail) = classify("Заявка на вебинар", content)

        assertThat(route).isEqualTo("lead")
        assertThat(mail.kind).isEqualTo(ReadMailboxCRMProcessor.OTHER_KIND)
    }

    @Test
    fun emptyTrackingIdDoesNotMakeForm() {
        val (route, _) = classify("Заявка на вебинар", "<p>YM_client_ID:</p>\n<p>GA_client_ID: </p>")
        assertThat(route).isEqualTo("other")
    }

    @Test
    fun replyQuotingFormWithTrackingIdIsOrdinaryEmail() {
        val (route, _) = classify("Re: Заявка на вебинар", "<p>&gt; YM_client_ID: 123</p>")
        assertThat(route).isEqualTo("other")
    }

    @Test
    fun replyWithDealNumberStaysMailActivity() {
        val (route, mail) = classify("Re: Сделка #1203")

        assertThat(route).isEqualTo("mail-activity")
        assertThat(mail.leadNumber).isEqualTo("1203")
    }

    @Test
    fun replyPrefixIsMatchedOnlyAtStart() {
        assertThat(ReadMailboxCRMProcessor.isReplyOrForward("Демо-доступ: Re: вопрос")).isFalse()
        assertThat(ReadMailboxCRMProcessor.isReplyOrForward("Report: Акция")).isFalse()
        assertThat(ReadMailboxCRMProcessor.isReplyOrForward("Training: Акция")).isFalse()
        assertThat(ReadMailboxCRMProcessor.isReplyOrForward("[Акция] Скидки")).isFalse()
        assertThat(ReadMailboxCRMProcessor.isReplyOrForward(null)).isFalse()
    }

    private fun classify(subject: String, content: String = "<p>Тело письма</p>"): Pair<Any?, MailDTO> {
        val ecomMail = EcomMail(
            "Citeck Site <noreply@citeck.ru>",
            "noreply@citeck.ru",
            "citeck.ru",
            subject,
            content,
            Instant.now(),
            emptyList()
        )
        val exchange = DefaultExchange(camelCtx)
        exchange.getIn().body = ecomMail
        processor.process(exchange)

        return exchange.getProperty("subject") to exchange.getIn().body as MailDTO
    }
}
