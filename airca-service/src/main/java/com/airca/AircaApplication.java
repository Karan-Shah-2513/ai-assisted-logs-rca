package com.airca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AircaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AircaApplication.class, args);
    }
}
