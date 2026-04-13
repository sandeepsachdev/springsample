package com.example.springsample;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP helpers. All providers go through this so that a single
 * proxy/authenticator configuration applies everywhere.
 */
public final class HttpSupport {

    public static final String USER_AGENT = "springsample-restaurant-finder/0.2";

    public static final HttpClient CLIENT = build();

    private HttpSupport() {}

    private static HttpClient build() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));

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
            } catch (Exception ignored) { }
        }
        return b.build();
    }

    public static String get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }

    public static String post(String url, String contentType, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
