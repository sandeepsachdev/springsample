package com.example.springsample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TripAdvisor Content API restaurant finder.
 *
 * Flow:
 *   1) Geocode the location using OSM Nominatim (free, no key) to get lat/lng.
 *      (The TripAdvisor Content API doesn't offer general-purpose geocoding.)
 *   2) Call TripAdvisor's Nearby Location Search with latLong + category=restaurants
 *      -> https://api.content.tripadvisor.com/api/v1/location/nearby_search
 *   3) For each result, optionally fetch details
 *      -> https://api.content.tripadvisor.com/api/v1/location/{id}/details
 *      to enrich with rating / cuisine / address.
 *
 * Requires a TripAdvisor Content API key. Set it via the
 * TRIPADVISOR_API_KEY environment variable.
 *
 * Note: TripAdvisor's nearby_search returns up to ~10 results and does not
 * accept an arbitrary radius - results are ordered by distance from the
 * supplied latLong. We filter client-side to enforce the 2 km cap.
 *
 * Docs: https://tripadvisor-content-api.readme.io/reference/overview
 */
@Component
public class TripAdvisorRestaurantFinder implements RestaurantFinder {

    private static final String TA_BASE = "https://api.content.tripadvisor.com/api/v1";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "tripadvisor"; }

    @Override
    public void findAndPrint(String location, double radiusKm) throws Exception {
        String apiKey = System.getenv("TRIPADVISOR_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("TRIPADVISOR_API_KEY is not set. "
                    + "Register at https://www.tripadvisor.com/developers to get one.");
            return;
        }

        System.out.println("[provider=tripadvisor] Searching for restaurants within "
                + radiusKm + " km of: " + location);
        System.out.println();

        // --- 1) Geocode via Nominatim (free) ------------------------------
        String geoUrl = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                + URLEncoder.encode(location, StandardCharsets.UTF_8);
        JsonNode geo = mapper.readTree(HttpSupport.get(geoUrl, null));
        if (geo == null || !geo.isArray() || geo.isEmpty()) {
            System.out.println("Could not geocode location: " + location);
            return;
        }
        double lat = geo.get(0).get("lat").asDouble();
        double lon = geo.get(0).get("lon").asDouble();
        System.out.printf("Resolved: %s%n", geo.get(0).get("display_name").asText());
        System.out.printf("Coordinates: lat=%.6f, lon=%.6f%n%n", lat, lon);

        // --- 2) TripAdvisor Nearby Search ---------------------------------
        String nearbyUrl = TA_BASE + "/location/nearby_search"
                + "?latLong=" + lat + "," + lon
                + "&category=restaurants"
                + "&radius=" + radiusKm
                + "&radiusUnit=km"
                + "&language=en"
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        JsonNode nearby = mapper.readTree(HttpSupport.get(nearbyUrl, null));

        record Place(String id, String name, String address, double distanceKm) {}
        List<Place> initial = new ArrayList<>();
        for (JsonNode r : nearby.path("data")) {
            String id = r.path("location_id").asText();
            String name = r.path("name").asText("(unnamed)");
            String addr = r.path("address_obj").path("address_string").asText("");
            // TripAdvisor sometimes returns its own distance (miles); recompute from lat/lng when present.
            double pLat = r.path("latitude").asDouble(Double.NaN);
            double pLon = r.path("longitude").asDouble(Double.NaN);
            double dist = (!Double.isNaN(pLat) && !Double.isNaN(pLon))
                    ? HttpSupport.haversineKm(lat, lon, pLat, pLon)
                    : Double.NaN;
            if (!Double.isNaN(dist) && dist > radiusKm) continue;   // enforce radius
            initial.add(new Place(id, name, addr, Double.isNaN(dist) ? 0.0 : dist));
        }
        initial.sort(Comparator.comparingDouble(Place::distanceKm));

        if (initial.isEmpty()) { System.out.println("No restaurants found."); return; }

        // --- 3) Enrich each hit with /details for rating + cuisine --------
        System.out.println("Found " + initial.size() + " restaurant(s):");
        System.out.println("---------------------------------------------------------------");
        int i = 1;
        for (Place p : initial) {
            String detailsUrl = TA_BASE + "/location/" + p.id() + "/details"
                    + "?language=en&currency=USD"
                    + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            String rating = "";
            String cuisine = "";
            String priceLevel = "";
            try {
                JsonNode d = mapper.readTree(HttpSupport.get(detailsUrl, null));
                rating = d.path("rating").asText("");
                priceLevel = d.path("price_level").asText("");
                StringBuilder cs = new StringBuilder();
                for (JsonNode c : d.path("cuisine")) {
                    if (cs.length() > 0) cs.append(", ");
                    cs.append(c.path("localized_name").asText(c.path("name").asText("")));
                }
                cuisine = cs.toString();
            } catch (Exception e) {
                // Details call failed for this one; print what we have.
            }

            System.out.printf("%2d. %s%n", i++, p.name());
            if (!cuisine.isEmpty())    System.out.println("    Cuisine : " + cuisine);
            if (!p.address().isEmpty())System.out.println("    Address : " + p.address());
            if (!rating.isEmpty())     System.out.println("    Rating  : " + rating + "/5");
            if (!priceLevel.isEmpty()) System.out.println("    Price   : " + priceLevel);
            System.out.printf("    Distance: %.2f km%n%n", p.distanceKm());
        }
    }
}
