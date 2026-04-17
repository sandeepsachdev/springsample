package com.example.pllineup.service;

import com.example.pllineup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Predicts the most likely starting XI using a weighted scoring system
 * that favours experience, prime-age players, and first-team shirt numbers.
 *
 * Scoring per player (higher = more likely to start):
 *   +30  shirt number 1-11  (first-choice squad numbers)
 *   +15  shirt number 12-23 (regular squad)
 *    -5  shirt number 24-30 (fringe)
 *   -30  shirt number >30 or 0 (youth / unregistered)
 *
 *   +25  age 25-30  (peak years)
 *   +15  age 23-24 or 31-32
 *    +5  age 21-22 or 33
 *   -15  age <21   (likely not first choice unless exceptional)
 *   -10  age >34   (rotation / backup)
 *
 *   +10  position exactly matches a formation slot (e.g. Left-Back in a
 *        4-back, Centre-Forward in a 1-striker system)
 *
 * The engine then picks the top-scoring player for each formation slot,
 * ensuring no player is picked twice.
 */
@Service
public class LineupPredictor {

    private static final Logger log = LoggerFactory.getLogger(LineupPredictor.class);

    // Manager formations for 2024-25 season
    private static final Map<String, String> TEAM_FORMATIONS = Map.ofEntries(
            Map.entry("Arsenal FC", "4-3-3"),
            Map.entry("Aston Villa FC", "4-2-3-1"),
            Map.entry("AFC Bournemouth", "4-2-3-1"),
            Map.entry("Brentford FC", "4-3-3"),
            Map.entry("Brighton & Hove Albion FC", "4-2-3-1"),
            Map.entry("Chelsea FC", "4-2-3-1"),
            Map.entry("Crystal Palace FC", "4-3-3"),
            Map.entry("Everton FC", "4-4-2"),
            Map.entry("Fulham FC", "4-2-3-1"),
            Map.entry("Ipswich Town FC", "4-2-3-1"),
            Map.entry("Leicester City FC", "4-2-3-1"),
            Map.entry("Liverpool FC", "4-3-3"),
            Map.entry("Manchester City FC", "4-3-3"),
            Map.entry("Manchester United FC", "4-2-3-1"),
            Map.entry("Newcastle United FC", "4-3-3"),
            Map.entry("Nottingham Forest FC", "4-2-3-1"),
            Map.entry("Southampton FC", "4-3-3"),
            Map.entry("Tottenham Hotspur FC", "4-3-3"),
            Map.entry("West Ham United FC", "4-2-3-1"),
            Map.entry("Wolverhampton Wanderers FC", "3-4-3")
    );

    // Expanded formation slot definitions.
    // Each slot is a positional group (GK/DEF/MID/FWD) plus preferred
    // detailed positions for bonus scoring.
    private record Slot(String group, Set<String> preferred) {}

    private static final Map<String, List<Slot>> FORMATION_SLOTS = new LinkedHashMap<>();
    static {
        FORMATION_SLOTS.put("4-3-3", List.of(
                new Slot("GK",  Set.of("Goalkeeper")),
                new Slot("DEF", Set.of("Right-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Left-Back")),
                new Slot("MID", Set.of("Defensive Midfield", "Central Midfield")),
                new Slot("MID", Set.of("Central Midfield")),
                new Slot("MID", Set.of("Central Midfield", "Attacking Midfield")),
                new Slot("FWD", Set.of("Right Winger", "Left Winger")),
                new Slot("FWD", Set.of("Centre-Forward")),
                new Slot("FWD", Set.of("Left Winger", "Right Winger"))
        ));
        FORMATION_SLOTS.put("4-2-3-1", List.of(
                new Slot("GK",  Set.of("Goalkeeper")),
                new Slot("DEF", Set.of("Right-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Left-Back")),
                new Slot("MID", Set.of("Defensive Midfield", "Central Midfield")),
                new Slot("MID", Set.of("Defensive Midfield", "Central Midfield")),
                new Slot("MID", Set.of("Right Winger", "Right Midfield", "Attacking Midfield")),
                new Slot("MID", Set.of("Attacking Midfield")),
                new Slot("MID", Set.of("Left Winger", "Left Midfield", "Attacking Midfield")),
                new Slot("FWD", Set.of("Centre-Forward"))
        ));
        FORMATION_SLOTS.put("4-4-2", List.of(
                new Slot("GK",  Set.of("Goalkeeper")),
                new Slot("DEF", Set.of("Right-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Left-Back")),
                new Slot("MID", Set.of("Right Midfield", "Right Winger")),
                new Slot("MID", Set.of("Central Midfield", "Defensive Midfield")),
                new Slot("MID", Set.of("Central Midfield")),
                new Slot("MID", Set.of("Left Midfield", "Left Winger")),
                new Slot("FWD", Set.of("Centre-Forward")),
                new Slot("FWD", Set.of("Centre-Forward", "Offence"))
        ));
        FORMATION_SLOTS.put("3-4-3", List.of(
                new Slot("GK",  Set.of("Goalkeeper")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("DEF", Set.of("Centre-Back")),
                new Slot("MID", Set.of("Right-Back", "Right Midfield")),
                new Slot("MID", Set.of("Central Midfield", "Defensive Midfield")),
                new Slot("MID", Set.of("Central Midfield")),
                new Slot("MID", Set.of("Left-Back", "Left Midfield")),
                new Slot("FWD", Set.of("Right Winger")),
                new Slot("FWD", Set.of("Centre-Forward")),
                new Slot("FWD", Set.of("Left Winger"))
        ));
    }

    public MatchPrediction predict(Match match, Team homeTeam, Team awayTeam) {
        PredictedLineup home = predictForTeam(homeTeam);
        PredictedLineup away = predictForTeam(awayTeam);
        ScorePrediction score = predictScore(home, away);
        return new MatchPrediction(
                match.id(), match.utcDate(), match.matchday(),
                home, away, score);
    }

    private PredictedLineup predictForTeam(Team team) {
        String formation = TEAM_FORMATIONS.getOrDefault(team.name(), "4-3-3");
        List<Slot> slots = FORMATION_SLOTS.getOrDefault(formation,
                FORMATION_SLOTS.get("4-3-3"));

        // Filter out players with no position (staff, coaches listed in some squads)
        List<Player> available = team.squad().stream()
                .filter(p -> p.position() != null && !p.positionGroup().equals("Unknown"))
                .toList();

        // Score every player once
        Map<Integer, Integer> scores = new HashMap<>();
        for (Player p : available) {
            scores.put(p.id(), scorePlayer(p));
        }

        // Greedily fill each formation slot with the highest-scoring eligible player
        Set<Integer> picked = new HashSet<>();
        List<Player> startingXI = new ArrayList<>();

        for (Slot slot : slots) {
            Player best = available.stream()
                    .filter(p -> !picked.contains(p.id()))
                    .filter(p -> p.positionGroup().equals(slot.group))
                    .max(Comparator.comparingInt((Player p) -> {
                        int s = scores.get(p.id());
                        // Bonus if the player's detailed position matches this slot
                        if (slot.preferred.contains(p.position())) s += 10;
                        return s;
                    }).thenComparing(Player::name))
                    .orElse(null);

            if (best != null) {
                startingXI.add(best);
                picked.add(best.id());
            }
        }

        // Bench: next-best unpicked players, up to 9, balanced across groups
        List<Player> bench = available.stream()
                .filter(p -> !picked.contains(p.id()))
                .sorted(Comparator.comparingInt((Player p) -> -scores.get(p.id()))
                        .thenComparing(Player::name))
                .limit(9)
                .toList();

        return new PredictedLineup(team.name(), team.shortName(), team.crest(),
                formation, startingXI, bench);
    }

    private int scorePlayer(Player p) {
        int score = 50; // base

        // --- Shirt number ---
        int sn = p.shirtNumber();
        if (sn >= 1 && sn <= 11)       score += 30;
        else if (sn >= 12 && sn <= 23) score += 15;
        else if (sn >= 24 && sn <= 30) score -= 5;
        else                           score -= 30; // 0 or >30 = youth/unregistered

        // --- Age ---
        int age = p.age();
        if (age == 0)                     score += 0;  // unknown DOB, neutral
        else if (age >= 25 && age <= 30)  score += 25; // peak
        else if (age >= 23 && age <= 32)  score += 15; // strong years
        else if (age >= 21 && age <= 33)  score += 5;  // viable
        else if (age < 21)               score -= 15;  // likely not first choice
        else                             score -= 10;  // >34, winding down

        return score;
    }

    private ScorePrediction predictScore(PredictedLineup home, PredictedLineup away) {
        double homeAttack = subgroupStrength(home.startingXI(), Set.of("FWD", "MID"));
        double homeDefense = subgroupStrength(home.startingXI(), Set.of("DEF", "GK"));
        double awayAttack = subgroupStrength(away.startingXI(), Set.of("FWD", "MID"));
        double awayDefense = subgroupStrength(away.startingXI(), Set.of("DEF", "GK"));

        // PL base rates (~1.45 home, ~1.15 away) adjusted by attack vs opponent defense
        double homeXG = 1.45 + (homeAttack - awayDefense) * 0.025;
        double awayXG = 1.15 + (awayAttack - homeDefense) * 0.025;

        homeXG = Math.max(0.3, Math.min(4.0, homeXG));
        awayXG = Math.max(0.2, Math.min(3.5, awayXG));

        int homeGoals = (int) Math.round(homeXG);
        int awayGoals = (int) Math.round(awayXG);

        String outcome;
        if (homeGoals > awayGoals) outcome = "HOME_WIN";
        else if (awayGoals > homeGoals) outcome = "AWAY_WIN";
        else outcome = "DRAW";

        double xgGap = Math.abs(homeXG - awayXG);
        int confidence;
        if (xgGap < 0.15)      confidence = 30;
        else if (xgGap < 0.4)  confidence = 42;
        else if (xgGap < 0.7)  confidence = 55;
        else if (xgGap < 1.0)  confidence = 65;
        else                    confidence = 75;

        return new ScorePrediction(homeGoals, awayGoals, outcome, confidence);
    }

    private double subgroupStrength(List<Player> xi, Set<String> groups) {
        return xi.stream()
                .filter(p -> groups.contains(p.positionGroup()))
                .mapToInt(this::scorePlayer)
                .average()
                .orElse(50);
    }
}
