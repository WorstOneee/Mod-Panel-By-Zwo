package dev.zwo.modpanel.storage;

import dev.zwo.modpanel.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;
import java.util.UUID;

/** Roda quando MODPANEL_MYSQL_HOST está definido (o workflow do GitHub sobe um MySQL para isso). */
@EnabledIfEnvironmentVariable(named = "MODPANEL_MYSQL_HOST", matches = ".+")
class MysqlRepositoryTest extends RepositoryContract {

    @Override
    protected Database createDatabase() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "mysql");
        config.set("storage.mysql.host", System.getenv("MODPANEL_MYSQL_HOST"));
        config.set("storage.mysql.port", Integer.parseInt(System.getenv().getOrDefault("MODPANEL_MYSQL_PORT", "3306")));
        config.set("storage.mysql.database", System.getenv().getOrDefault("MODPANEL_MYSQL_DATABASE", "modpanel"));
        config.set("storage.mysql.username", System.getenv().getOrDefault("MODPANEL_MYSQL_USER", "root"));
        config.set("storage.mysql.password", System.getenv().getOrDefault("MODPANEL_MYSQL_PASSWORD", ""));
        config.set("storage.mysql.pool.maximum-size", 2);
        Settings settings = Settings.load(config, LOG);

        // prefixo próprio por teste para não misturar dados
        String prefix = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
        return new MysqlDatabase(settings, Tables.withPrefix(prefix), LOG);
    }

    @Override
    protected void dropTables() throws Exception {
        Tables t = database.tables();
        database.query(c -> {
            try (Statement st = c.createStatement()) {
                for (String table : new String[]{t.meta(), t.identities(), t.names(), t.ips(), t.punishments(), t.mirror()}) {
                    st.executeUpdate("DROP TABLE IF EXISTS " + table);
                }
            }
            return null;
        });
    }
}
