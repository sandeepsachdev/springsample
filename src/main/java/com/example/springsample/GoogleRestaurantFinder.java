package com.example.springsample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Places API (New) restaurant finder.
 *
 * Uses two endpoints:
 *   1) Geocoding API    -> https://maps.googleapis.com/maps/api/geocode/json
 *      to resolve the free-form location to lat/lng.
 *   2) Places API (New) -> https://places.googleapis.com/v1/places:searchNearby
 *      to list restaurants within radiusKm of that lat/lng.
 *
 * Requires a Google Cloud API key with the Geocoding API and Places API (New)
 * enabled. Supply it via the GOOGLE_MAPS_API_KEY environment variable.
 *
 * Docs:
 *   https://developers.google.com/maps/documentation/geocoding/overview
 *   https://developers.google.com/maps/documentation/places/web-service/nearby-search
 */
@Component
public class GoogleRestaurantFinder implements RestaurantFinder {

    private static final String GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json";
    private static final String NEARBY_URL  = "https://places.googleapis.com/v1/places:searchNearby";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "google"; }

    @Override
    public void findAndPrint(String location, double radiusKm) throws Exception {
        String apiKey = System.getenv("GOOGLE_MAPS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("GOOGLE_MAPS_API_KEY is not set. "
                    + "Get one from https://console.cloud.google.com/ and enable "
                    + "the 'Geocoding API' and 'Places API (New)'.");
            return;
        }

        System.out.println("[provider=google] Searching for restaurants within "
                + radiusKm + " km of: " + location);
        System.out.println();

        // --- 1) Geocode ---------------------------------------------------
        String geoUrl = GEOCODE_URL
                + "?address=" + java.net.URLEncoder.encode(location, java.nio.charset.StandardCharsets.UTF_8)
                + "&key=" + apiKey;
        JsonNode geo = mapper.readTree(HttpSupport.get(geoUrl, null));
        if (!"OK".equals(geo.path("status").asText())) {
            System.err.println("Geocoding failed: " + geo.path("status").asText()
                    + " - " + geo.path("error_message").asText());
            return;
        }
        JsonNode loc = geo.path("results").get(0).path("geometry").path("location");
        double lat = loc.path("lat").asDouble();
        double lng = loc.path("lng").asDouble();
        System.out.printf("Resolved: %s%n",
                geo.path("results").get(0).path("formatted_address").asText());
        System.out.printf("Coordinates: lat=%.6f, lng=%.6f%n%n", lat, lng);

        // --- 2) Nearby Search (Places API New) ----------------------------
        // POST body is JSON; API key travels in the X-Goog-Api-Key header.
        // FieldMask limits the response to just what we need (and what we pay for).
        String requestBody = """
                {
                  "includedTypes": ["restaurant"],
                  "maxResultCount": 20,
                  "locationRestriction": {
                    "circle": {
                      "center": {"latitude": %f, "longitude": %f},
                      "radius": %f
                    }
                  }
                }
                """.formatted(lat, lng, radiusKm * 1000.0);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Goog-Api-Key", apiKey);
        headers.put("X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.location,"
                + "places.rating,places.userRatingCount,places.primaryType");

        JsonNode resp = mapper.readTree(postJson(NEARBY_URL, requestBody, headers));

        record Place(String name, String type, String address,
                     double rating, int reviews, double distanceKm) {}
        List<Place> places = new ArrayList<>();
        for (JsonNode p : resp.path("places")) {
            double pLat = p.path("location").path("latitude").asDouble();
            double pLng = p.path("location").path("longitude").asDouble();
            places.add(new Place(
                    p.path("displayName").path("text").asText("(unnamed)"),
                    p.path("primaryType").asText(""),
                    p.path("formattedAddress").asText(""),
                    p.path("rating").asDouble(0.0),
                    p.path("userRatingCount").asInt(0),
                    HttpSupport.haversineKm(lat, lng, pLat, pLng)));
        }
        places.sort(Comparator.comparingDouble(Place::distanceKm));

        if (places.isEmpty()) { System.out.println("No restaurants found."); return; }
        System.out.println("Found " + places.size() + " restaurant(s):");
        System.out.println("---------------------------------------------------------------");
        int i = 1;
        for (Place p : places) {
            System.out.printf("%2d. %s%n", i++, p.name());
            if (!p.type().isEmpty())    System.out.println("    Type    : " + p.type());
            if (!p.address().isEmpty()) System.out.println("    Address : " + p.address());
            if (p.rating() > 0)         System.out.printf("    Rating  : %.1f (%d reviews)%n",
                    p.rating(), p.reviews());
            System.out.printf("    Distance: %.2f km%n%n", p.distanceKm());
        }
    }

    /** Small POST helper that sends a JSON body plus custom headers. */
    private static String postJson(String url, String body, Map<String, String> headers) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("User-Agent", HttpSupport.USER_AGENT)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        for (Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
        java.net.http.HttpResponse<String> resp = HttpSupport.CLIENT.send(
                b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }
}
