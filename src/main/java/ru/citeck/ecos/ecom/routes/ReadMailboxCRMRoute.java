package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.AddEmailActivityProcessor;
import ru.citeck.ecos.ecom.processor.ReadMailboxCRMProcessor;
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor;

@Component
public class ReadMailboxCRMRoute extends RouteBuilder {

    static final String ROUTE_ID = "readMailboxCRMRoute";

    @EcosConfig("mail-inbox-crm")
    private String imap;

    @Autowired
    private ReadMailboxCRMProcessor readMailboxCRMProcessor;

    @Override
    public void configure() {

        EcomCamelMailUtils.fromMailUri(this, imap)
                .to("log:DEBUG?showHeaders=true")
                .bean(MailBodyExtractor.class, "extract(*)")
                .process(new EcomMailReaderProcessor())
                .to("log:parsed-email?level=DEBUG")
                .setVariable(AddEmailActivityProcessor.MAIL_VARIABLE, simple("${body}"))
                .process(readMailboxCRMProcessor)
                .choice()
                    .when(simple("${exchangeProperty.subject} == 'lead'"))
                        .to("direct:createLead")
                    .when(simple("${exchangeProperty.subject} == 'other'"))
                        .to("direct:createOtherLead")
                    .when(simple("${exchangeProperty.subject} == 'mail-activity'"))
                        .setBody(simple(""))
                        .to("direct:addMailActivity")
                .end()
                .choice()
                    .when(simple("${exchangeProperty.subject} != 'mail-activity' && ${variable.mail} != '' " +
                        "&& ${variable.mail.attachments.size()} > 0"))
                    .to("direct:addMailActivity");
    }
}

