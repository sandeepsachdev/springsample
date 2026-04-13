package com.example.springsample;

/**
 * Common contract for a provider that finds restaurants near a given
 * free-form location string and prints them to stdout.
 */
public interface RestaurantFinder {
    /** A short provider id, e.g. "osm", "google", "tripadvisor". */
    String name();

    /** Look up restaurants within radiusKm of the given location and print them. */
    void findAndPrint(String location, double radiusKm) throws Exception;
}
