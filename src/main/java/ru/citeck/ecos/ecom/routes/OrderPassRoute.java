package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.OrderPassProcessor;

import java.util.Objects;

@Component
public class OrderPassRoute extends RouteBuilder {

    static final String ROUTE_ID = "orderPassRoute";

    @EcosConfig("telegram-oap-authtoken")
    private String telegramAuthorizationToken;

    @Autowired
    private OrderPassProcessor orderPassProcessor;

    @Override
    public void configure() {
        from("telegram:bots?authorizationToken="+telegramAuthorizationToken)
                .autoStartup(!Objects.equals(telegramAuthorizationToken, "disabled"))
                .to("log:INFO?showHeaders=true")
                .process(orderPassProcessor)
                .to("telegram:bots?authorizationToken="+telegramAuthorizationToken);
    }
}

