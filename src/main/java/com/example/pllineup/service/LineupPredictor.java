package com.example.pllineup.service;

import com.example.pllineup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Predicts the most likely starting XI for a team based on squad composition
 * and the manager's typical formation.
 *
 * Heuristic approach (no ML):
 *   - Map each player to a positional group (GK / DEF / MID / FWD).
 *   - Apply the team's preferred formation to determine how many slots
 *     exist per group.
 *   - Rank players within each group by shirt-number seniority (lower
 *     numbers typically indicate first-choice starters) and alphabetical
 *     name as a tie-breaker.
 *   - Pick the top N from each group. The remainder form the bench (up
 *     to 9 subs).
 */
@Service
public class LineupPredictor {

    private static final Logger log = LoggerFactory.getLogger(LineupPredictor.class);

    // Well-known manager formations per team (2024-25 season tendencies).
    // Falls back to 4-3-3 if unknown.
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

    public MatchPrediction predict(Match match, Team homeTeam, Team awayTeam) {
        PredictedLineup home = predictForTeam(homeTeam);
        PredictedLineup away = predictForTeam(awayTeam);
        return new MatchPrediction(match.id(), match.utcDate(), match.matchday(), home, away);
    }

    private PredictedLineup predictForTeam(Team team) {
        String formation = TEAM_FORMATIONS.getOrDefault(team.name(), "4-3-3");
        int[] slots = parseFormation(formation);
        // slots = [DEF, MID, FWD]  — GK is always 1.

        Map<String, List<Player>> byGroup = team.squad().stream()
                .filter(p -> p.position() != null)
                .collect(Collectors.groupingBy(Player::positionGroup));

        List<Player> gks  = ranked(byGroup.getOrDefault("GK", List.of()));
        List<Player> defs = ranked(byGroup.getOrDefault("DEF", List.of()));
        List<Player> mids = ranked(byGroup.getOrDefault("MID", List.of()));
        List<Player> fwds = ranked(byGroup.getOrDefault("FWD", List.of()));

        List<Player> startingXI = new ArrayList<>();
        startingXI.addAll(pick(gks, 1));
        startingXI.addAll(pick(defs, slots[0]));
        startingXI.addAll(pick(mids, slots[1]));
        startingXI.addAll(pick(fwds, slots[2]));

        Set<Integer> starterIds = startingXI.stream()
                .map(Player::id).collect(Collectors.toSet());

        List<Player> bench = team.squad().stream()
                .filter(p -> p.position() != null)
                .filter(p -> !starterIds.contains(p.id()))
                .sorted(Comparator.comparingInt((Player p) -> groupOrder(p.positionGroup()))
                        .thenComparingInt(Player::shirtNumber)
                        .thenComparing(Player::name))
                .limit(9)
                .toList();

        return new PredictedLineup(team.name(), team.shortName(), team.crest(),
                formation, startingXI, bench);
    }

    /** Parse "4-3-3" -> [4,3,3] or "4-2-3-1" -> [4,5,1] (merge inner mids). */
    private int[] parseFormation(String f) {
        String[] parts = f.split("-");
        if (parts.length == 3) {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])};
        }
        if (parts.length == 4) {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]) + Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])};
        }
        if (parts.length == 5) {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]) + Integer.parseInt(parts[2]) + Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4])};
        }
        return new int[]{4, 3, 3};
    }

    private List<Player> ranked(List<Player> players) {
        return players.stream()
                .sorted(Comparator.comparingInt((Player p) -> p.shirtNumber() == 0 ? 999 : p.shirtNumber())
                        .thenComparing(Player::name))
                .toList();
    }

    private List<Player> pick(List<Player> ranked, int n) {
        return ranked.stream().limit(n).toList();
    }

    private int groupOrder(String g) {
        return switch (g) {
            case "GK" -> 0; case "DEF" -> 1; case "MID" -> 2; case "FWD" -> 3;
            default -> 4;
        };
    }
}
