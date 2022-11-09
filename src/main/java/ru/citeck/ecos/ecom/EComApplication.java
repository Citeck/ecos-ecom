package ru.citeck.ecos.ecom;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import ru.citeck.ecos.webapp.lib.spring.EcosSpringApplication;

@SpringBootApplication
@EnableDiscoveryClient
public class EComApplication {

    public static final String NAME = "ecom";

    public static void main(String[] args) {
        new EcosSpringApplication(EComApplication.class).run(args);
    }
}