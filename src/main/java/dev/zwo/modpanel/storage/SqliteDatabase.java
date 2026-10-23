package dev.zwo.modpanel.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/** SQLite com uma conexão única protegida por lock. */
public final class SqliteDatabase extends Database {

    private final File file;
    private final Logger logger;
    private Connection connection;

    public SqliteDatabase(File file, Tables tables, Logger logger) {
        super(Dialect.SQLITE, tables);
        this.file = file;
        this.logger = logger;
    }

    @Override
    public synchronized void open() throws Exception {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar " + parent);
        }
        Class.forName("org.sqlite.JDBC");
        connect();
        logger.info("Banco SQLite aberto em " + file.getName() + ".");
    }

    private void connect() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    @Override
    protected synchronized <T> T withConnection(SqlFunction<Connection, T> work) throws SQLException {
        if (connection == null) {
            throw new SQLException("Banco de dados fechado");
        }
        if (connection.isClosed()) {
            connect();
        }
        return work.apply(connection);
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warning("Falha ao fechar SQLite: " + e.getMessage());
        } finally {
            connection = null;
        }
    }

    @Override
    public String describe() {
        return "SQLite (" + file.getName() + ")";
    }
}
