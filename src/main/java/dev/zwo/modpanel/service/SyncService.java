package dev.zwo.modpanel.service;

import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.paper.BanGateway;
import dev.zwo.modpanel.paper.BanGateway.NativeIpBan;
import dev.zwo.modpanel.paper.BanGateway.NativeProfileBan;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Ips;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Mantém banco e banlist nativa de acordo.
 *
 * <p>Cada servidor guarda em {@code ban_mirror} quais punições ele já aplicou na própria banlist.
 * Com isso dá para diferenciar um ban removido por /pardon neste servidor (vira REVOKED_EXTERNAL)
 * de um ban criado em outro servidor da rede (é aplicado aqui).</p>
 */
public final class SyncService {

    public record SyncReport(boolean success, boolean skipped, int imported, int revokedExternal, int repaired,
                             int applied, int review, String error) {

        static SyncReport skippedRun() {
            return new SyncReport(true, true, 0, 0, 0, 0, 0, null);
        }

        static SyncReport failed(String error) {
            return new SyncReport(false, false, 0, 0, 0, 0, 0, error);
        }

        int changes() {
            return imported + revokedExternal + repaired + applied + review;
        }
    }

    private static final String SYNC_ACTOR = "Sincronização nativa";
    private static final String NATIVE_ORIGIN = "NATIVE_SYNC";
    private static final String LEGACY_ORIGIN = "LEGACY_YAML";

    private final PunishmentRepository repository;
    private final BanGateway gateway;
    private final Tasks tasks;
    private final Notifier notifier;
    private final ActiveCache cache;
    private final Supplier<Settings> settings;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean rerun;

    public SyncService(PunishmentRepository repository, BanGateway gateway, Tasks tasks, Notifier notifier,
                       ActiveCache cache, Supplier<Settings> settings, Logger logger) {
        this.repository = repository;
        this.gateway = gateway;
        this.tasks = tasks;
        this.notifier = notifier;
        this.cache = cache;
        this.settings = settings;
        this.logger = logger;
    }

    public CompletableFuture<SyncReport> sync(String trigger) {
        if (!running.compareAndSet(false, true)) {
            rerun = true;
            return CompletableFuture.completedFuture(SyncReport.skippedRun());
        }
        return tasks.async(() -> {
            try {
                return run(trigger);
            } finally {
                running.set(false);
                if (rerun) {
                    rerun = false;
                    sync(trigger + "+");
                }
            }
        });
    }

    /** Planejamento feito na thread principal, gravação depois no worker. */
    private static final class Plan {
        final List<PunishmentRecord> revokeExternal = new ArrayList<>();
        final List<PunishmentRecord> review = new ArrayList<>();
        final Set<Long> adopt = new HashSet<>();
        final Set<Long> unmirror = new HashSet<>();
        final List<NewPunishment> imports = new ArrayList<>();
        int repaired;
        int applied;
    }

    private SyncReport run(String trigger) {
        Settings s = settings.get();
        String server = s.serverId();
        boolean network = s.network();
        long now = System.currentTimeMillis();
        try {
            List<PunishmentRecord> active = repository.active(EnumSet.of(PunishmentScope.PROFILE, PunishmentScope.IP));
            Set<Long> activeIds = active.stream().map(PunishmentRecord::id).collect(Collectors.toSet());
            // vencidos ficam para a limpeza de expirados
            List<PunishmentRecord> live = active.stream().filter(r -> !r.expired(now)).toList();
            Set<Long> mirrored = repository.mirroredIds(server);
            List<Long> staleIds = mirrored.stream().filter(id -> !activeIds.contains(id)).toList();
            List<PunishmentRecord> stale = repository.byIds(staleIds);

            Plan plan = tasks.onMain(() -> plan(live, mirrored, stale, staleIds, server, network, now));

            int revoked = 0;
            for (PunishmentRecord record : plan.revokeExternal) {
                if (repository.markStatus(record.id(), PunishmentStatus.REVOKED_EXTERNAL, SYNC_ACTOR, now)) {
                    revoked++;
                }
                plan.unmirror.add(record.id());
            }
            int review = 0;
            for (PunishmentRecord record : plan.review) {
                if (repository.markStatus(record.id(), PunishmentStatus.REVIEW, SYNC_ACTOR, now)) {
                    review++;
                }
            }
            repository.addMirror(server, plan.adopt, now);
            repository.removeMirror(server, plan.unmirror);
            int imported = repository.insertAndMirror(plan.imports, server, now).size();

            SyncReport report = new SyncReport(true, false, imported, revoked, plan.repaired, plan.applied, review, null);
            if (report.changes() > 0) {
                logger.info("Sincronização (" + trigger + "): importados=" + imported + ", removidos-externos=" + revoked
                        + ", reparados=" + plan.repaired + ", aplicados-da-rede=" + plan.applied + ", revisão=" + review + ".");
                cache.refreshNames(repository);
            }
            return report;
        } catch (Exception e) {
            Throwable cause = Tasks.unwrap(e);
            logger.warning("Falha na sincronização de bans (" + trigger + "): " + cause.getMessage());
            return SyncReport.failed(String.valueOf(cause.getMessage()));
        }
    }

    private Plan plan(List<PunishmentRecord> live, Set<Long> mirrored, List<PunishmentRecord> stale,
                      List<Long> staleIds, String server, boolean network, long now) {
        List<NativeProfileBan> profiles = gateway.profileBans().stream().filter(b -> !b.expired(now)).toList();
        List<NativeIpBan> ips = gateway.ipBans().stream().filter(b -> !b.expired(now)).toList();
        Plan plan = new Plan();
        List<PunishmentRecord> kickProfiles = new ArrayList<>();
        List<PunishmentRecord> kickIps = new ArrayList<>();

        for (PunishmentRecord record : live) {
            boolean present = record.scope() == PunishmentScope.PROFILE
                    ? profiles.stream().anyMatch(ban -> matches(record, ban))
                    : record.targetIp() != null && ips.stream().anyMatch(ban -> ban.ip().equalsIgnoreCase(record.targetIp()));

            if (mirrored.contains(record.id())) {
                if (!present) {
                    plan.revokeExternal.add(record);
                }
                continue;
            }
            if (present) {
                plan.adopt.add(record.id());
                continue;
            }
            if (LEGACY_ORIGIN.equals(record.origin())) {
                if (applyNative(record, live)) {
                    plan.repaired++;
                    plan.adopt.add(record.id());
                } else {
                    plan.review.add(record);
                }
                continue;
            }
            if (network && !server.equals(record.serverId())) {
                if (applyNative(record, live)) {
                    plan.applied++;
                    plan.adopt.add(record.id());
                    (record.scope() == PunishmentScope.PROFILE ? kickProfiles : kickIps).add(record);
                }
                continue;
            }
            plan.revokeExternal.add(record);
        }

        // punições que deixaram de valer (removidas em outro servidor ou expiradas)
        for (PunishmentRecord record : stale) {
            boolean covered = live.stream().anyMatch(other -> sameTarget(other, record));
            if (!covered) {
                if (record.scope() == PunishmentScope.PROFILE) {
                    gateway.pardonProfile(record.targetUuid(), record.targetName());
                } else if (record.targetIp() != null) {
                    gateway.pardonIp(record.targetIp());
                }
            }
        }
        plan.unmirror.addAll(staleIds);

        // bans feitos por fora do painel neste servidor
        for (NativeProfileBan ban : profiles) {
            boolean known = live.stream().anyMatch(r -> r.scope() == PunishmentScope.PROFILE && matches(r, ban))
                    || stale.stream().anyMatch(r -> r.scope() == PunishmentScope.PROFILE && matches(r, ban));
            if (!known) {
                plan.imports.add(new NewPunishment(
                        "native-" + stable(ban.uuid(), ban.name(), ban.createdAt()),
                        PunishmentScope.PROFILE, ban.uuid(), safe(ban.name(), "Desconhecido"), null,
                        safe(ban.reason(), "Sincronizado da banlist nativa"), safe(ban.actor(), "Servidor"),
                        NATIVE_ORIGIN, PunishmentStatus.ACTIVE, ban.createdAt(), ban.expiresAt(),
                        "native:profile:" + stable(ban.uuid(), ban.name(), ban.createdAt(), ban.reason()), server));
            }
        }
        for (NativeIpBan ban : ips) {
            boolean known = live.stream().anyMatch(r -> r.scope() == PunishmentScope.IP && ban.ip().equalsIgnoreCase(r.targetIp()))
                    || stale.stream().anyMatch(r -> r.scope() == PunishmentScope.IP && ban.ip().equalsIgnoreCase(r.targetIp()));
            if (!known) {
                plan.imports.add(new NewPunishment(
                        "native-" + stable(ban.ip(), ban.createdAt()),
                        PunishmentScope.IP, null, "IP", ban.ip(),
                        safe(ban.reason(), "Sincronizado da banlist nativa"), safe(ban.actor(), "Servidor"),
                        NATIVE_ORIGIN, PunishmentStatus.ACTIVE, ban.createdAt(), ban.expiresAt(),
                        "native:ip:" + stable(ban.ip(), ban.createdAt(), ban.reason()), server));
            }
        }

        kick(kickProfiles, kickIps);
        return plan;
    }

    private boolean applyNative(PunishmentRecord record, List<PunishmentRecord> live) {
        List<PunishmentRecord> sameTarget = live.stream().filter(other -> sameTarget(other, record)).toList();
        Long expiresAt = Expirations.effective(sameTarget);
        if (record.scope() == PunishmentScope.PROFILE) {
            return record.targetUuid() != null
                    && gateway.banProfile(record.targetUuid(), record.targetName(), record.reason(), record.actor(), expiresAt);
        }
        return record.targetIp() != null && gateway.banIp(record.targetIp(), record.reason(), record.actor(), expiresAt);
    }

    private void kick(List<PunishmentRecord> profiles, List<PunishmentRecord> ips) {
        for (PunishmentRecord record : profiles) {
            Player player = Bukkit.getPlayer(record.targetUuid());
            if (player != null) {
                notifier.kick(player, notifier.banScreen(record.reason(), record.actor(), record.expiresAt(), false));
            }
        }
        if (ips.isEmpty() || !settings.get().kickSameIp()) {
            return;
        }
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            String address = Ips.normalize(player.getAddress());
            for (PunishmentRecord record : ips) {
                if (record.targetIp().equals(address)) {
                    Component screen = notifier.banScreen(record.reason(), record.actor(), record.expiresAt(), true);
                    notifier.kick(player, screen);
                    break;
                }
            }
        }
    }

    private static boolean matches(PunishmentRecord record, NativeProfileBan ban) {
        if (record.targetUuid() != null && ban.uuid() != null) {
            return record.targetUuid().equals(ban.uuid());
        }
        return record.targetName() != null && ban.name() != null && record.targetName().equalsIgnoreCase(ban.name());
    }

    private static boolean sameTarget(PunishmentRecord a, PunishmentRecord b) {
        if (a.scope() != b.scope()) {
            return false;
        }
        if (a.scope() == PunishmentScope.IP) {
            return a.targetIp() != null && a.targetIp().equalsIgnoreCase(b.targetIp());
        }
        UUID au = a.targetUuid();
        UUID bu = b.targetUuid();
        if (au != null && bu != null) {
            return au.equals(bu);
        }
        return a.targetName() != null && a.targetName().equalsIgnoreCase(b.targetName());
    }

    /** Mesmo formato de identificador usado desde a versão 2, para não duplicar importações. */
    static String stable(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = Arrays.stream(values).map(String::valueOf).collect(Collectors.joining("|"));
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
