package dev.zwo.modpanel.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.zwo.modpanel.config.Settings;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/** MySQL/MariaDB através de um pool HikariCP. */
public final class MysqlDatabase extends Database {

    private final Settings settings;
    private final Logger logger;
    private HikariDataSource dataSource;

    public MysqlDatabase(Settings settings, Tables tables, Logger logger) {
        super(Dialect.MYSQL, tables);
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void open() throws Exception {
        HikariConfig config = new HikariConfig();
        String url = "jdbc:mysql://" + settings.mysqlHost() + ":" + settings.mysqlPort() + "/" + settings.mysqlDatabase();
        String properties = settings.mysqlProperties();
        if (properties != null && !properties.isBlank()) {
            url += (properties.startsWith("?") ? "" : "?") + properties;
        }
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(settings.mysqlUser());
        config.setPassword(settings.mysqlPassword());
        config.setPoolName("ModPanel-MySQL");
        config.setMaximumPoolSize(settings.poolMaxSize());
        config.setMinimumIdle(settings.poolMinIdle());
        config.setConnectionTimeout(settings.poolConnectionTimeout());
        config.setMaxLifetime(settings.poolMaxLifetime());
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");

        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException e) {
            dataSource.close();
            dataSource = null;
            throw e;
        }
        logger.info("Conectado ao MySQL em " + settings.mysqlHost() + ":" + settings.mysqlPort()
                + "/" + settings.mysqlDatabase() + ".");
    }

    @Override
    protected <T> T withConnection(SqlFunction<Connection, T> work) throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("Banco de dados fechado");
        }
        try (Connection connection = source.getConnection()) {
            return work.apply(connection);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    @Override
    public String describe() {
        return "MySQL (" + settings.mysqlHost() + "/" + settings.mysqlDatabase() + ")";
    }
}
