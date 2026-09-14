package com.github.comui520.learnhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LearnHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(LearnHubApplication.class, args);
    }
}