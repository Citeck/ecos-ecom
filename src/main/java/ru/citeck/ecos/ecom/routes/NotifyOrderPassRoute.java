package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;

@Component
public class NotifyOrderPassRoute extends RouteBuilder {

    static final String ROUTE_ID = "notifyOrderPassRoute";

    @EcosConfig("telegram-authtoken")
    private String telegramAuthorizationToken;

    @Override
    public void configure() {
        from("direct:notifyUser")
                .to("log:INFO?showHeaders=true")
                .to("telegram:bots?authorizationToken="+telegramAuthorizationToken);
    }
}

