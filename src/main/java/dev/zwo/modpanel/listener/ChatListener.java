package dev.zwo.modpanel.listener;

import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.service.ActiveCache;
import dev.zwo.modpanel.util.Durations;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.function.Supplier;

/** Bloqueio de chat e de comandos de mensagem para jogadores mutados. */
public final class ChatListener implements Listener {

    private final ActiveCache cache;
    private final Messages messages;
    private final Supplier<Settings> settings;

    public ChatListener(ActiveCache cache, Messages messages, Supplier<Settings> settings) {
        this.cache = cache;
        this.messages = messages;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Long until = mutedUntil(event.getPlayer());
        if (until == null) {
            return;
        }
        event.setCancelled(true);
        messages.send(event.getPlayer(), "mute.chat-blocked",
                "remaining", Durations.format(until - System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Settings s = settings.get();
        if (s.blockedCommands().isEmpty()) {
            return;
        }
        String message = event.getMessage();
        String label = message.startsWith("/") ? message.substring(1) : message;
        int space = label.indexOf(' ');
        if (space >= 0) {
            label = label.substring(0, space);
        }
        label = label.toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        String bare = namespace >= 0 ? label.substring(namespace + 1) : label;
        if (!s.blockedCommands().contains(label) && !s.blockedCommands().contains(bare)) {
            return;
        }
        if (mutedUntil(event.getPlayer()) != null) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "mute.command-blocked");
        }
    }

    private Long mutedUntil(Player player) {
        Settings s = settings.get();
        if (!s.chatMuteEnabled() || !s.blockChat()) {
            return null;
        }
        return cache.chatMutedUntil(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }
}
