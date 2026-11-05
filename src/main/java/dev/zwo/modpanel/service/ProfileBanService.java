package dev.zwo.modpanel.service;

import dev.zwo.modpanel.api.ProfileBanApi;
import dev.zwo.modpanel.api.ProfileBanRequest;
import dev.zwo.modpanel.api.ProfileBanResult;
import dev.zwo.modpanel.api.ProfileBanResult.Status;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.paper.BanGateway;
import dev.zwo.modpanel.storage.PunishmentRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Implementação da {@link ProfileBanApi}: ban temporário só por perfil, para automações.
 *
 * <p>Usa o mesmo banco, a mesma banlist e o mesmo histórico dos comandos da staff, mas nunca toca
 * em IP. Os comandos manuais ({@code /mpban}, {@code /mptempban}...) não mudam.</p>
 */
public final class ProfileBanService implements ProfileBanApi {

    static final String ORIGIN = "API";
    private static final String KEY_PREFIX = "api:";

    private final PunishmentRepository repository;
    private final BanGateway gateway;
    private final PunishmentService punishments;
    private final Tasks tasks;
    private final Notifier notifier;
    private final ActiveCache cache;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public ProfileBanService(PunishmentRepository repository, BanGateway gateway, PunishmentService punishments,
                             Tasks tasks, Notifier notifier, ActiveCache cache, Supplier<Settings> settings,
                             Logger logger) {
        this.repository = repository;
        this.gateway = gateway;
        this.punishments = punishments;
        this.tasks = tasks;
        this.notifier = notifier;
        this.cache = cache;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public CompletableFuture<ProfileBanResult> tempBanProfile(ProfileBanRequest request) {
        if (request == null) {
            return done(ProfileBanResult.of(Status.REJECTED, null, "pedido vazio"));
        }
        Duration duration = request.duration();
        if (duration.isZero() || duration.isNegative()) {
            return done(ProfileBanResult.of(Status.REJECTED, request.uuid(), "duração inválida"));
        }
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException e) {
            return done(ProfileBanResult.of(Status.REJECTED, request.uuid(), "duração grande demais"));
        }
        if (millis > settings.get().maxTempBanMillis()) {
            return done(ProfileBanResult.of(Status.REJECTED, request.uuid(),
                    "duração acima de limits.max-tempban-days do ModPanel"));
        }
        if (request.requestId().isBlank() || request.requestId().length() > 150) {
            return done(ProfileBanResult.of(Status.REJECTED, request.uuid(), "requestId inválido"));
        }
        return guard(request.uuid(), tasks.async(() -> apply(request, millis)));
    }

    @Override
    public CompletableFuture<ProfileBanResult> activeProfileBan(UUID uuid) {
        if (uuid == null) {
            return done(ProfileBanResult.of(Status.REJECTED, null, "uuid vazio"));
        }
        return guard(uuid, tasks.async(() -> {
            List<PunishmentRecord> active = repository.activeForPlayer(uuid.toString(), uuid, EnumSet.of(PunishmentScope.PROFILE));
            if (active.isEmpty()) {
                return ProfileBanResult.of(Status.NOT_BANNED, uuid, "nenhum ban de perfil ativo");
            }
            PunishmentRecord r = active.getFirst();
            boolean nativeBan = tasks.onMain(() -> gateway.isProfileBanned(uuid, r.targetName()));
            return result(Status.ALREADY_BANNED, uuid, r, nativeBan,
                    nativeBan ? "ban de perfil ativo" : "ativo no banco, mas ausente da banlist do Paper");
        }));
    }

    // ------------------------------------------------------------------ worker

    private ProfileBanResult apply(ProfileBanRequest req, long millis) throws Exception {
        UUID uuid = req.uuid();
        String legacyKey = KEY_PREFIX + req.requestId();
        String actor = blankTo(req.actor(), "API");
        String reason = punishments.sanitizeReason(req.reason());

        // Nome: jogador online > identidade conhecida > nome do pedido > UUID
        String onlineName = tasks.onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            return p == null ? null : p.getName();
        });
        String name = onlineName;
        if (name == null) {
            name = repository.findIdentity(uuid).map(i -> i.name()).orElse(null);
        }
        if (name == null) {
            name = blankTo(req.name(), uuid.toString());
        }
        String targetName = name;

        if (tasks.onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            return p != null && p.hasPermission(settings.get().permission("protected"));
        })) {
            return ProfileBanResult.of(Status.REJECTED, uuid, "alvo protegido (permissão protected do ModPanel)");
        }

        // Mesmo pedido repetido: não cria outro registro.
        if (repository.hasLegacyKey(legacyKey)) {
            return existing(uuid, targetName, "pedido " + req.requestId() + " já registrado");
        }
        List<PunishmentRecord> previous =
                repository.activeForPlayer(targetName, uuid, EnumSet.of(PunishmentScope.PROFILE));
        if (!previous.isEmpty()) {
            return existing(uuid, targetName, "o perfil já tem um ban ativo; nada foi criado");
        }

        long now = System.currentTimeMillis();
        long expiresAt = Math.addExact(now, millis);
        String caseId = UUID.randomUUID().toString();
        Settings s = settings.get();

        boolean nativeOk = tasks.onMain(() -> {
            try {
                return gateway.banProfile(uuid, targetName, reason, actor, expiresAt);
            } catch (RuntimeException e) {
                logger.warning("API: falha no ban de perfil de " + targetName + ": " + e.getMessage());
                return false;
            }
        });
        NewPunishment row = new NewPunishment(caseId, PunishmentScope.PROFILE, uuid, targetName, null, reason, actor,
                ORIGIN, nativeOk ? PunishmentStatus.ACTIVE : PunishmentStatus.PARTIAL, now, expiresAt, legacyKey,
                s.serverId());
        if (!nativeOk) {
            try {
                repository.insertAll(List.of(row));
            } catch (SQLException ignored) {
                // registro parcial é só informativo
            }
            return ProfileBanResult.of(Status.FAILED, uuid, "a banlist do Paper recusou o ban");
        }

        List<PunishmentRecord> saved;
        try {
            saved = repository.insertAndMirror(List.of(row), s.serverId(), now);
        } catch (SQLException e) {
            tasks.onMain(() -> {
                punishments.restoreProfile(uuid, targetName, List.of());
                return null;
            });
            logger.severe("API: falha ao salvar o ban de " + targetName + "; ban nativo revertido: " + e.getMessage());
            return ProfileBanResult.of(Status.FAILED, uuid, "falha no banco; o ban nativo foi revertido");
        }
        if (saved.isEmpty()) {
            // Outro pedido com o mesmo id gravou primeiro.
            return existing(uuid, targetName, "pedido " + req.requestId() + " já registrado");
        }
        PunishmentRecord record = saved.getFirst();

        // Conferência: registro ativo no banco e perfil na banlist.
        boolean inDb = repository.activeForPlayer(targetName, uuid, EnumSet.of(PunishmentScope.PROFILE)).stream()
                .anyMatch(r -> r.id() == record.id());
        boolean inBanlist = tasks.onMain(() -> {
            boolean banned = gateway.isProfileBanned(uuid, targetName);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                Component screen = notifier.banScreen(reason, actor, expiresAt, false);
                notifier.kick(online, screen);
            }
            notifier.broadcastBan(targetName, reason, actor, expiresAt);
            return banned;
        });
        try {
            cache.refreshNames(repository);
        } catch (SQLException e) {
            logger.fine("Cache de nomes não atualizado: " + e.getMessage());
        }
        boolean verified = inDb && inBanlist;
        logger.info("API: ban de perfil de " + targetName + " (" + uuid + ") por " + actor + " até " + expiresAt
                + " — registro #" + record.id() + (verified ? " conferido" : " NÃO conferido")
                + " (sem IP).");
        return result(Status.APPLIED, uuid, record, verified, verified
                ? "ban de perfil aplicado e conferido (banco e banlist)"
                : "ban gravado, mas a conferência falhou (banco=" + inDb + ", banlist=" + inBanlist + ")");
    }

    private ProfileBanResult existing(UUID uuid, String name, String why) throws Exception {
        List<PunishmentRecord> active = repository.activeForPlayer(name, uuid, EnumSet.of(PunishmentScope.PROFILE));
        Optional<PunishmentRecord> first = active.stream().findFirst();
        if (first.isEmpty()) {
            return ProfileBanResult.of(Status.FAILED, uuid, why + ", mas não há ban de perfil ativo");
        }
        boolean nativeBan = tasks.onMain(() -> gateway.isProfileBanned(uuid, name));
        return result(Status.ALREADY_BANNED, uuid, first.get(), nativeBan, why);
    }

    private static ProfileBanResult result(Status status, UUID uuid, PunishmentRecord r, boolean verified, String msg) {
        return new ProfileBanResult(status, uuid, r.id(), r.caseId(), r.expiresAt(), verified, false, msg);
    }

    private CompletableFuture<ProfileBanResult> guard(UUID uuid, CompletableFuture<ProfileBanResult> future) {
        return future.exceptionally(error -> {
            Throwable cause = Tasks.unwrap(error);
            tasks.log("API: falha no ban de perfil", cause);
            return ProfileBanResult.of(Status.FAILED, uuid, "erro interno: " + cause);
        });
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static CompletableFuture<ProfileBanResult> done(ProfileBanResult r) {
        return CompletableFuture.completedFuture(r);
    }
}
