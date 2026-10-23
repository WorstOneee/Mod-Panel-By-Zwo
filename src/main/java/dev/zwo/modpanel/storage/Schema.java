package dev.zwo.modpanel.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Criação das tabelas para SQLite e MySQL. */
public final class Schema {

    public static final int VERSION = 1;

    private Schema() {
    }

    public static void create(Database database) throws SQLException {
        List<String> statements = database.dialect() == Dialect.MYSQL
                ? mysql(database.tables())
                : sqlite(database.tables());
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.executeUpdate(sql);
                }
            }
            if (currentVersion(connection, database.tables()) == 0) {
                writeVersion(connection, database);
            }
            return null;
        });
    }

    private static int currentVersion(Connection connection, Tables t) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT meta_value FROM " + t.meta() + " WHERE meta_key='schema_version'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeVersion(Connection connection, Database database) throws SQLException {
        String sql = database.dialect() == Dialect.MYSQL
                ? "INSERT INTO " + database.tables().meta() + "(meta_key,meta_value) VALUES('schema_version',?) "
                  + "ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)"
                : "INSERT INTO " + database.tables().meta() + "(meta_key,meta_value) VALUES('schema_version',?) "
                  + "ON CONFLICT(meta_key) DO UPDATE SET meta_value=excluded.meta_value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(VERSION));
            ps.executeUpdate();
        }
    }

    private static List<String> sqlite(Tables t) {
        String p = t.prefix();
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.meta() + " ("
                        + "meta_key TEXT PRIMARY KEY,"
                        + "meta_value TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + t.identities() + " ("
                        + "uuid TEXT PRIMARY KEY,"
                        + "current_name TEXT NOT NULL,"
                        + "name_key TEXT NOT NULL,"
                        + "last_ip TEXT,"
                        + "updated_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_identities_name ON " + t.identities() + "(name_key)",
                "CREATE TABLE IF NOT EXISTS " + t.names() + " ("
                        + "uuid TEXT NOT NULL,"
                        + "name TEXT NOT NULL,"
                        + "name_key TEXT NOT NULL,"
                        + "last_seen INTEGER NOT NULL,"
                        + "PRIMARY KEY (uuid, name_key))",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_names_key ON " + t.names() + "(name_key)",
                "CREATE TABLE IF NOT EXISTS " + t.ips() + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT,"
                        + "player_name TEXT NOT NULL,"
                        + "name_key TEXT NOT NULL,"
                        + "ip TEXT NOT NULL,"
                        + "first_seen INTEGER NOT NULL,"
                        + "last_seen INTEGER NOT NULL,"
                        + "UNIQUE (name_key, ip))",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_ips_ip ON " + t.ips() + "(ip)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_ips_name ON " + t.ips() + "(name_key, last_seen)",
                "CREATE TABLE IF NOT EXISTS " + t.punishments() + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "case_id TEXT NOT NULL,"
                        + "scope TEXT NOT NULL,"
                        + "target_uuid TEXT,"
                        + "target_name TEXT,"
                        + "target_key TEXT,"
                        + "target_ip TEXT,"
                        + "reason TEXT NOT NULL,"
                        + "actor TEXT NOT NULL,"
                        + "origin TEXT NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL,"
                        + "expires_at INTEGER,"
                        + "revoked_at INTEGER,"
                        + "revoked_by TEXT,"
                        + "legacy_key TEXT UNIQUE,"
                        + "server_id TEXT)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_case ON " + t.punishments() + "(case_id)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_status_scope ON " + t.punishments() + "(status, scope)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_status_expires ON " + t.punishments() + "(status, expires_at)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_target_key ON " + t.punishments() + "(target_key)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_target_uuid ON " + t.punishments() + "(target_uuid)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_p_target_ip ON " + t.punishments() + "(target_ip)",
                "CREATE TABLE IF NOT EXISTS " + t.mirror() + " ("
                        + "server_id TEXT NOT NULL,"
                        + "punishment_id INTEGER NOT NULL,"
                        + "applied_at INTEGER NOT NULL,"
                        + "PRIMARY KEY (server_id, punishment_id))"
        );
    }

    private static List<String> mysql(Tables t) {
        String engine = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.meta() + " ("
                        + "meta_key VARCHAR(128) NOT NULL PRIMARY KEY,"
                        + "meta_value TEXT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS " + t.identities() + " ("
                        + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                        + "current_name VARCHAR(64) NOT NULL,"
                        + "name_key VARCHAR(64) NOT NULL,"
                        + "last_ip VARCHAR(64) NULL,"
                        + "updated_at BIGINT NOT NULL,"
                        + "INDEX idx_identities_name (name_key))" + engine,
                "CREATE TABLE IF NOT EXISTS " + t.names() + " ("
                        + "uuid VARCHAR(36) NOT NULL,"
                        + "name VARCHAR(64) NOT NULL,"
                        + "name_key VARCHAR(64) NOT NULL,"
                        + "last_seen BIGINT NOT NULL,"
                        + "PRIMARY KEY (uuid, name_key),"
                        + "INDEX idx_names_key (name_key))" + engine,
                "CREATE TABLE IF NOT EXISTS " + t.ips() + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "uuid VARCHAR(36) NULL,"
                        + "player_name VARCHAR(64) NOT NULL,"
                        + "name_key VARCHAR(64) NOT NULL,"
                        + "ip VARCHAR(64) NOT NULL,"
                        + "first_seen BIGINT NOT NULL,"
                        + "last_seen BIGINT NOT NULL,"
                        + "UNIQUE KEY uq_ips_name_ip (name_key, ip),"
                        + "INDEX idx_ips_ip (ip),"
                        + "INDEX idx_ips_name (name_key, last_seen))" + engine,
                "CREATE TABLE IF NOT EXISTS " + t.punishments() + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "case_id VARCHAR(64) NOT NULL,"
                        + "scope VARCHAR(16) NOT NULL,"
                        + "target_uuid VARCHAR(36) NULL,"
                        + "target_name VARCHAR(64) NULL,"
                        + "target_key VARCHAR(64) NULL,"
                        + "target_ip VARCHAR(64) NULL,"
                        + "reason VARCHAR(512) NOT NULL,"
                        + "actor VARCHAR(64) NOT NULL,"
                        + "origin VARCHAR(32) NOT NULL,"
                        + "status VARCHAR(24) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "expires_at BIGINT NULL,"
                        + "revoked_at BIGINT NULL,"
                        + "revoked_by VARCHAR(64) NULL,"
                        + "legacy_key VARCHAR(191) NULL,"
                        + "server_id VARCHAR(64) NULL,"
                        + "UNIQUE KEY uq_p_legacy (legacy_key),"
                        + "INDEX idx_p_case (case_id),"
                        + "INDEX idx_p_status_scope (status, scope),"
                        + "INDEX idx_p_status_expires (status, expires_at),"
                        + "INDEX idx_p_target_key (target_key),"
                        + "INDEX idx_p_target_uuid (target_uuid),"
                        + "INDEX idx_p_target_ip (target_ip))" + engine,
                "CREATE TABLE IF NOT EXISTS " + t.mirror() + " ("
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "punishment_id BIGINT NOT NULL,"
                        + "applied_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (server_id, punishment_id))" + engine
        );
    }
}
