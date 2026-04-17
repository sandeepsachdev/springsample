package com.example.pllineup.service;

import com.example.pllineup.model.Match;
import com.example.pllineup.model.Player;
import com.example.pllineup.model.Team;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FootballDataService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataService.class);
    private static final int PL_ID = 2021; // Premier League competition ID

    @Value("${football-data.api-key:}")
    private String apiKey;

    @Value("${football-data.base-url}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Simple cache: key -> (data, fetchTimeMs)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 min

    private record CacheEntry(String json, long ts) {}

    public List<Match> getUpcomingMatches() throws Exception {
        String json = cachedGet("/competitions/" + PL_ID + "/matches?status=SCHEDULED");
        JsonNode root = mapper.readTree(json);
        List<Match> matches = new ArrayList<>();
        for (JsonNode m : root.path("matches")) {
            matches.add(parseMatch(m));
        }
        matches.sort(Comparator.comparing(Match::utcDate));
        return matches;
    }

    public Team getTeam(int teamId) throws Exception {
        String json = cachedGet("/teams/" + teamId);
        JsonNode root = mapper.readTree(json);
        String name = root.path("name").asText();
        String shortName = root.path("shortName").asText(name);
        String crest = root.path("crest").asText("");
        String formation = root.path("formation").asText("4-3-3");

        List<Player> squad = new ArrayList<>();
        for (JsonNode p : root.path("squad")) {
            String pos = p.path("position").asText(null);
            String dob = p.path("dateOfBirth").asText(null);
            LocalDate birthDate = (dob != null && !dob.isBlank()) ? LocalDate.parse(dob) : null;
            squad.add(new Player(
                    p.path("id").asInt(),
                    p.path("name").asText(),
                    pos,
                    p.path("shirtNumber").asInt(0),
                    birthDate
            ));
        }
        return new Team(teamId, name, shortName, crest, formation, squad);
    }

    public List<Match> getRecentFinishedMatches(int teamId, int limit) throws Exception {
        String json = cachedGet("/teams/" + teamId + "/matches?status=FINISHED&limit=" + limit);
        JsonNode root = mapper.readTree(json);
        List<Match> matches = new ArrayList<>();
        for (JsonNode m : root.path("matches")) {
            matches.add(parseMatch(m));
        }
        matches.sort(Comparator.comparing(Match::utcDate).reversed());
        return matches;
    }

    private Match parseMatch(JsonNode m) {
        return new Match(
                m.path("id").asInt(),
                ZonedDateTime.parse(m.path("utcDate").asText()),
                m.path("matchday").asInt(),
                m.path("status").asText(),
                new Match.TeamRef(
                        m.path("homeTeam").path("id").asInt(),
                        m.path("homeTeam").path("name").asText(),
                        m.path("homeTeam").path("shortName").asText(""),
                        m.path("homeTeam").path("crest").asText("")),
                new Match.TeamRef(
                        m.path("awayTeam").path("id").asInt(),
                        m.path("awayTeam").path("name").asText(),
                        m.path("awayTeam").path("shortName").asText(""),
                        m.path("awayTeam").path("crest").asText(""))
        );
    }

    private String cachedGet(String path) throws Exception {
        CacheEntry cached = cache.get(path);
        if (cached != null && System.currentTimeMillis() - cached.ts < CACHE_TTL_MS) {
            return cached.json;
        }
        String url = baseUrl + path;
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("X-Auth-Token", apiKey);
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            log.warn("Rate limited by football-data.org — waiting 60s");
            Thread.sleep(60_000);
            return cachedGet(path);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("football-data.org " + resp.statusCode() + ": " + resp.body());
        }
        cache.put(path, new CacheEntry(resp.body(), System.currentTimeMillis()));
        return resp.body();
    }
}
