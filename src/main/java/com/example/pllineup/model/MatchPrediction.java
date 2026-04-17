package com.example.pllineup.model;

import java.time.ZonedDateTime;

public record MatchPrediction(
        int matchId,
        ZonedDateTime utcDate,
        int matchday,
        PredictedLineup home,
        PredictedLineup away
) {}
