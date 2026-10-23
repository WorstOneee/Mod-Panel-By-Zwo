package dev.zwo.modpanel.model;

import java.util.UUID;

public record PlayerIdentity(UUID uuid, String name, String lastIp, long updatedAt) {
}
