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
 * OpenStreetMap-based restaurant finder. Uses:
 *   - Nominatim for forward geocoding the location string
 *   - Overpass API for amenity=restaurant within a radius
 *
 * Both services are free and do not require an API key (Nominatim only
 * asks for a descriptive User-Agent).
 */
@Component
public class OsmRestaurantFinder implements RestaurantFinder {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "osm"; }

    @Override
    public void findAndPrint(String location, double radiusKm) throws Exception {
        System.out.println("[provider=osm] Searching for restaurants within "
                + radiusKm + " km of: " + location);
        System.out.println();

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

        int radiusMeters = (int) Math.round(radiusKm * 1000);
        String query = String.format(
                "[out:json][timeout:25];" +
                "(node[\"amenity\"=\"restaurant\"](around:%d,%f,%f);" +
                " way[\"amenity\"=\"restaurant\"](around:%d,%f,%f);" +
                " relation[\"amenity\"=\"restaurant\"](around:%d,%f,%f);" +
                ");out center tags;",
                radiusMeters, lat, lon,
                radiusMeters, lat, lon,
                radiusMeters, lat, lon);
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode resp = mapper.readTree(HttpSupport.post(
                "https://overpass-api.de/api/interpreter",
                "application/x-www-form-urlencoded", body));

        record Place(String name, String cuisine, String address, double distanceKm) {}
        List<Place> places = new ArrayList<>();
        for (JsonNode el : resp.path("elements")) {
            JsonNode tags = el.path("tags");
            String name = tags.path("name").asText("(unnamed)");
            String cuisine = tags.path("cuisine").asText("");
            double pLat, pLon;
            if (el.has("lat") && el.has("lon")) {
                pLat = el.get("lat").asDouble(); pLon = el.get("lon").asDouble();
            } else if (el.has("center")) {
                pLat = el.get("center").get("lat").asDouble(); pLon = el.get("center").get("lon").asDouble();
            } else continue;

            String street = tags.path("addr:street").asText("");
            String hn = tags.path("addr:housenumber").asText("");
            String city = tags.path("addr:city").asText("");
            String address = (hn + " " + street).trim();
            if (!city.isEmpty()) address = address.isEmpty() ? city : address + ", " + city;

            places.add(new Place(name, cuisine, address,
                    HttpSupport.haversineKm(lat, lon, pLat, pLon)));
        }
        places.sort(Comparator.comparingDouble(Place::distanceKm));

        if (places.isEmpty()) { System.out.println("No restaurants found."); return; }
        System.out.println("Found " + places.size() + " restaurant(s):");
        System.out.println("---------------------------------------------------------------");
        int i = 1;
        for (Place p : places) {
            System.out.printf("%2d. %s%n", i++, p.name());
            if (!p.cuisine().isEmpty()) System.out.println("    Cuisine : " + p.cuisine());
            if (!p.address().isEmpty()) System.out.println("    Address : " + p.address());
            System.out.printf("    Distance: %.2f km%n%n", p.distanceKm());
        }
    }
}
