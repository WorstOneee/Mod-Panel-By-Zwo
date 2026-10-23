package dev.zwo.modpanel.service;

import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.util.Durations;
import dev.zwo.modpanel.util.Placeholders;
import dev.zwo.modpanel.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/** Telas de ban, kicks e anúncios. Thread principal. */
public final class Notifier {

    private final Messages messages;
    private final Supplier<FileConfiguration> config;

    public Notifier(Messages messages, Supplier<FileConfiguration> config) {
        this.messages = messages;
        this.config = config;
    }

    public Component banScreen(String reason, String staff, Long expiresAt, boolean ipBan) {
        String key;
        if (ipBan) {
            key = "kick.ip";
        } else {
            key = expiresAt == null ? "kick.permanent" : "kick.temporary";
        }
        String remaining = expiresAt == null
                ? messages.raw("time.permanent")
                : Durations.format(expiresAt - System.currentTimeMillis());
        return messages.component(key, "reason", reason, "staff", staff, "remaining", remaining);
    }

    public void kick(Player player, Component screen) {
        if (player != null && player.isOnline()) {
            player.kick(screen);
        }
    }

    public void broadcastBan(String player, String reason, String staff, Long expiresAt) {
        boolean temporary = expiresAt != null;
        String path = temporary ? "broadcasts.temp-ban" : "broadcasts.perm-ban";
        FileConfiguration c = config.get();
        if (!c.getBoolean(path + ".enabled", true)) {
            return;
        }
        String duration = temporary ? Durations.format(expiresAt - System.currentTimeMillis()) : messages.raw("time.permanent");
        Map<String, String> values = Placeholders.of("player", player, "reason", reason, "staff", staff,
                "duration", duration);

        for (String line : c.getStringList(path + ".chat")) {
            Bukkit.broadcast(Text.component(messages.apply(line, values)));
        }

        if (c.getBoolean(path + ".title.enabled", true)) {
            Component title = Text.component(messages.apply(c.getString(path + ".title.title", "&c&lBANIDO"), values));
            Component subtitle = Text.component(messages.apply(c.getString(path + ".title.subtitle", "&f{player}"), values));
            Title.Times times = Title.Times.times(
                    ticks(c.getInt(path + ".title.fade-in", 10)),
                    ticks(c.getInt(path + ".title.stay", 100)),
                    ticks(c.getInt(path + ".title.fade-out", 20)));
            Title shown = Title.title(title, subtitle, times);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.showTitle(shown);
            }
        }
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }
}
