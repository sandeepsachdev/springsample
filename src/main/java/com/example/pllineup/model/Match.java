package com.example.pllineup.model;

import java.time.ZonedDateTime;

public record Match(
        int id,
        ZonedDateTime utcDate,
        int matchday,
        String status,
        TeamRef homeTeam,
        TeamRef awayTeam
) {
    public record TeamRef(int id, String name, String shortName, String crest) {}
}
