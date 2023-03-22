package ru.citeck.ecos.ecom.routes;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CreateSDRoute extends RouteBuilder {

    public static final String ID = "createSD";

    @Override
    public void configure() {
        Map<String, String> map = new HashMap<>();
        map.put("subject", "letterTopic");
        map.put("from", "author");
        map.put("date", "dateReceived");
        map.put("content", "letterContent");
        map.put("client", "client");
        map.put("initiator", "initiator");
        map.put("createdAutomatically", "createdAutomatically");
        map.put("priority", "priority");

        from("direct:" + ID)
                .setHeader("recordsDaoColumnMap", constant(map))
                .bean(RecordsDaoEndpoint.class, "mutate(*, emodel, sd-request-type)");
    }
}

