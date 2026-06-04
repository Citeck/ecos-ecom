package ru.citeck.ecos.ecom.routes

import org.apache.camel.builder.RouteBuilder
import org.springframework.stereotype.Component
import ru.citeck.ecos.ecom.processor.ReadMailboxPTProcessor
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor
import ru.citeck.ecos.ecom.processor.pt.PollProjectMailboxesProcessor
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor

@Component
class ReadProjectMailboxesRoute(
    private val pollProcessor: PollProjectMailboxesProcessor,
    private val ptProcessor: ReadMailboxPTProcessor
) : RouteBuilder() {

    companion object {
        const val TIMER_ROUTE_ID = "readProjectMailboxesTimerRoute"
        const val IMPORT_ROUTE_ID = "ptPerProjectImportRoute"
        const val IMPORT_ENDPOINT = "direct:pt-project-import"
    }

    override fun configure() {

        onException(Exception::class.java)
            .handled(true)
            .log("Project mailbox import failed: \${exception.message}")

        from("timer:pt-project-mailboxes?period=60000&delay=10000")
            .routeId(TIMER_ROUTE_ID)
            .process(pollProcessor)

        from(IMPORT_ENDPOINT)
            .routeId(IMPORT_ROUTE_ID)
            .bean(MailBodyExtractor::class.java, "extract(*)")
            .process(EcomMailReaderProcessor())
            .process(ptProcessor)
    }
}
