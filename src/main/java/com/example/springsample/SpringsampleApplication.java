package com.example.springsample;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootApplication
public class SpringsampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringsampleApplication.class, args);
    }

    /**
     * Usage:
     *   java -jar springsample.jar [--provider=osm|google|tripadvisor] [location words...]
     *
     * Defaults: provider=osm, location="Cherrybrook, NSW, Australia", radius=2 km.
     */
    @Bean
    CommandLineRunner runner(List<RestaurantFinder> finders) {
        Map<String, RestaurantFinder> byName = finders.stream()
                .collect(Collectors.toMap(RestaurantFinder::name, f -> f));

        return args -> {
            String provider = "osm";
            List<String> positional = new ArrayList<>();
            for (String a : args) {
                if (a.startsWith("--provider=")) provider = a.substring("--provider=".length());
                else positional.add(a);
            }
            String location = positional.isEmpty()
                    ? "Cherrybrook, NSW, Australia"
                    : String.join(" ", positional);
            double radiusKm = 2.0;

            RestaurantFinder finder = byName.get(provider);
            if (finder == null) {
                System.err.println("Unknown provider '" + provider + "'. "
                        + "Available: " + byName.keySet());
                System.exit(2);
                return;
            }
            finder.findAndPrint(location, radiusKm);
        };
    }
}
