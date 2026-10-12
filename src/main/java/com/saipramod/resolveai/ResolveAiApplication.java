package com.saipramod.resolveai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResolveAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResolveAiApplication.class, args);
    }
}
