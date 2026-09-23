package com.bazlur.orders;

import module java.base;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(proxyBeanMethods = false)
public class OrdersApplication {
    public static void main(String[] args) {
        var options = List.of(args);
        boolean agent = options.contains("--agent");
        boolean mock = options.contains("--mock-agent");
        if (agent && mock) throw new IllegalArgumentException("Choose --agent or --mock-agent");
        var application = new SpringApplication(OrdersApplication.class);
        if (agent || mock) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles(agent ? "agent" : "mock-agent");
        }
        var context = application.run(args);
        if (agent || mock) context.close();
    }
}
