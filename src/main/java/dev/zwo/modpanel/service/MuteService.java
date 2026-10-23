package dev.zwo.modpanel.service;

import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.ActionResult;
import dev.zwo.modpanel.model.Actor;
import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.service.TargetResolver.ResolvedTarget;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Placeholders;
import dev.zwo.modpanel.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Mutes de voz (via comando, normalmente LuckPerms) e de chat (bloqueio próprio e/ou comando). */
public final class MuteService {

    public record RadiusResult(int muted, List<String> failed) {
    }

    private static final Set<PunishmentScope> MUTES = EnumSet.of(PunishmentScope.VOICE, PunishmentScope.CHAT);

    private final PunishmentRepository repository;
    private final TargetResolver resolver;
    private final Tasks tasks;
    private final Messages messages;
    private final ActiveCache cache;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public MuteService(PunishmentRepository repository, TargetResolver resolver, Tasks tasks, Messages messages,
                       ActiveCache cache, Supplier<Settings> settings, Logger logger) {
        this.repository = repository;
        this.resolver = resolver;
        this.tasks = tasks;
        this.messages = messages;
        this.cache = cache;
        this.settings = settings;
        this.logger = logger;
    }

    // ----------------------------------------------------------------- mute

    public CompletableFuture<ActionResult> mute(Actor actor, String input, int minutes, String reason) {
        return run("aplicar mute", () -> applyMute(actor, input, minutes, reason));
    }

    public CompletableFuture<RadiusResult> muteRadius(Actor actor, List<String> targets, int minutes, String reason) {
        return tasks.async(() -> {
            int muted = 0;
            List<String> failed = new ArrayList<>();
            for (String target : targets) {
                ActionResult result;
                try {
                    result = applyMute(actor, target, minutes, reason);
                } catch (Exception e) {
                    tasks.log("Falha ao mutar " + target, e);
                    result = ActionResult.fail("");
                }
                if (result.success()) {
                    muted++;
                } else {
                    failed.add(target);
                }
            }
            return new RadiusResult(muted, failed);
        });
    }

    private ActionResult applyMute(Actor actor, String input, int minutes, String rawReason) throws Exception {
        Settings s = settings.get();
        if (minutes <= 0 || minutes > s.maxMuteMinutes()) {
            return fail("mute.invalid-duration");
        }
        boolean voice = s.voiceEnabled();
        boolean chat = s.chatMuteEnabled();
        if (!voice && !chat) {
            return fail("mute.disabled");
        }

        ResolvedTarget target = resolver.resolve(input);
        if (target.isSameAs(actor)) {
            return fail("general.self-target");
        }
        if (tasks.onMain(() -> resolver.isProtected(target))) {
            return fail("general.protected-target", "player", target.name());
        }

        String reason = reason(rawReason);
        long now = System.currentTimeMillis();
        long expiresAt = now + minutes * 60_000L;
        String caseId = UUID.randomUUID().toString();

        List<PunishmentRecord> current = repository.activeForPlayer(target.name(), target.uuid(), MUTES);
        int effective = effectiveMinutes(current, expiresAt, now, s);
        long effectiveUntil = now + effective * 60_000L;
        List<String> warnings = new ArrayList<>();
        if (effective > minutes) {
            warnings.add(messages.raw("mute.longer-kept"));
        }
        Map<String, String> values = placeholders(target.name(), effective, reason);

        boolean[] outcome = tasks.onMain(() -> {
            boolean voiceOk = true;
            if (voice) {
                voiceOk = dispatch(s.voiceMuteCommand(), with(values, "perm", s.speakPermission()));
                if (s.muteListen()) {
                    voiceOk &= dispatch(s.voiceMuteCommand(), with(values, "perm", s.listenPermission()));
                }
            }
            boolean chatOk = true;
            if (chat) {
                boolean commandOk = !s.chatMuteCommand().isBlank() && dispatch(s.chatMuteCommand(), values);
                chatOk = s.blockChat() || commandOk;
            }
            return new boolean[]{voiceOk, chatOk};
        });

        List<NewPunishment> rows = new ArrayList<>();
        if (voice) {
            rows.add(row(caseId, PunishmentScope.VOICE, target, reason, actor, outcome[0], now, expiresAt));
            if (!outcome[0]) {
                warnings.add(messages.raw("mute.voice-command-failed"));
            }
        }
        if (chat) {
            rows.add(row(caseId, PunishmentScope.CHAT, target, reason, actor, outcome[1], now, expiresAt));
            if (!outcome[1]) {
                warnings.add(messages.raw("mute.chat-command-failed"));
            }
        }

        try {
            repository.insertAll(rows);
        } catch (SQLException e) {
            tasks.onMain(() -> {
                if (current.isEmpty()) {
                    executeUnmute(target.uuid(), target.name());
                } else {
                    reapply(target.uuid(), target.name(), current, now);
                }
                return null;
            });
            logger.severe("Falha ao aplicar mute: " + e.getMessage());
            return fail("general.database-error", "action", "aplicar mute");
        }

        tasks.onMain(() -> {
            if (chat && s.blockChat()) {
                cache.setChatMute(target.uuid(), target.name(), effectiveUntil);
            }
            Player online = target.uuid() == null ? null : Bukkit.getPlayer(target.uuid());
            if (online != null) {
                messages.send(online, "mute.target-notice", "minutes", effective, "reason", reason);
            }
            return null;
        });
        refreshCache();
        return ActionResult.ok(messages.format("mute.applied", "player", target.name(), "minutes", effective),
                warnings, rows.size());
    }

    // --------------------------------------------------------------- unmute

    public CompletableFuture<ActionResult> unmute(Actor actor, String input) {
        return run("remover mute", () -> {
            ResolvedTarget target = resolver.resolve(input);
            List<PunishmentRecord> revoked = repository.revokePlayerMutes(target.name(), target.uuid(), actor.name(),
                    System.currentTimeMillis());
            tasks.onMain(() -> {
                executeUnmute(target.uuid(), target.name());
                Player online = target.uuid() == null ? null : Bukkit.getPlayer(target.uuid());
                if (online != null && !revoked.isEmpty()) {
                    messages.send(online, "mute.removed-notice");
                }
                return null;
            });
            refreshCache();
            if (revoked.isEmpty()) {
                return fail("mute.none-active", "player", target.name());
            }
            return ActionResult.ok(messages.format("mute.removed", "player", target.name(), "count", revoked.size()),
                    revoked.size());
        });
    }

    public CompletableFuture<ActionResult> unmuteAll(Actor actor) {
        return run("remover todos os mutes", () -> {
            List<PunishmentRecord> revoked = repository.revokeAllMutes(actor.name(), System.currentTimeMillis());
            Map<String, PunishmentRecord> targets = new LinkedHashMap<>();
            revoked.forEach(r -> targets.putIfAbsent(r.targetKey(), r));
            tasks.onMain(() -> {
                for (PunishmentRecord record : targets.values()) {
                    executeUnmute(record.targetUuid(), record.targetName());
                    Player online = record.targetUuid() == null ? null : Bukkit.getPlayer(record.targetUuid());
                    if (online != null) {
                        messages.send(online, "mute.removed-notice");
                    }
                }
                cache.clearAllChatMutes();
                return null;
            });
            refreshCache();
            return ActionResult.ok(messages.format("mute.all-done", "count", targets.size()), revoked.size());
        });
    }

    // ----------------------------------------------------- tarefas periódicas

    /** Encerra mutes vencidos. Worker. */
    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        try {
            Set<String> cases = new LinkedHashSet<>();
            for (PunishmentRecord record : repository.due(now)) {
                if (record.scope().isMute()) {
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
                remaining.put(record, repository.activeForPlayer(record.targetName(), record.targetUuid(), MUTES));
            }
            tasks.onMain(() -> {
                for (Map.Entry<PunishmentRecord, List<PunishmentRecord>> entry : remaining.entrySet()) {
                    PunishmentRecord record = entry.getKey();
                    if (entry.getValue().isEmpty()) {
                        executeUnmute(record.targetUuid(), record.targetName());
                    } else {
                        reapply(record.targetUuid(), record.targetName(), entry.getValue(), now);
                    }
                }
                return null;
            });
            if (!expired.isEmpty()) {
                logger.info("Mutes expirados: " + expired.size() + " componente(s).");
                refreshCache();
            }
            return expired.size();
        } catch (Exception e) {
            tasks.log("Falha na limpeza de mutes", e);
            return 0;
        }
    }

    /** Na inicialização, garante que os mutes ativos continuam aplicados. Worker. */
    public int reconcileActive() {
        long now = System.currentTimeMillis();
        try {
            List<PunishmentRecord> active = repository.active(MUTES);
            Map<String, List<PunishmentRecord>> grouped = new LinkedHashMap<>();
            for (PunishmentRecord record : active) {
                if (!record.expired(now)) {
                    grouped.computeIfAbsent(record.targetKey(), k -> new ArrayList<>()).add(record);
                }
            }
            cache.rebuildChat(active);
            tasks.onMain(() -> {
                for (List<PunishmentRecord> records : grouped.values()) {
                    PunishmentRecord first = records.getFirst();
                    if (first.targetName() != null) {
                        reapply(first.targetUuid(), first.targetName(), records, now);
                    }
                }
                return null;
            });
            if (!grouped.isEmpty()) {
                logger.info("Mutes ativos reaplicados: " + grouped.size() + " jogador(es).");
            }
            return grouped.size();
        } catch (Exception e) {
            tasks.log("Falha ao reaplicar mutes ativos", e);
            return 0;
        }
    }

    /** Atualiza o bloqueio de chat com o que está no banco (útil em rede). Worker. */
    public void refreshChatState() {
        try {
            cache.rebuildChat(repository.active(PunishmentScope.CHAT));
        } catch (SQLException e) {
            logger.warning("Falha ao atualizar mutes de chat: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- helpers

    /** Thread principal. */
    private void executeUnmute(UUID uuid, String name) {
        Settings s = settings.get();
        Map<String, String> values = Placeholders.of("player", name, "name", name);
        if (s.voiceEnabled()) {
            dispatch(s.voiceUnmuteCommand(), with(values, "perm", s.speakPermission()));
            if (s.muteListen()) {
                dispatch(s.voiceUnmuteCommand(), with(values, "perm", s.listenPermission()));
            }
        }
        if (s.chatMuteEnabled()) {
            cache.clearChatMute(uuid, name);
            if (!s.chatUnmuteCommand().isBlank()) {
                dispatch(s.chatUnmuteCommand(), values);
            }
        }
    }

    /** Reaplica o mute mais longo que ainda vale. Thread principal. */
    private void reapply(UUID uuid, String name, List<PunishmentRecord> active, long now) {
        Settings s = settings.get();
        int minutes = effectiveMinutes(active, null, now, s);
        Map<String, String> values = placeholders(name, minutes, active.getFirst().reason());
        boolean hasVoice = active.stream().anyMatch(r -> r.scope() == PunishmentScope.VOICE);
        boolean hasChat = active.stream().anyMatch(r -> r.scope() == PunishmentScope.CHAT);
        if (s.voiceEnabled() && hasVoice) {
            dispatch(s.voiceMuteCommand(), with(values, "perm", s.speakPermission()));
            if (s.muteListen()) {
                dispatch(s.voiceMuteCommand(), with(values, "perm", s.listenPermission()));
            }
        }
        if (s.chatMuteEnabled() && hasChat) {
            if (s.blockChat()) {
                cache.setChatMute(uuid, name, now + minutes * 60_000L);
            }
            if (!s.chatMuteCommand().isBlank()) {
                dispatch(s.chatMuteCommand(), values);
            }
        }
    }

    static int effectiveMinutes(List<PunishmentRecord> active, Long requested, long now, Settings s) {
        long longest = requested == null ? now : requested;
        for (PunishmentRecord record : active) {
            if (record.expiresAt() == null) {
                return s.maxMuteMinutes();
            }
            longest = Math.max(longest, record.expiresAt());
        }
        long minutes = (Math.max(0, longest - now) + 59_999L) / 60_000L;
        return (int) Math.min(s.maxMuteMinutes(), Math.max(1, minutes));
    }

    private Map<String, String> placeholders(String name, int minutes, String reason) {
        return Placeholders.of("player", name, "name", name, "minutes", minutes, "time", minutes + "m",
                "reason", reason == null ? messages.defaultReason() : reason);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    /** Executa um comando configurado pelo console. Thread principal. */
    private boolean dispatch(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return true;
        }
        String command = Text.replace(template, values).trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException e) {
            logger.warning("Comando configurado falhou (" + command + "): " + e.getMessage());
            return false;
        }
    }

    private NewPunishment row(String caseId, PunishmentScope scope, ResolvedTarget target, String reason, Actor actor,
                              boolean ok, long now, long expiresAt) {
        return new NewPunishment(caseId, scope, target.uuid(), target.name(), null, reason, actor.name(),
                PunishmentService.ORIGIN, ok ? PunishmentStatus.ACTIVE : PunishmentStatus.PARTIAL, now, expiresAt,
                null, settings.get().serverId());
    }

    private String reason(String reason) {
        String value = reason == null || reason.isBlank() ? messages.defaultReason() : reason;
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private void refreshCache() {
        try {
            cache.refreshNames(repository);
        } catch (SQLException e) {
            logger.fine("Cache de nomes não atualizado: " + e.getMessage());
        }
    }

    private CompletableFuture<ActionResult> run(String action, Tasks.Job<ActionResult> job) {
        return tasks.async(job).exceptionally(error -> {
            tasks.log("Falha ao " + action, error);
            return ActionResult.fail(messages.format("general.database-error", "action", action));
        });
    }

    private ActionResult fail(String key, Object... pairs) {
        return ActionResult.fail(messages.format(key, pairs));
    }
}
