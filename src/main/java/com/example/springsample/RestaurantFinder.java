package com.example.springsample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RestaurantFinder {

    // Per Nominatim usage policy, identify the app via User-Agent.
    private static final String USER_AGENT = "springsample-restaurant-finder/0.1 (demo)";

    private final HttpClient http = buildHttpClient();

    private static HttpClient buildHttpClient() {
        // Allow Basic auth on HTTPS proxy CONNECT tunnels (often required in sandboxes)
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }

        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));

        String proxyUrl = firstNonBlank(System.getenv("https_proxy"),
                                        System.getenv("HTTPS_PROXY"),
                                        System.getenv("http_proxy"),
                                        System.getenv("HTTP_PROXY"));
        if (proxyUrl != null) {
            try {
                URI u = URI.create(proxyUrl);
                b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort())));
                if (u.getUserInfo() != null) {
                    int colon = u.getUserInfo().indexOf(':');
                    String user = URLDecoder.decode(u.getUserInfo().substring(0, colon), StandardCharsets.UTF_8);
                    String pass = URLDecoder.decode(u.getUserInfo().substring(colon + 1), StandardCharsets.UTF_8);
                    b.authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(user, pass.toCharArray());
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }
        return b.build();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public void findAndPrint(String location, double radiusKm) throws Exception {
        System.out.println("Searching for restaurants within " + radiusKm + " km of: " + location);
        System.out.println();

        // 1) Geocode the location with Nominatim
        String geoUrl = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                + URLEncoder.encode(location, StandardCharsets.UTF_8);
        String geoJson = get(geoUrl);

        JsonNode geo = mapper.readTree(geoJson);
        if (geo == null || !geo.isArray() || geo.isEmpty()) {
            System.out.println("Could not geocode location: " + location);
            return;
        }
        double lat = geo.get(0).get("lat").asDouble();
        double lon = geo.get(0).get("lon").asDouble();
        String displayName = geo.get(0).get("display_name").asText();
        System.out.printf("Resolved: %s%n", displayName);
        System.out.printf("Coordinates: lat=%.6f, lon=%.6f%n%n", lat, lon);

        // 2) Query Overpass for restaurants within radius
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
        String overpassJson = post("https://overpass-api.de/api/interpreter", body);

        JsonNode resp = mapper.readTree(overpassJson);
        JsonNode elements = resp.path("elements");

        record Place(String name, String cuisine, String address, double distanceKm) {}
        List<Place> places = new ArrayList<>();
        for (JsonNode el : elements) {
            JsonNode tags = el.path("tags");
            String name = tags.path("name").asText("(unnamed)");
            String cuisine = tags.path("cuisine").asText("");

            double pLat, pLon;
            if (el.has("lat") && el.has("lon")) {
                pLat = el.get("lat").asDouble();
                pLon = el.get("lon").asDouble();
            } else if (el.has("center")) {
                pLat = el.get("center").get("lat").asDouble();
                pLon = el.get("center").get("lon").asDouble();
            } else {
                continue;
            }

            String street = tags.path("addr:street").asText("");
            String housenumber = tags.path("addr:housenumber").asText("");
            String city = tags.path("addr:city").asText("");
            String address = (housenumber + " " + street).trim();
            if (!city.isEmpty()) {
                address = address.isEmpty() ? city : address + ", " + city;
            }

            double dist = haversineKm(lat, lon, pLat, pLon);
            places.add(new Place(name, cuisine, address, dist));
        }

        places.sort(Comparator.comparingDouble(Place::distanceKm));

        if (places.isEmpty()) {
            System.out.println("No restaurants found within " + radiusKm + " km.");
            return;
        }

        System.out.println("Found " + places.size() + " restaurant(s):");
        System.out.println("---------------------------------------------------------------");
        int i = 1;
        for (Place p : places) {
            System.out.printf("%2d. %s%n", i++, p.name());
            if (!p.cuisine().isEmpty()) {
                System.out.println("    Cuisine : " + p.cuisine());
            }
            if (!p.address().isEmpty()) {
                System.out.println("    Address : " + p.address());
            }
            System.out.printf("    Distance: %.2f km%n", p.distanceKm());
            System.out.println();
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }

    private String post(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
