package dev.zwo.modpanel.model;

import java.util.Locale;

public enum PunishmentStatus {
    ACTIVE,
    REVOKED,
    REVOKED_EXTERNAL,
    EXPIRED,
    PARTIAL,
    REVIEW;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
