package com.example.pllineup.model;

public record ScorePrediction(
        int homeGoals,
        int awayGoals,
        String outcome,
        int confidence
) {
    public String outcomeLabel() {
        return switch (outcome) {
            case "HOME_WIN" -> "Home Win";
            case "AWAY_WIN" -> "Away Win";
            default -> "Draw";
        };
    }
}
