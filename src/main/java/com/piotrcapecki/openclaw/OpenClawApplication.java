package com.piotrcapecki.openclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OpenClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenClawApplication.class, args);
    }
}
