package com.example.pllineup.model;

import java.util.List;

public record Team(
        int id,
        String name,
        String shortName,
        String crest,
        String formation,
        List<Player> squad
) {}
