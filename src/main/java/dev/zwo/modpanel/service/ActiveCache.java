package dev.zwo.modpanel.service;

import dev.zwo.modpanel.model.Names;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.storage.PunishmentRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dados em memória para o que precisa responder na hora: tab-complete e bloqueio de chat.
 * O banco continua sendo a fonte da verdade.
 */
public final class ActiveCache {

    private volatile List<String> bannedNames = List.of();
    private volatile List<String> mutedNames = List.of();
    private final Map<UUID, Long> chatByUuid = new ConcurrentHashMap<>();
    private final Map<String, Long> chatByName = new ConcurrentHashMap<>();

    /** Recarrega as listas de nomes usadas no tab-complete. Worker. */
    public void refreshNames(PunishmentRepository repository) throws SQLException {
        bannedNames = names(repository.active(PunishmentScope.PROFILE));
        mutedNames = names(repository.active(EnumSet.of(PunishmentScope.VOICE, PunishmentScope.CHAT)));
    }

    private static List<String> names(Collection<PunishmentRecord> records) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PunishmentRecord record : records) {
            if (record.targetName() != null && !record.targetName().isBlank()) {
                names.add(record.targetName());
            }
        }
        return List.copyOf(names);
    }

    public List<String> bannedNames() {
        return bannedNames;
    }

    public List<String> mutedNames() {
        return mutedNames;
    }

    public void setChatMute(UUID uuid, String name, long expiresAt) {
        if (uuid != null) {
            chatByUuid.merge(uuid, expiresAt, Math::max);
        }
        if (name != null && !name.isBlank()) {
            chatByName.merge(Names.key(name), expiresAt, Math::max);
        }
    }

    public void clearChatMute(UUID uuid, String name) {
        if (uuid != null) {
            chatByUuid.remove(uuid);
        }
        if (name != null) {
            chatByName.remove(Names.key(name));
        }
    }

    public void clearAllChatMutes() {
        chatByUuid.clear();
        chatByName.clear();
    }

    /** Troca o estado de um jogador pelo que está no banco. */
    public void replaceChatMutes(UUID uuid, String name, List<PunishmentRecord> activeChat) {
        clearChatMute(uuid, name);
        for (PunishmentRecord record : activeChat) {
            if (record.scope() == PunishmentScope.CHAT && record.expiresAt() != null) {
                setChatMute(uuid, name, record.expiresAt());
            }
        }
    }

    /** Fim do mute de chat ou null se o jogador puder falar. */
    public Long chatMutedUntil(UUID uuid, String name, long now) {
        Long until = uuid == null ? null : chatByUuid.get(uuid);
        if (until == null && name != null) {
            until = chatByName.get(Names.key(name));
        }
        if (until == null) {
            return null;
        }
        if (until <= now) {
            clearChatMute(uuid, name);
            return null;
        }
        return until;
    }

    /** Recria o estado de chat a partir de todos os mutes de chat ativos. */
    public void rebuildChat(Collection<PunishmentRecord> activeChat) {
        Map<UUID, Long> byUuid = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        for (PunishmentRecord record : activeChat) {
            if (record.scope() != PunishmentScope.CHAT || record.expiresAt() == null) {
                continue;
            }
            if (record.targetUuid() != null) {
                byUuid.merge(record.targetUuid(), record.expiresAt(), Math::max);
            }
            if (record.targetName() != null && !record.targetName().isBlank()) {
                byName.merge(Names.key(record.targetName()), record.expiresAt(), Math::max);
            }
        }
        chatByUuid.keySet().retainAll(byUuid.keySet());
        chatByName.keySet().retainAll(byName.keySet());
        chatByUuid.putAll(byUuid);
        chatByName.putAll(byName);
    }

    public List<UUID> chatMutedPlayers() {
        return new ArrayList<>(chatByUuid.keySet());
    }
}
