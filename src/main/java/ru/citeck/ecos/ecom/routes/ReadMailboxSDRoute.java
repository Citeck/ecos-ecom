package ru.citeck.ecos.ecom.routes;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.ReadMailboxSDProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor;

import java.util.Objects;

@Component
public class ReadMailboxSDRoute extends RouteBuilder {

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
                .to("log:DEBUG?showHeaders=true")
                .bean(MailBodyExtractor.class, "extract(*)")
                .process(readMailboxSDProcessor)
                .choice()
                    .when(PredicateBuilder.and(
                                header("client").isNotNull(),
                                header("initiator").isNotNull()
                            )
                        )

                            .when(header("kind").isEqualTo("new")).to("direct:" + CreateSDRoute.ID)
                            .when(header("kind").isEqualTo("reply")).to("direct:" + CreateCommentRoute.ID)
                            .otherwise()
                                .log(LoggingLevel.WARN, "Unknown kind of SD message: ${header.kind}")
                    .otherwise()
                        .log(LoggingLevel.WARN, "No user client or initiator found with email from: ${header.From}")
                .end();
    }
}

