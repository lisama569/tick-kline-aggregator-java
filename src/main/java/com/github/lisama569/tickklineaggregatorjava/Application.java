package com.github.lisama569.tickklineaggregatorjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        com.github.lisama569.tickklineaggregatorjava.service.AggregatorProperties.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}