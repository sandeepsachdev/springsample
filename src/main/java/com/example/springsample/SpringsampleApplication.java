package com.example.springsample;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringsampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringsampleApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(RestaurantFinder finder) {
        return args -> {
            String location = (args.length > 0) ? String.join(" ", args) : "Erin, Ontario, Canada";
            double radiusKm = 2.0;
            finder.findAndPrint(location, radiusKm);
        };
    }
}
