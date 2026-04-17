package com.example.pllineup.model;

public record Player(
        int id,
        String name,
        String position,
        int shirtNumber
) {
    public String positionGroup() {
        if (position == null) return "Unknown";
        return switch (position) {
            case "Goalkeeper" -> "GK";
            case "Defence", "Left-Back", "Right-Back", "Centre-Back" -> "DEF";
            case "Midfield", "Central Midfield", "Attacking Midfield",
                 "Defensive Midfield", "Left Midfield", "Right Midfield",
                 "Left Winger", "Right Winger" -> "MID";
            case "Offence", "Centre-Forward" -> "FWD";
            default -> "Unknown";
        };
    }
}
