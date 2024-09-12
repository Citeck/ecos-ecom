package ru.citeck.ecos.ecom.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.processor.AddEmailActivityProcessor;

@Component
public class AddEmailActivityRoute extends RouteBuilder {

    @Autowired
    private AddEmailActivityProcessor addEmailActivityProcessor;

    @Override
    public void configure() {
        from("direct:addMailActivity")
            .process(addEmailActivityProcessor);
    }
}
