package dev.zwo.modpanel.service;

import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.ActionResult;
import dev.zwo.modpanel.model.Actor;
import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.paper.BanGateway;
import dev.zwo.modpanel.service.TargetResolver.ResolvedTarget;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Ips;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Bans de perfil e de IP: banco + banlist nativa sempre juntos. */
public final class PunishmentService {

    static final String ORIGIN = "MODPANEL";

    private final PunishmentRepository repository;
    private final BanGateway gateway;
    private final TargetResolver resolver;
    private final Tasks tasks;
    private final Messages messages;
    private final Notifier notifier;
    private final ActiveCache cache;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public PunishmentService(PunishmentRepository repository, BanGateway gateway, TargetResolver resolver, Tasks tasks,
                             Messages messages, Notifier notifier, ActiveCache cache, Supplier<Settings> settings,
                             Logger logger) {
        this.repository = repository;
        this.gateway = gateway;
        this.resolver = resolver;
        this.tasks = tasks;
        this.messages = messages;
        this.notifier = notifier;
        this.cache = cache;
        this.settings = settings;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ ban

    public CompletableFuture<ActionResult> banPermanent(Actor actor, String target, String reason) {
        return run("aplicar o ban", () -> applyBan(actor, target, sanitizeReason(reason), null));
    }

    public CompletableFuture<ActionResult> tempBan(Actor actor, String target, Duration duration, String reason) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return done(fail("ban.invalid-duration"));
        }
        long expiresAt;
        try {
            long millis = duration.toMillis();
            if (millis > settings.get().maxTempBanMillis()) {
                return done(fail("ban.duration-over-limit"));
            }
            expiresAt = Math.addExact(System.currentTimeMillis(), millis);
        } catch (ArithmeticException e) {
            return done(fail("ban.duration-too-large"));
        }
        return run("aplicar o ban", () -> applyBan(actor, target, sanitizeReason(reason), expiresAt));
    }

    private ActionResult applyBan(Actor actor, String input, String reason, Long expiresAt) throws Exception {
        ResolvedTarget target;
        try {
            target = resolver.resolve(input);
        } catch (SQLException e) {
            return databaseFailure("resolver o jogador", e);
        }
        if (!target.knownProfile() || target.uuid() == null) {
            return fail("ban.unknown-profile");
        }
        if (target.isSameAs(actor)) {
            return fail("general.self-target");
        }
        if (tasks.onMain(() -> resolver.isProtected(target))) {
            return fail("general.protected-target", "player", target.name());
        }

        Settings s = settings.get();
        long now = System.currentTimeMillis();
        String caseId = UUID.randomUUID().toString();
        List<String> warnings = new ArrayList<>();

        List<PunishmentRecord> previousProfile;
        try {
            previousProfile = repository.activeForPlayer(target.name(), target.uuid(), EnumSet.of(PunishmentScope.PROFILE));
        } catch (SQLException e) {
            return databaseFailure("verificar punições já existentes", e);
        }
        Long profileExpiry = Expirations.merge(previousProfile, expiresAt);

        String ip = target.ip();
        boolean includeIp = false;
        List<PunishmentRecord> previousIp = List.of();
        Long ipExpiry = null;
        if (ip != null && !ip.isBlank()) {
            try {
                int accounts = repository.knownNameCountForIp(ip);
                if (accounts >= s.ipAccountLimit()) {
                    warnings.add(messages.format("ban.ip-shared", "count", accounts));
                } else {
                    previousIp = repository.activeForIp(ip);
                    ipExpiry = Expirations.merge(previousIp, expiresAt);
                    includeIp = true;
                }
            } catch (SQLException e) {
                logger.warning("Falha ao validar o IP de " + target.name() + ": " + e.getMessage());
                warnings.add(messages.raw("ban.ip-check-failed"));
            }
        } else {
            warnings.add(messages.raw("ban.no-known-ip"));
        }

        boolean withIp = includeIp;
        Long ipExpiryFinal = ipExpiry;
        boolean[] applied = tasks.onMain(() -> {
            boolean profileOk;
            try {
                profileOk = gateway.banProfile(target.uuid(), target.name(), reason, actor.name(), profileExpiry);
            } catch (RuntimeException e) {
                logger.warning("Falha no ban de perfil de " + target.name() + ": " + e.getMessage());
                profileOk = false;
            }
            boolean ipOk = profileOk && withIp && gateway.banIp(ip, reason, actor.name(), ipExpiryFinal);
            return new boolean[]{profileOk, ipOk};
        });
        boolean profileOk = applied[0];
        boolean ipOk = applied[1];

        if (!profileOk) {
            try {
                repository.insertAll(List.of(row(caseId, PunishmentScope.PROFILE, target, null, reason, actor,
                        PunishmentStatus.PARTIAL, now, expiresAt)));
            } catch (SQLException ignored) {
                // o registro parcial é só informativo
            }
            return fail("ban.native-profile-failed");
        }

        List<NewPunishment> rows = new ArrayList<>();
        rows.add(row(caseId, PunishmentScope.PROFILE, target, null, reason, actor, PunishmentStatus.ACTIVE, now, expiresAt));
        if (includeIp) {
            rows.add(row(caseId, PunishmentScope.IP, target, ip, reason, actor,
                    ipOk ? PunishmentStatus.ACTIVE : PunishmentStatus.PARTIAL, now, expiresAt));
            if (!ipOk) {
                warnings.add(messages.raw("ban.native-ip-failed"));
            }
        }

        List<PunishmentRecord> saved;
        try {
            saved = repository.insertAndMirror(rows, s.serverId(), now);
        } catch (SQLException e) {
            List<PunishmentRecord> profileBefore = previousProfile;
            List<PunishmentRecord> ipBefore = previousIp;
            tasks.onMain(() -> {
                restoreProfile(target.uuid(), target.name(), profileBefore);
                if (ipOk) {
                    restoreIp(ip, ipBefore);
                }
                return null;
            });
            return databaseFailure("salvar a punição; o ban nativo foi revertido", e);
        }

        int kickedOnIp = tasks.onMain(() -> {
            Component screen = notifier.banScreen(reason, actor.name(), profileExpiry, false);
            Player online = Bukkit.getPlayer(target.uuid());
            notifier.kick(online, screen);
            int others = 0;
            if (ipOk && s.kickSameIp()) {
                others = kickByIp(ip, reason, actor.name(), ipExpiryFinal, target.uuid());
            }
            notifier.broadcastBan(target.name(), reason, actor.name(), expiresAt);
            return others;
        });
        if (kickedOnIp > 0) {
            warnings.add(messages.format("ban.ip-kicked", "count", kickedOnIp));
        }
        refreshCache();
        return ActionResult.ok(messages.format("ban.applied", "player", target.name()), warnings, saved.size());
    }

    // --------------------------------------------------------------- ban ip

    public CompletableFuture<ActionResult> banIp(Actor actor, String input, String reason) {
        return run("banir o IP", () -> applyIpBan(actor, input, sanitizeReason(reason)));
    }

    private ActionResult applyIpBan(Actor actor, String input, String reason) throws Exception {
        String ip = Ips.normalize(input);
        ResolvedTarget target = null;
        if (ip == null) {
            target = resolver.resolve(input);
            ip = target.ip();
            if (ip == null || ip.isBlank()) {
                return fail("ban.ip-unknown", "player", input);
            }
            if (target.isSameAs(actor)) {
                return fail("general.self-target");
            }
            ResolvedTarget checked = target;
            if (tasks.onMain(() -> resolver.isProtected(checked))) {
                return fail("general.protected-target", "player", target.name());
            }
        }
        if (ip.equals(actor.ip())) {
            return fail("general.self-target");
        }

        Settings s = settings.get();
        int accounts = repository.knownNameCountForIp(ip);
        if (accounts >= s.ipAccountLimit()) {
            return fail("ban.ip-shared-blocked", "count", accounts);
        }
        List<PunishmentRecord> previous = repository.activeForIp(ip);

        String address = ip;
        boolean ok = tasks.onMain(() -> gateway.banIp(address, reason, actor.name(), null));
        if (!ok) {
            return fail("ban.ip-native-failed");
        }

        long now = System.currentTimeMillis();
        NewPunishment row = new NewPunishment(UUID.randomUUID().toString(), PunishmentScope.IP,
                target == null ? null : target.uuid(), target == null ? null : target.name(), ip, reason,
                actor.name(), ORIGIN, PunishmentStatus.ACTIVE, now, null, null, s.serverId());
        try {
            repository.insertAndMirror(List.of(row), s.serverId(), now);
        } catch (SQLException e) {
            tasks.onMain(() -> {
                restoreIp(address, previous);
                return null;
            });
            return databaseFailure("banir o IP", e);
        }

        List<String> warnings = new ArrayList<>();
        if (s.kickSameIp()) {
            int kicked = tasks.onMain(() -> kickByIp(address, reason, actor.name(), null, null));
            if (kicked > 0) {
                warnings.add(messages.format("ban.ip-kicked", "count", kicked));
            }
        }
        refreshCache();
        return ActionResult.ok(messages.format("ban.ip-applied", "ip", ip), warnings, 1);
    }

    // ---------------------------------------------------------------- unban

    public CompletableFuture<ActionResult> unbanPlayer(Actor actor, String input) {
        return run("desbanir o jogador", () -> {
            ResolvedTarget target = resolver.resolve(input);
            long now = System.currentTimeMillis();
            List<PunishmentRecord> revoked = repository.revokePlayerBan(target.name(), target.uuid(), actor.name(), now);
            int remainingProfile = repository.activeProfileCount(target.name(), target.uuid());

            Map<String, Integer> remainingIp = new LinkedHashMap<>();
            for (PunishmentRecord record : revoked) {
                if (record.scope() == PunishmentScope.IP && record.targetIp() != null) {
                    remainingIp.put(record.targetIp(), repository.activeIpCount(record.targetIp()));
                }
            }

            List<String> warnings = new ArrayList<>();
            boolean wasNative = tasks.onMain(() -> {
                boolean banned = gateway.isProfileBanned(target.uuid(), target.name());
                if (remainingProfile > 0) {
                    warnings.add(messages.format("unban.profile-still-active", "count", remainingProfile));
                } else if (banned && !gateway.pardonProfile(target.uuid(), target.name())) {
                    warnings.add(messages.raw("unban.native-still-banned"));
                }
                for (Map.Entry<String, Integer> entry : remainingIp.entrySet()) {
                    if (entry.getValue() > 0) {
                        warnings.add(messages.format("unban.ip-still-active", "ip", entry.getKey()));
                    } else if (!gateway.pardonIp(entry.getKey())) {
                        warnings.add(messages.format("unban.ip-pardon-failed", "ip", entry.getKey()));
                    }
                }
                return banned;
            });

            forgetMirror(revoked);
            refreshCache();
            if (revoked.isEmpty() && !wasNative) {
                return fail("unban.nothing", "player", target.name());
            }
            return ActionResult.ok(messages.format("unban.done", "player", target.name(), "count", revoked.size()),
                    warnings, revoked.size());
        });
    }

    public CompletableFuture<ActionResult> unbanIp(Actor actor, String input) {
        String ip = Ips.normalize(input);
        if (ip == null) {
            return done(fail("ban.invalid-ip"));
        }
        return run("desbanir o IP", () -> {
            long now = System.currentTimeMillis();
            List<PunishmentRecord> revoked = repository.revokeIpFull(ip, actor.name(), now);

            Map<String, PunishmentRecord> profiles = new LinkedHashMap<>();
            for (PunishmentRecord record : revoked) {
                if (record.scope() == PunishmentScope.PROFILE) {
                    profiles.putIfAbsent(record.targetKey(), record);
                }
            }
            Map<PunishmentRecord, Integer> remaining = new LinkedHashMap<>();
            for (PunishmentRecord record : profiles.values()) {
                remaining.put(record, repository.activeProfileCount(record.targetName(), record.targetUuid()));
            }

            List<String> warnings = new ArrayList<>();
            boolean wasNative = tasks.onMain(() -> {
                boolean banned = gateway.isIpBanned(ip);
                if (banned && !gateway.pardonIp(ip)) {
                    warnings.add(messages.raw("unban.ip-still-banned"));
                }
                for (Map.Entry<PunishmentRecord, Integer> entry : remaining.entrySet()) {
                    PunishmentRecord record = entry.getKey();
                    if (entry.getValue() == 0 && !gateway.pardonProfile(record.targetUuid(), record.targetName())) {
                        warnings.add(messages.format("unban.profile-pardon-failed", "player", record.targetName()));
                    }
                }
                return banned;
            });

            forgetMirror(revoked);
            refreshCache();
            if (revoked.isEmpty() && !wasNative) {
                return fail("unban.ip-nothing", "ip", ip);
            }
            return ActionResult.ok(messages.format("unban.ip-done", "ip", ip, "count", revoked.size()),
                    warnings, revoked.size());
        });
    }

    public CompletableFuture<ActionResult> unbanAll(Actor actor) {
        return run("executar o desbanimento geral", () -> {
            List<PunishmentRecord> revoked = repository.revokeAllBans(actor.name(), System.currentTimeMillis());
            Set<String> ips = new LinkedHashSet<>();
            Map<String, PunishmentRecord> profiles = new LinkedHashMap<>();
            for (PunishmentRecord record : revoked) {
                if (record.scope() == PunishmentScope.IP && record.targetIp() != null) {
                    ips.add(record.targetIp());
                } else if (record.scope() == PunishmentScope.PROFILE) {
                    profiles.putIfAbsent(record.targetKey(), record);
                }
            }
            tasks.onMain(() -> {
                ips.forEach(gateway::pardonIp);
                profiles.values().forEach(r -> gateway.pardonProfile(r.targetUuid(), r.targetName()));
                return null;
            });
            forgetMirror(revoked);
            refreshCache();
            return ActionResult.ok(messages.format("unban.all-done", "count", revoked.size()), revoked.size());
        });
    }

    // ------------------------------------------------------------- cleanup

    /** Encerra bans vencidos e ajusta a banlist nativa. Worker. */
    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        try {
            Set<String> cases = new LinkedHashSet<>();
            for (PunishmentRecord record : repository.due(now)) {
                if (record.scope().isBan()) {
                    cases.add(record.caseId());
                }
            }
            if (cases.isEmpty()) {
                return 0;
            }

            List<PunishmentRecord> expired = new ArrayList<>();
            for (String caseId : cases) {
                expired.addAll(repository.expireCase(caseId, now));
            }

            Map<String, PunishmentRecord> targets = new LinkedHashMap<>();
            expired.forEach(r -> targets.putIfAbsent(r.targetKey(), r));
            Map<PunishmentRecord, List<PunishmentRecord>> remaining = new LinkedHashMap<>();
            for (PunishmentRecord record : targets.values()) {
                remaining.put(record, record.scope() == PunishmentScope.PROFILE
                        ? repository.activeForPlayer(record.targetName(), record.targetUuid(), EnumSet.of(PunishmentScope.PROFILE))
                        : repository.activeForIp(record.targetIp()));
            }

            tasks.onMain(() -> {
                for (Map.Entry<PunishmentRecord, List<PunishmentRecord>> entry : remaining.entrySet()) {
                    PunishmentRecord record = entry.getKey();
                    if (record.scope() == PunishmentScope.PROFILE) {
                        restoreProfile(record.targetUuid(), record.targetName(), entry.getValue());
                    } else if (record.targetIp() != null) {
                        restoreIp(record.targetIp(), entry.getValue());
                    }
                }
                return null;
            });

            forgetMirror(expired);
            if (!expired.isEmpty()) {
                logger.info("Bans expirados: " + expired.size() + " componente(s).");
                refreshCache();
            }
            return expired.size();
        } catch (Exception e) {
            tasks.log("Falha na limpeza de bans", e);
            return 0;
        }
    }

    public List<PunishmentRecord> history(ResolvedTarget target, int page, int perPage) throws SQLException {
        int offset = Math.max(0, page - 1) * perPage;
        return repository.historyForPlayer(target.name(), target.uuid(), perPage + 1, offset);
    }

    // -------------------------------------------------------------- helpers

    /** Reaplica o ban mais forte que restou para o perfil ou remove o ban nativo. Thread principal. */
    void restoreProfile(UUID uuid, String name, List<PunishmentRecord> active) {
        if (active.isEmpty()) {
            gateway.pardonProfile(uuid, name);
            return;
        }
        PunishmentRecord first = active.getFirst();
        gateway.banProfile(Objects.requireNonNullElse(first.targetUuid(), uuid), first.targetName(), first.reason(),
                first.actor(), Expirations.effective(active));
    }

    /** Thread principal. */
    void restoreIp(String ip, List<PunishmentRecord> active) {
        if (active.isEmpty()) {
            gateway.pardonIp(ip);
            return;
        }
        PunishmentRecord first = active.getFirst();
        gateway.banIp(first.targetIp(), first.reason(), first.actor(), Expirations.effective(active));
    }

    /** Desconecta quem está no IP banido. Thread principal. */
    int kickByIp(String ip, String reason, String staff, Long expiresAt, UUID skip) {
        Component screen = notifier.banScreen(reason, staff, expiresAt, true);
        int kicked = 0;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (player.getUniqueId().equals(skip)) {
                continue;
            }
            if (ip.equals(Ips.normalize(player.getAddress()))) {
                notifier.kick(player, screen);
                kicked++;
            }
        }
        return kicked;
    }

    private void forgetMirror(List<PunishmentRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        try {
            repository.removeMirror(settings.get().serverId(), records.stream().map(PunishmentRecord::id).toList());
        } catch (SQLException e) {
            logger.warning("Falha ao atualizar o espelho de bans: " + e.getMessage());
        }
    }

    private void refreshCache() {
        try {
            cache.refreshNames(repository);
        } catch (SQLException e) {
            logger.fine("Cache de nomes não atualizado: " + e.getMessage());
        }
    }

    private NewPunishment row(String caseId, PunishmentScope scope, ResolvedTarget target, String ip, String reason,
                              Actor actor, PunishmentStatus status, long now, Long expiresAt) {
        return new NewPunishment(caseId, scope, target.uuid(), target.name(), ip, reason, actor.name(), ORIGIN,
                status, now, expiresAt, null, settings.get().serverId());
    }

    String sanitizeReason(String reason) {
        String value = reason == null || reason.isBlank() ? messages.defaultReason() : reason;
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private CompletableFuture<ActionResult> run(String action, Tasks.Job<ActionResult> job) {
        return tasks.async(job).exceptionally(error -> {
            Throwable cause = Tasks.unwrap(error);
            tasks.log("Falha ao " + action, cause);
            return ActionResult.fail(messages.format("general.database-error", "action", action));
        });
    }

    private ActionResult databaseFailure(String action, SQLException e) {
        logger.severe("Falha ao " + action + ": " + e.getMessage());
        return ActionResult.fail(messages.format("general.database-error", "action", action));
    }

    private ActionResult fail(String key, Object... pairs) {
        return ActionResult.fail(messages.format(key, pairs));
    }

    private static CompletableFuture<ActionResult> done(ActionResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
