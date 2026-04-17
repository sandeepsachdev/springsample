package com.example.pllineup.model;

import java.util.List;

public record PredictedLineup(
        String teamName,
        String shortName,
        String crest,
        String formation,
        List<Player> startingXI,
        List<Player> bench
) {}
