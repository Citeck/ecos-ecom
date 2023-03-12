package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.ReadMailboxSmContractProcessor;
import ru.citeck.ecos.ecom.processor.SmContractTaskResponseProcessor;

import java.util.Objects;

@Component
public class ReadMailboxSmContractRoute extends RouteBuilder {

    @EcosConfig("mail-inbox-sm-contract")
    private String imap;

    @Autowired
    private ReadMailboxSmContractProcessor readMailboxSmContractProcessor;

    @Autowired
    private SmContractTaskResponseProcessor smContractTaskResponseProcessor;

    @Override
    public void configure() {
        String endPoint = StringUtils.startsWithAny(imap, "imap://", "imaps://") ? imap : "imap://" + imap;

        from(endPoint)
                .autoStartup(!Objects.equals(imap, "disabled"))
                .tracing()
                .process(readMailboxSmContractProcessor)
                .choice()
                    .when(header("taskResolution").isNotNull())
                        .log("ReadMailboxSmContractRoute: ${body}")
                        .process(smContractTaskResponseProcessor)
                    .otherwise()
                        .log("Its not a task response. Skipping...")
                .endChoice()
                .end();
    }
}

