package dev.zwo.modpanel.service;

import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.Actor;
import dev.zwo.modpanel.model.Names;
import dev.zwo.modpanel.model.PlayerIdentity;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Ips;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Descobre UUID, nome e último IP de um alvo digitado pela staff. */
public final class TargetResolver {

    public record ResolvedTarget(UUID uuid, String name, String ip, boolean online, boolean knownProfile) {

        public boolean isSameAs(Actor actor) {
            if (actor == null) {
                return false;
            }
            if (uuid != null && uuid.equals(actor.uuid())) {
                return true;
            }
            return actor.uuid() != null && name != null && Names.key(name).equals(Names.key(actor.name()));
        }
    }

    private record Snapshot(UUID uuid, String name, String ip, boolean online) {
    }

    private final PunishmentRepository repository;
    private final Tasks tasks;
    private final Supplier<Settings> settings;

    public TargetResolver(PunishmentRepository repository, Tasks tasks, Supplier<Settings> settings) {
        this.repository = repository;
        this.tasks = tasks;
        this.settings = settings;
    }

    /** Bloqueante; chamar a partir de um worker. */
    public ResolvedTarget resolve(String input) throws Exception {
        String query = input == null ? "" : input.trim();
        UUID parsed = parseUuid(query);
        Snapshot snapshot = tasks.onMain(() -> snapshot(query, parsed));
        long now = System.currentTimeMillis();

        if (snapshot != null && snapshot.online()) {
            return new ResolvedTarget(snapshot.uuid(), snapshot.name(), snapshot.ip(), true, true);
        }

        Optional<PlayerIdentity> identity = parsed != null
                ? repository.findIdentity(parsed)
                : repository.findIdentity(query);
        if (identity.isPresent()) {
            PlayerIdentity found = identity.get();
            String ip = found.lastIp() != null ? found.lastIp() : repository.findLastIp(found.name()).orElse(null);
            return new ResolvedTarget(found.uuid(), found.name(), ip, false, true);
        }

        if (snapshot != null) {
            String ip = repository.findLastIp(snapshot.name()).orElse(null);
            repository.upsertIdentity(snapshot.uuid(), snapshot.name(), ip, now);
            return new ResolvedTarget(snapshot.uuid(), snapshot.name(), ip, false, true);
        }

        String ip = parsed == null ? repository.findLastIp(query).orElse(null) : null;
        return new ResolvedTarget(parsed, query, ip, false, false);
    }

    private Snapshot snapshot(String query, UUID parsed) {
        Player player = parsed != null ? Bukkit.getPlayer(parsed) : Bukkit.getPlayerExact(query);
        if (player != null) {
            return new Snapshot(player.getUniqueId(), player.getName(), Ips.normalize(player.getAddress()), true);
        }
        if (parsed != null || query.isEmpty()) {
            return null;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(query);
        if (cached != null && cached.getName() != null) {
            return new Snapshot(cached.getUniqueId(), cached.getName(), null, false);
        }
        return null;
    }

    /** Thread principal: alvo online com a permissão de proteção. */
    public boolean isProtected(ResolvedTarget target) {
        if (target.uuid() == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(target.uuid());
        return player != null && player.hasPermission(settings.get().permission("protected"));
    }

    private static UUID parseUuid(String value) {
        if (value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
