package dev.zwo.modpanel.model;

import java.util.UUID;

public record PunishmentRecord(
        long id,
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
        Long revokedAt,
        String revokedBy,
        String legacyKey,
        String serverId
) {

    public boolean temporary() {
        return expiresAt != null;
    }

    public boolean expired(long now) {
        return expiresAt != null && expiresAt <= now;
    }

    public boolean active() {
        return status == PunishmentStatus.ACTIVE;
    }

    /** Chave usada para agrupar registros do mesmo alvo. */
    public String targetKey() {
        if (scope == PunishmentScope.IP) {
            return "ip:" + targetIp;
        }
        if (targetUuid != null) {
            return "uuid:" + targetUuid;
        }
        return "name:" + Names.key(targetName);
    }
}
