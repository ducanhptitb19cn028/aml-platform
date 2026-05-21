package com.alexbank.aiops.remediation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RemediationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RemediationEngineApplication.class, args);
    }
}
