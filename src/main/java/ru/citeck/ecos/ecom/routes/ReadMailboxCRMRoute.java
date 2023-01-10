package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.ReadMailboxCRMProcessor;

import java.util.Objects;

@Component
public class ReadMailboxCRMRoute extends RouteBuilder {

    static final String ROUTE_ID = "readMailboxCRMRoute";

    @EcosConfig("mail-inbox-crm")
    private String imap;

    @Autowired
    private ReadMailboxCRMProcessor readMailboxCRMProcessor;

    @Override
    public void configure() {
        from(imap)
                .autoStartup(!Objects.equals(imap, "disabled"))
                .to("log:INFO?showHeaders=true")
                .process(readMailboxCRMProcessor)
                .choice()
                    .when(simple("${exchangeProperty.subject} == 'deal'"))
                        .to("direct:createDeal")
                    .when(simple("${exchangeProperty.subject} == 'other'"))
                        .to("direct:createOtherDeal");
    }
}

