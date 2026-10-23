package dev.zwo.modpanel.listener;

import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.service.Notifier;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Ips;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Confere o banco no login. Cobre o intervalo entre um ban feito em outro servidor da rede
 * e a próxima sincronização da banlist local.
 */
public final class LoginListener implements Listener {

    private final PunishmentRepository repository;
    private final Notifier notifier;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public LoginListener(PunishmentRepository repository, Notifier notifier, Supplier<Settings> settings, Logger logger) {
        this.repository = repository;
        this.notifier = notifier;
        this.settings = settings;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!settings.get().loginCheck() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String ip = Ips.normalize(event.getAddress());
        try {
            Optional<PunishmentRecord> block = repository.activeLoginBlock(event.getUniqueId(), ip, System.currentTimeMillis());
            block.ifPresent(record -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    notifier.banScreen(record.reason(), record.actor(), record.expiresAt(),
                            record.scope() == PunishmentScope.IP)));
        } catch (SQLException e) {
            // sem banco a banlist do Paper continua valendo
            logger.warning("Não foi possível consultar bans no login de " + event.getName() + ": " + e.getMessage());
        }
    }
}
