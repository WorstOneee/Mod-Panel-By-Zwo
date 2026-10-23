package dev.zwo.modpanel.config;

import dev.zwo.modpanel.model.ActionResult;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.util.Placeholders;
import dev.zwo.modpanel.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Leitura do messages.yml, com os textos do jar como padrão. */
public final class Messages {

    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private volatile YamlConfiguration yaml = new YamlConfiguration();

    public Messages(JavaPlugin plugin, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        yaml = ConfigFiles.loadWithDefaults(plugin, file, "messages.yml");
    }

    /** Texto cru da chave (listas viram várias linhas). */
    public String raw(String key) {
        YamlConfiguration current = yaml;
        if (current.isList(key)) {
            return String.join("\n", current.getStringList(key));
        }
        String value = current.getString(key);
        return value == null ? "&cMensagem ausente: " + key : value;
    }

    public List<String> lines(String key) {
        YamlConfiguration current = yaml;
        if (current.isList(key)) {
            return current.getStringList(key);
        }
        return List.of(raw(key).split("\n", -1));
    }

    public String format(String key, Object... pairs) {
        return apply(raw(key), Placeholders.of(pairs));
    }

    public String apply(String text, Map<String, String> values) {
        String out = Text.replace(text, values);
        return out.replace("{prefix}", settings.get().prefix()).replace("\\n", "\n");
    }

    public Component component(String key, Object... pairs) {
        return Text.component(format(key, pairs));
    }

    public void send(CommandSender sender, String key, Object... pairs) {
        sendText(sender, format(key, pairs));
    }

    public void sendText(CommandSender sender, String text) {
        if (sender == null || text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\n", -1)) {
            sender.sendMessage(Text.component(line));
        }
    }

    public void sendResult(CommandSender sender, ActionResult result) {
        if (result.message() != null && !result.message().isBlank()) {
            sendText(sender, format(result.success() ? "result.success" : "result.failure",
                    "message", result.message()));
        }
        for (String warning : result.warnings()) {
            sendText(sender, format("result.warning", "message", warning));
        }
    }

    public String scope(PunishmentScope scope) {
        return raw("scope." + scope.key());
    }

    public String status(PunishmentStatus status) {
        return raw("status." + status.key());
    }

    public String defaultReason() {
        return raw("general.default-reason");
    }
}
