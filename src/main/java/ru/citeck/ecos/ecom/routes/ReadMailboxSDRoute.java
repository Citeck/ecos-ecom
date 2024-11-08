package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor;
import ru.citeck.ecos.ecom.processor.sd.SdEcomMailProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor;

import java.util.Objects;

@Component
public class ReadMailboxSDRoute extends RouteBuilder {

    @EcosConfig("mail-inbox-sd")
    private String imap;

    private SdEcomMailProcessor sdEcomMailProcessor;

    @Override
    public void configure() {

        from(EcomCamelMailUtils.fromMailUri(this, imap))
                .autoStartup(!Objects.equals(imap, "disabled"))
                .to("log:raw-email?level=INFO&showHeaders=true")
                .bean(MailBodyExtractor.class, "extract(*)")
                .process(new EcomMailReaderProcessor())
                .to("log:parsed-email?level=DEBUG")
                .process(sdEcomMailProcessor);
    }

    @Autowired
    public void setSdEcomMailProcessor(SdEcomMailProcessor sdEcomMailProcessor) {
        this.sdEcomMailProcessor = sdEcomMailProcessor;
    }
}
