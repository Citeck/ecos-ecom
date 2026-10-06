package ru.citeck.ecos.ecom.routes

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spi.Injector
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.citeck.ecos.ecom.dto.MailDTO
import ru.citeck.ecos.ecom.processor.CreateLeadProcessor
import ru.citeck.ecos.ecom.processor.MailLeadTestConfig
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.Date

/**
 * Checks what the lead route actually saves, not only what the processor builds.
 */
class LeadRoutesTest {

    private lateinit var camelCtx: DefaultCamelContext
    private val mutated = mutableListOf<RecordAtts>()

    @BeforeEach
    fun setup() {
        val recordsService = mock<RecordsService>()
        whenever(recordsService.queryOne(any<RecordsQuery>())).thenAnswer { invocation ->
            val query = invocation.getArgument<RecordsQuery>(0)
            if (query.sourceId == "emodel/ecos-counterparty") null else EntityRef.valueOf("${query.sourceId}@mail")
        }
        whenever(recordsService.mutate(any<RecordAtts>())).thenAnswer { invocation ->
            val atts = invocation.getArgument<RecordAtts>(0)
            mutated.add(atts)
            EntityRef.create("emodel", "lead", "1")
        }

        val processor = MailLeadTestConfig.createLeadProcessor(recordsService)

        val endpoint = RecordsDaoEndpoint()
        endpoint.recordsService = recordsService

        camelCtx = DefaultCamelContext()
        camelCtx.injector = EndpointInjector(camelCtx.injector, endpoint)
        camelCtx.addRoutes(withProcessor(CreateLeadRoute(), processor))
        camelCtx.start()
    }

    @AfterEach
    fun tearDown() {
        camelCtx.stop()
    }

    @ParameterizedTest
    // A site form without the person is named after its subject, an ordinary email after the sender
    @CsvSource("lead, Главные материалы августа", "other, CFO Russia")
    fun siteFormsAndOtherEmailsSaveLeadNameAndDescription(subjectProperty: String, name: String) {
        val mail = MailDTO()
        mail.from = "\"CFO Russia\" <info@cfo-russia.ru>"
        mail.fromAddress = "info@cfo-russia.ru"
        mail.subject = "Главные материалы августа"
        mail.content = "Уважаемые коллеги!"
        mail.date = Date()
        mail.kind = "other"

        camelCtx.createProducerTemplate().sendBodyAndProperty("direct:createLead", mail, "subject", subjectProperty)

        assertThat(mutated).hasSize(1)
        val atts = mutated[0]
        assertThat(atts.getAtt("name").asText()).isEqualTo(name)
        assertThat(atts.getAtt("description").asText())
            .startsWith("<b>Тема письма:</b> Главные материалы августа<br>")
    }

    @ParameterizedTest
    @CsvSource("lead, true", "other, true", "mail-activity, false")
    fun siteFormsAndOtherEmailsAreRoutedToLeadCreation(subjectProperty: String, createsLead: Boolean) {
        val exchange = DefaultExchange(camelCtx)
        exchange.setProperty("subject", subjectProperty)

        val condition = camelCtx.resolveLanguage("simple").createPredicate(ReadMailboxCRMRoute.CREATE_LEAD_CONDITION)
        assertThat(condition.matches(exchange)).isEqualTo(createsLead)
    }

    private fun withProcessor(route: RouteBuilder, processor: CreateLeadProcessor): RouteBuilder {
        val field = route.javaClass.getDeclaredField("createLeadProcessor")
        field.isAccessible = true
        field.set(route, processor)
        return route
    }

    /**
     * The routes call RecordsDaoEndpoint by class, so Camel creates it through the injector
     */
    private class EndpointInjector(
        private val delegate: Injector,
        private val endpoint: RecordsDaoEndpoint
    ) : Injector by delegate {

        override fun <T : Any?> newInstance(type: Class<T>): T {
            return if (type == RecordsDaoEndpoint::class.java) type.cast(endpoint) else delegate.newInstance(type)
        }

        override fun <T : Any?> newInstance(type: Class<T>, postProcessBean: Boolean): T {
            return if (type == RecordsDaoEndpoint::class.java) {
                type.cast(endpoint)
            } else {
                delegate.newInstance(type, postProcessBean)
            }
        }
    }
}
