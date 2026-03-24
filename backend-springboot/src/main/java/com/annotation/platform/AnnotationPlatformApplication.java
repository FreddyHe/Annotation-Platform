package com.annotation.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AnnotationPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnotationPlatformApplication.class, args);
    }
}
