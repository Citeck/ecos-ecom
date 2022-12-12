package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;

import java.util.Objects;

@Component
public class NotifyOrderPassRoute extends RouteBuilder {

    static final String ROUTE_ID = "notifyOrderPassRoute";

    @EcosConfig("telegram-oap-authtoken")
    private String telegramAuthorizationToken;

    @Override
    public void configure() {
        from("direct:notifyUser")
                .autoStartup(!Objects.equals(telegramAuthorizationToken, "disabled"))
                .to("log:INFO?showHeaders=true")
                .to("telegram:bots?authorizationToken="+telegramAuthorizationToken);
    }
}

