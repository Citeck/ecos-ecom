package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.ReadMailboxSDProcessor;

import java.util.Objects;

@Component
public class ReadMailboxSDRoute extends RouteBuilder {

    static final String ROUTE_ID = "readMailboxSDRoute";

    @EcosConfig("mail-inbox-sd")
    private String imap;

    @Autowired
    private ReadMailboxSDProcessor readMailboxSDProcessor;

    @Override
    public void configure() {
        String endPoint = StringUtils.startsWith(imap, "imap")
                ? imap : "imap://" + imap;

        from(endPoint)
                .autoStartup(!Objects.equals(imap, "disabled"))
                .to("log:INFO?showHeaders=true")
                .process(readMailboxSDProcessor)
                .choice()
                    .when(header("client").isNotNull())
                        .to("direct:createSD")
                    .otherwise()
                        .log("No client found with email domain: ${header.fromDomain}")
                    .end();
    }
}

