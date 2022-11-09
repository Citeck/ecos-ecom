package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.processor.CreateDealProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint;

import java.util.HashMap;
import java.util.Map;

@Component
public class CreateOtherDealRoute extends RouteBuilder {

    static final String ROUTE_ID = "createOtherDealRoute";

    @Autowired
    private RecordsDaoEndpoint recordsDaoEndpoint;

    @Override
    public void configure() {
        recordsDaoEndpoint.setAppName("emodel");
        recordsDaoEndpoint.setSourceId("deal");
        Map<String, String> map = new HashMap<String, String>();
        map.put("fromAddress", "email");
        map.put("from", "fio");
        map.put("date", "dateReceived");
        map.put("content", "description");
        map.put("status", "_status");
        map.put("kind", "source");
        //recordsDaoEndpoint.setColumnMap(map);
        from("direct:createOtherDeal")
                .setHeader("recordsDaoColumnMap", constant(map))
                .to("log:INFO?showHeaders=true")
                .bean("recordsDaoEndpoint");
    }
}

