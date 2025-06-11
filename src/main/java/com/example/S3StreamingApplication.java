package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.example")
public class S3StreamingApplication {
    public static void main(String[] args) {
        SpringApplication.run(S3StreamingApplication.class, args);
    }
}