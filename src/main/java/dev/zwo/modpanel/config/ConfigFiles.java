package dev.zwo.modpanel.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class ConfigFiles {

    private ConfigFiles() {
    }

    /**
     * Carrega um yml da pasta do plugin usando o arquivo do jar como padrão.
     * Chaves novas que ainda não existem no arquivo do usuário são gravadas nele.
     */
    public static YamlConfiguration loadWithDefaults(JavaPlugin plugin, File file, String resource) {
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = bundled(plugin, resource);
        if (defaults == null) {
            return yaml;
        }
        yaml.setDefaults(defaults);

        boolean missing = false;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !yaml.contains(key, true)) {
                yaml.set(key, defaults.get(key));
                missing = true;
            }
        }
        if (missing) {
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Não foi possível atualizar " + file.getName(), e);
            }
        }
        return yaml;
    }

    static YamlConfiguration bundled(JavaPlugin plugin, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
