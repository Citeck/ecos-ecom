package ru.citeck.ecos.ecom.routes

import org.apache.camel.builder.RouteBuilder
import org.springframework.stereotype.Component
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig
import ru.citeck.ecos.ecom.processor.AddEmailActivityProcessor
import ru.citeck.ecos.ecom.processor.ReadMailboxActivityProcessor
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor

@Component
class ReadMailboxActivityRoute(
    private val readMailboxActivityProcessor: ReadMailboxActivityProcessor
) : RouteBuilder() {

    @EcosConfig("mail-inbox-activity")
    private lateinit var imap: String

    override fun configure() {

        EcomCamelMailUtils.fromMailUri(this, imap)
            .to("log:raw-email?showHeaders=true")
            .bean(MailBodyExtractor::class.java, "extract(*)")
            .process(EcomMailReaderProcessor())
            .to("log:parsed-email?level=DEBUG")
            .setVariable(AddEmailActivityProcessor.MAIL_VARIABLE, simple("\${body}"))
            .process(readMailboxActivityProcessor)
            .setBody(simple(""))
            .choice()
            .`when`(simple("\${exchangeProperty.${ReadMailboxActivityProcessor.SKIP_MESSAGE_PROPERTY}} != 'true'"))
            .to("direct:addMailActivity")
    }
}
