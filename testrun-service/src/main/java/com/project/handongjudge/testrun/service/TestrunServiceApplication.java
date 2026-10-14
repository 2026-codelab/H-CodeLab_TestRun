package com.project.handongjudge.testrun.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TestrunServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestrunServiceApplication.class, args);
    }
}
