package dev.zwo.modpanel.model;

import java.util.UUID;

public record NewPunishment(
        String caseId,
        PunishmentScope scope,
        UUID targetUuid,
        String targetName,
        String targetIp,
        String reason,
        String actor,
        String origin,
        PunishmentStatus status,
        long createdAt,
        Long expiresAt,
        String legacyKey,
        String serverId
) {
}
