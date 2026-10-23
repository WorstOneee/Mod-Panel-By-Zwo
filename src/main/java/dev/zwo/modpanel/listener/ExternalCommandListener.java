package dev.zwo.modpanel.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Set;

/** Quando alguém usa os comandos de ban do jogo ou de outros plugins, sincroniza logo em seguida. */
public final class ExternalCommandListener implements Listener {

    private static final Set<String> RELEVANT = Set.of(
            "ban", "tempban", "ban-ip", "banip", "ipban", "tempbanip", "pardon", "unban", "pardon-ip", "unban-ip", "unbanip",
            "minecraft:ban", "minecraft:ban-ip", "minecraft:pardon", "minecraft:pardon-ip",
            "essentials:ban", "essentials:tempban", "essentials:banip", "essentials:tempbanip",
            "essentials:unban", "essentials:unbanip");

    private final Runnable trigger;

    public ExternalCommandListener(Runnable trigger) {
        this.trigger = trigger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        check(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        check(event.getCommand());
    }

    private void check(String command) {
        if (command == null) {
            return;
        }
        String text = command.trim();
        while (text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return;
        }
        String label = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (RELEVANT.contains(label)) {
            trigger.run();
        }
    }
}
