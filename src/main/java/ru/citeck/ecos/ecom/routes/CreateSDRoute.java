package ru.citeck.ecos.ecom.routes;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CreateSDRoute extends RouteBuilder {

    static final String ROUTE_ID = "createSDRoute";

    @Autowired
    private RecordsDaoEndpoint recordsDaoEndpoint;

    @Override
    public void configure() {
        recordsDaoEndpoint.setAppName("emodel");
        recordsDaoEndpoint.setSourceId("sd-request-type");
        Map<String, String> map = new HashMap<String, String>();
        map.put("subject", "letterTopic");
        //map.put("fromAddress", "email");
        map.put("from", "author");
        map.put("date", "dateReceived");
        map.put("content", "letterContent");
        map.put("client", "client");
        map.put("initiator", "initiator");
        map.put("createdAutomatically", "createdAutomatically");
        map.put("priority", "priority");
        //map.put("status", "_status");
        from("direct:createSD")
                .setHeader("recordsDaoColumnMap", constant(map))
                .to("log:INFO?showHeaders=true")
                .bean("recordsDaoEndpoint");
    }
}

