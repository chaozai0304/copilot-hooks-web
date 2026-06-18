package com.copilot.hooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HooksApplication {
    public static void main(String[] args) {
        SpringApplication.run(HooksApplication.class, args);
    }
}
