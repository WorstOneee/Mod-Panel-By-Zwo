package dev.zwo.modpanel.storage;

import dev.zwo.modpanel.model.Names;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Copia dados de um arquivo SQLite para o banco atual. Usado para trazer o histórico do
 * SerpensModPanel e para passar do SQLite do ModPanel para o MySQL.
 */
public final class SqliteImporter {

    public enum Layout {
        /** Banco do SerpensModPanel 2.x (tabelas sem prefixo). */
        SERPENS,
        /** Banco SQLite do próprio ModPanel. */
        MODPANEL
    }

    public record Result(int identities, int names, int ips, int punishments, int mirrors) {
    }

    private final PunishmentRepository repository;
    private final Logger logger;

    public SqliteImporter(PunishmentRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    /** Só importa em banco sem punições, preservando os IDs originais. */
    public Result importFrom(File source, Layout layout, String tablePrefix, String serverId) throws Exception {
        if (!source.isFile()) {
            throw new IllegalArgumentException("Arquivo não encontrado: " + source);
        }
        if (repository.countPunishments() > 0) {
            throw new IllegalStateException("O banco de destino já possui punições");
        }
        Class.forName("org.sqlite.JDBC");

        Path workDir = Files.createTempDirectory("modpanel-import");
        Path copy = workDir.resolve("source.db");
        try {
            Files.copy(source.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : new String[]{"-wal", "-shm"}) {
                File extra = new File(source.getPath() + suffix);
                if (extra.isFile()) {
                    Files.copy(extra.toPath(), workDir.resolve("source.db" + suffix), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Tables from = layout == Layout.SERPENS ? Tables.withPrefix("") : Tables.withPrefix(tablePrefix);
            try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + copy.toAbsolutePath())) {
                return repository.database().transaction(dst -> copyAll(src, dst, from, layout, serverId));
            }
        } finally {
            deleteQuietly(workDir);
        }
    }

    private Result copyAll(Connection src, Connection dst, Tables from, Layout layout, String serverId) throws SQLException {
        Tables to = repository.database().tables();
        boolean mysql = repository.database().dialect() == Dialect.MYSQL;
        Set<String> tables = tableNames(src);

        int identities = 0;
        if (tables.contains(from.identities().toLowerCase(Locale.ROOT))) {
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, current_name, last_ip, updated_at FROM " + from.identities());
                 PreparedStatement ps = dst.prepareStatement((mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
                         + to.identities() + "(uuid,current_name,name_key,last_ip,updated_at) VALUES(?,?,?,?,?)")) {
                while (rs.next()) {
                    UUID uuid = PunishmentRepository.parseUuid(rs.getString(1));
                    String name = rs.getString(2);
                    if (uuid == null || name == null || name.isBlank()) {
                        continue;
                    }
                    ps.setString(1, uuid.toString());
                    ps.setString(2, PunishmentRepository.limit(name, 64));
                    ps.setString(3, PunishmentRepository.limit(Names.key(name), 64));
                    PunishmentRepository.nullable(ps, 4, PunishmentRepository.limit(rs.getString(3), 64));
                    ps.setLong(5, rs.getLong(4));
                    identities += ps.executeUpdate();
                }
            }
        }

        int names = 0;
        if (tables.contains(from.names().toLowerCase(Locale.ROOT))) {
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, name, last_seen FROM " + from.names());
                 PreparedStatement ps = dst.prepareStatement((mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
                         + to.names() + "(uuid,name,name_key,last_seen) VALUES(?,?,?,?)")) {
                while (rs.next()) {
                    UUID uuid = PunishmentRepository.parseUuid(rs.getString(1));
                    String name = rs.getString(2);
                    if (uuid == null || name == null || name.isBlank()) {
                        continue;
                    }
                    ps.setString(1, uuid.toString());
                    ps.setString(2, PunishmentRepository.limit(name, 64));
                    ps.setString(3, PunishmentRepository.limit(Names.key(name), 64));
                    ps.setLong(4, rs.getLong(3));
                    names += ps.executeUpdate();
                }
            }
        }

        int ips = 0;
        if (tables.contains(from.ips().toLowerCase(Locale.ROOT))) {
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, player_name, ip, first_seen, last_seen FROM " + from.ips());
                 PreparedStatement ps = dst.prepareStatement((mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
                         + to.ips() + "(uuid,player_name,name_key,ip,first_seen,last_seen) VALUES(?,?,?,?,?,?)")) {
                while (rs.next()) {
                    String name = rs.getString(2);
                    String ip = rs.getString(3);
                    if (name == null || name.isBlank() || ip == null || ip.isBlank()) {
                        continue;
                    }
                    UUID uuid = PunishmentRepository.parseUuid(rs.getString(1));
                    PunishmentRepository.nullable(ps, 1, uuid == null ? null : uuid.toString());
                    ps.setString(2, PunishmentRepository.limit(name, 64));
                    ps.setString(3, PunishmentRepository.limit(Names.key(name), 64));
                    ps.setString(4, PunishmentRepository.limit(ip, 64));
                    ps.setLong(5, rs.getLong(4));
                    ps.setLong(6, rs.getLong(5));
                    ips += ps.executeUpdate();
                }
            }
        }

        int punishments = 0;
        if (tables.contains(from.punishments().toLowerCase(Locale.ROOT))) {
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM " + from.punishments() + " ORDER BY id");
                 PreparedStatement ps = dst.prepareStatement("INSERT INTO " + to.punishments()
                         + "(id,case_id,scope,target_uuid,target_name,target_key,target_ip,reason,actor,origin,status,"
                         + "created_at,expires_at,revoked_at,revoked_by,legacy_key,server_id) "
                         + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                boolean hasServer = hasColumn(rs, "server_id");
                while (rs.next()) {
                    String name = rs.getString("target_name");
                    ps.setLong(1, rs.getLong("id"));
                    ps.setString(2, PunishmentRepository.limit(rs.getString("case_id"), 64));
                    ps.setString(3, rs.getString("scope"));
                    PunishmentRepository.nullable(ps, 4, rs.getString("target_uuid"));
                    PunishmentRepository.nullable(ps, 5, PunishmentRepository.limit(name, 64));
                    PunishmentRepository.nullable(ps, 6, name == null ? null : PunishmentRepository.limit(Names.key(name), 64));
                    PunishmentRepository.nullable(ps, 7, PunishmentRepository.limit(rs.getString("target_ip"), 64));
                    ps.setString(8, PunishmentRepository.limit(orDefault(rs.getString("reason"), "Sem motivo informado"), 500));
                    ps.setString(9, PunishmentRepository.limit(orDefault(rs.getString("actor"), "Servidor"), 64));
                    ps.setString(10, PunishmentRepository.limit(orDefault(rs.getString("origin"), "MODPANEL"), 32));
                    ps.setString(11, rs.getString("status"));
                    ps.setLong(12, rs.getLong("created_at"));
                    long expires = rs.getLong("expires_at");
                    PunishmentRepository.nullableLong(ps, 13, rs.wasNull() ? null : expires);
                    long revoked = rs.getLong("revoked_at");
                    PunishmentRepository.nullableLong(ps, 14, rs.wasNull() ? null : revoked);
                    PunishmentRepository.nullable(ps, 15, PunishmentRepository.limit(rs.getString("revoked_by"), 64));
                    PunishmentRepository.nullable(ps, 16, PunishmentRepository.limit(rs.getString("legacy_key"), 191));
                    String server = hasServer ? rs.getString("server_id") : null;
                    PunishmentRepository.nullable(ps, 17, server == null || server.isBlank() ? serverId : server);
                    ps.addBatch();
                    punishments++;
                }
                ps.executeBatch();
            }
        }

        int mirrors = 0;
        if (layout == Layout.MODPANEL && tables.contains(from.mirror().toLowerCase(Locale.ROOT))) {
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT server_id, punishment_id, applied_at FROM " + from.mirror());
                 PreparedStatement ps = dst.prepareStatement((mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
                         + to.mirror() + "(server_id,punishment_id,applied_at) VALUES(?,?,?)")) {
                while (rs.next()) {
                    ps.setString(1, rs.getString(1));
                    ps.setLong(2, rs.getLong(2));
                    ps.setLong(3, rs.getLong(3));
                    mirrors += ps.executeUpdate();
                }
            }
        }

        logger.info("Importação do SQLite: " + identities + " perfis, " + names + " nomes, " + ips + " IPs, "
                + punishments + " punições.");
        return new Result(identities, names, ips, punishments, mirrors);
    }

    private static Set<String> tableNames(Connection c) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                names.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(meta.getColumnName(i))) {
                return true;
            }
        }
        return false;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void deleteQuietly(Path dir) {
        try (var files = Files.list(dir)) {
            files.forEach(file -> file.toFile().delete());
        } catch (Exception ignored) {
            // diretório temporário
        }
        if (!dir.toFile().delete()) {
            logger.fine("Diretório temporário não removido: " + dir);
        }
    }
}
