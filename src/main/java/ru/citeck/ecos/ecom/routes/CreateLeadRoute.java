package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.processor.CreateLeadProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint;

import java.util.HashMap;
import java.util.Map;

@Component
public class CreateLeadRoute extends RouteBuilder {

    @Autowired
    private CreateLeadProcessor createLeadProcessor;

    @Override
    public void configure() {
        Map<String, String> map = new HashMap<>();

        map.put("fromAddress", "siteEmail");
        map.put("name", "name");
        map.put("company", "company");
        map.put("counterparty", "counterparty");
        map.put("dateReceived", "dateReceived");
        map.put("description", "description");
        map.put("status", "_status");
        map.put("workspace", "_workspace");
        map.put("requestCategory", "requestCategory");
        map.put("requestSource", "requestSource");
        map.put("siteFrom", "siteFrom");
        map.put("numberOfUsers", "numberOfUsers");
        map.put("emessage", "emessage");
        map.put("gaClientId", "ga_client_id");
        map.put("ymClientId", "ym_client_id");
        map.put("createdAutomatically", "createdAutomatically");
        map.put("contacts", "contacts");

        from("direct:createLead")
                .setHeader("recordsDaoColumnMap", constant(map))
                .process(createLeadProcessor)
                .bean(RecordsDaoEndpoint.class, "mutate(*, emodel, lead)");
    }
}

