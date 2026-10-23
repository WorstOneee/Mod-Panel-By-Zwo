package dev.zwo.modpanel.migration;

import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.storage.SqliteImporter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

/** Traz configuração e dados da pasta antiga (plugins/SerpensModPanel) na primeira inicialização. */
public final class SerpensUpgrade {

    public static final String META_KEY = "import.serpens";
    private static final String OLD_FOLDER = "SerpensModPanel";

    private SerpensUpgrade() {
    }

    private static File oldFolder(JavaPlugin plugin) {
        return new File(plugin.getDataFolder().getParentFile(), OLD_FOLDER);
    }

    /**
     * Copia o config.yml antigo quando ainda não existe um config do ModPanel.
     * As chaves continuam compatíveis; as novas são completadas depois.
     */
    public static void copyOldConfig(JavaPlugin plugin) {
        File target = new File(plugin.getDataFolder(), "config.yml");
        File source = new File(oldFolder(plugin), "config.yml");
        if (target.exists() || !source.isFile()) {
            return;
        }
        Logger logger = plugin.getLogger();
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new IOException("Não foi possível criar " + plugin.getDataFolder());
            }
            String text = Files.readString(source.toPath(), StandardCharsets.UTF_8)
                    .replace("&8Serpens ModPanel", "&8ModPanel");
            Files.writeString(target.toPath(), text, StandardCharsets.UTF_8);
            copyOldMessages(plugin, YamlConfiguration.loadConfiguration(source));
            logger.info("config.yml do SerpensModPanel copiado para a pasta do ModPanel.");
        } catch (IOException e) {
            logger.warning("Não foi possível copiar o config.yml antigo: " + e.getMessage());
        }
    }

    private static void copyOldMessages(JavaPlugin plugin, YamlConfiguration old) throws IOException {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (file.exists()) {
            return;
        }
        String noPermission = old.getString("messages.no-permission");
        String onlyPlayer = old.getString("messages.only-player");
        if (noPermission == null && onlyPlayer == null) {
            return;
        }
        plugin.saveResource("messages.yml", false);
        YamlConfiguration messages = YamlConfiguration.loadConfiguration(file);
        if (noPermission != null) {
            messages.set("general.no-permission", noPermission);
        }
        if (onlyPlayer != null) {
            messages.set("general.only-player", onlyPlayer);
        }
        messages.save(file);
    }

    /** Importa o histórico do SerpensModPanel para o banco atual, uma única vez. Worker. */
    public static void importData(JavaPlugin plugin, PunishmentRepository repository, String serverId) throws Exception {
        if (repository.meta(META_KEY).isPresent()) {
            return;
        }
        Logger logger = plugin.getLogger();
        File old = oldFolder(plugin);
        File oldDb = new File(old, "modpanel.db");
        long now = System.currentTimeMillis();

        if (oldDb.isFile()) {
            if (repository.countPunishments() > 0) {
                logger.info("Banco do SerpensModPanel encontrado, mas o banco atual já tem dados; importação ignorada.");
                repository.setMeta(META_KEY, "skipped:" + now);
                return;
            }
            SqliteImporter.Result result = new SqliteImporter(repository, logger)
                    .importFrom(oldDb, SqliteImporter.Layout.SERPENS, "", serverId);
            repository.setMeta(META_KEY, "ok:" + now + ":punishments=" + result.punishments());
            logger.info("Histórico do SerpensModPanel importado: " + result.punishments() + " punições, "
                    + result.identities() + " perfis.");
            return;
        }

        LegacyYamlMigrator yaml = new LegacyYamlMigrator(repository, new File(plugin.getDataFolder(), "backups"),
                serverId, logger);
        for (File candidate : new File[]{new File(old, "data.yml"), new File(plugin.getDataFolder(), "data.yml")}) {
            if (candidate.isFile()) {
                yaml.migrate(candidate);
                break;
            }
        }
        repository.setMeta(META_KEY, "none:" + now);
    }
}
