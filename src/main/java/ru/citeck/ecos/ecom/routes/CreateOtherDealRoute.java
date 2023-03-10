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
    private CreateDealProcessor createDealProcessor;

    @Override
    public void configure() {
        //recordsDaoEndpoint.setAppName("emodel");
        //recordsDaoEndpoint.setSourceId("deal");
        Map<String, String> map = new HashMap<String, String>();
        map.put("fromAddress", "siteEmail");
        map.put("from", "fio");
        map.put("email", "email");
        map.put("company", "company");
        map.put("phone", "phone");
        map.put("dateReceived", "dateReceived");
        map.put("content", "description");
        map.put("status", "_status");
        map.put("source", "source");
        map.put("emessage", "emessage");
        map.put("gaClientId", "ga_client_id");
        map.put("ymClientId", "ym_client_id");
        map.put("createdAutomatically", "createdAutomatically");
        //recordsDaoEndpoint.setColumnMap(map);
        from("direct:createOtherDeal")
                .setHeader("recordsDaoColumnMap", constant(map))
                .process(createDealProcessor)
                .bean(RecordsDaoEndpoint.class, "mutate(*, emodel, deal)");
    }
}

