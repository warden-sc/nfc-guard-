package com.nfcguard.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NfcGuardServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NfcGuardServerApplication.class, args);
    }
}
