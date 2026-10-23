package dev.zwo.modpanel.model;

import java.util.UUID;

/** Quem executou a ação (jogador ou console). */
public record Actor(String name, UUID uuid, String ip) {

    public static Actor system(String name) {
        return new Actor(name, null, null);
    }
}
