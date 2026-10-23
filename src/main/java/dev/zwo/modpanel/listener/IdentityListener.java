package dev.zwo.modpanel.listener;

import dev.zwo.modpanel.service.Tasks;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Ips;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.logging.Logger;

/** Guarda UUID, nome e IP de quem entra, base para bans de jogadores offline. */
public final class IdentityListener implements Listener {

    private final PunishmentRepository repository;
    private final Tasks tasks;
    private final Logger logger;

    public IdentityListener(PunishmentRepository repository, Tasks tasks, Logger logger) {
        this.repository = repository;
        this.tasks = tasks;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
    }

    public void record(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = Ips.normalize(player.getAddress());
        long now = System.currentTimeMillis();
        tasks.async(() -> {
            repository.upsertIdentity(uuid, name, ip, now);
            return null;
        }).exceptionally(error -> {
            logger.severe("Falha ao registrar identidade de " + name + ": " + Tasks.unwrap(error).getMessage());
            return null;
        });
    }
}
