package dev.zwo.modpanel.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Acesso ao banco. Todas as chamadas devem sair da thread principal. */
public abstract class Database implements AutoCloseable {

    private final Dialect dialect;
    private final Tables tables;

    protected Database(Dialect dialect, Tables tables) {
        this.dialect = dialect;
        this.tables = tables;
    }

    public abstract void open() throws Exception;

    @Override
    public abstract void close();

    /** Descrição curta para logs e /mp status. */
    public abstract String describe();

    protected abstract <T> T withConnection(SqlFunction<Connection, T> work) throws SQLException;

    public Dialect dialect() {
        return dialect;
    }

    public Tables tables() {
        return tables;
    }

    public <T> T query(SqlFunction<Connection, T> work) throws SQLException {
        return withConnection(work);
    }

    public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
        return withConnection(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // a conexão pode ter caído; o pool descarta
                }
            }
        });
    }
}
