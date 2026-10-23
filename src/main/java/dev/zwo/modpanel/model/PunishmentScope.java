package dev.zwo.modpanel.model;

import java.util.Locale;

public enum PunishmentScope {
    PROFILE,
    IP,
    VOICE,
    CHAT;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isBan() {
        return this == PROFILE || this == IP;
    }

    public boolean isMute() {
        return this == VOICE || this == CHAT;
    }
}
