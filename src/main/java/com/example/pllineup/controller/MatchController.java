package com.example.pllineup.controller;

import com.example.pllineup.model.*;
import com.example.pllineup.service.FootballDataService;
import com.example.pllineup.service.LineupPredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class MatchController {

    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final FootballDataService footballData;
    private final LineupPredictor predictor;

    public MatchController(FootballDataService footballData, LineupPredictor predictor) {
        this.footballData = footballData;
        this.predictor = predictor;
    }

    @GetMapping("/")
    public String index(Model model) {
        try {
            List<Match> upcoming = footballData.getUpcomingMatches();
            // Group matches by matchday
            Map<Integer, List<Match>> byMatchday = new LinkedHashMap<>();
            for (Match m : upcoming) {
                byMatchday.computeIfAbsent(m.matchday(), k -> new ArrayList<>()).add(m);
            }
            model.addAttribute("matchdays", byMatchday);
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Failed to fetch matches", e);
            model.addAttribute("matchdays", Map.of());
            model.addAttribute("error", e.getMessage());
        }
        return "index";
    }

    @GetMapping("/predict/{matchId}")
    public String predictMatch(@PathVariable int matchId, Model model) {
        try {
            List<Match> upcoming = footballData.getUpcomingMatches();
            Match match = upcoming.stream()
                    .filter(m -> m.id() == matchId)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Match " + matchId + " not found"));

            Team home = footballData.getTeam(match.homeTeam().id());
            Team away = footballData.getTeam(match.awayTeam().id());

            MatchPrediction prediction = predictor.predict(match, home, away);
            model.addAttribute("prediction", prediction);
            model.addAttribute("match", match);
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Failed to predict lineup for match {}", matchId, e);
            model.addAttribute("prediction", null);
            model.addAttribute("match", null);
            model.addAttribute("error", e.getMessage());
        }
        return "prediction";
    }

    // --- JSON API endpoints -----------------------------------------------

    @GetMapping("/api/matches")
    @ResponseBody
    public ResponseEntity<?> apiMatches() {
        try {
            return ResponseEntity.ok(footballData.getUpcomingMatches());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/predict/{matchId}")
    @ResponseBody
    public ResponseEntity<?> apiPredict(@PathVariable int matchId) {
        try {
            List<Match> upcoming = footballData.getUpcomingMatches();
            Match match = upcoming.stream()
                    .filter(m -> m.id() == matchId)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Match not found"));

            Team home = footballData.getTeam(match.homeTeam().id());
            Team away = footballData.getTeam(match.awayTeam().id());
            return ResponseEntity.ok(predictor.predict(match, home, away));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
