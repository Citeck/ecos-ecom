package ru.citeck.ecos.ecom.routes;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint;

import java.util.HashMap;
import java.util.Map;

@Component
public class CreateCommentRoute extends RouteBuilder {

    public static final String ID = "createComment";

    @Override
    public void configure() {
        Map<String, String> map = new HashMap<>();
        map.put("record", "record");
        map.put("content", "text");

        from("direct:" + ID)
                .choice()
                    .when(
                            PredicateBuilder.and(
                                    simple("${body.get('record')} != null"),
                                    simple("${body.get('content')} != null")
                            )
                    )
                        .setHeader("recordsDaoColumnMap", constant(map))
                        .bean(RecordsDaoEndpoint.class,
                                "mutate(*, emodel, comment, ${header.runAsUser})"
                        )
                    .otherwise()
                        .log(LoggingLevel.WARN, "No record or content found in message: ${body.get('subject')}");
    }
}

