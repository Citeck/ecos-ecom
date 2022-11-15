package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.OrderPassProcessor;
import ru.citeck.ecos.ecom.processor.ReadMailboxProcessor;

import java.util.Objects;

@Component
public class ReadMailboxRoute extends RouteBuilder {

    static final String ROUTE_ID = "readMailboxRoute";

    @EcosConfig("mail-inbox")
    private String imap;

    @Autowired
    private ReadMailboxProcessor readMailboxProcessor;

    @Override
    public void configure() {
        from("imap://" + imap)
                .autoStartup(!Objects.equals(imap, "disabled"))
                .to("log:INFO?showHeaders=true")
                .process(readMailboxProcessor)
                .choice()
                    .when(simple("${exchangeProperty.subject} == 'deal'"))
                        .to("direct:createDeal")
                    .when(simple("${exchangeProperty.subject} == 'other'"))
                        .to("direct:createOtherDeal");
    }
}

