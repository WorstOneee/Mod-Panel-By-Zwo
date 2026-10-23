package dev.zwo.modpanel.storage;

import dev.zwo.modpanel.model.Names;
import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PlayerIdentity;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Consultas de identidades, IPs e punições. Bloqueante: use fora da thread principal. */
public final class PunishmentRepository {

    private static final String ACTIVE = PunishmentStatus.ACTIVE.name();
    private static final int CHUNK = 400;

    private final Database database;
    private final Tables t;
    private final boolean mysql;

    public PunishmentRepository(Database database) {
        this.database = database;
        this.t = database.tables();
        this.mysql = database.dialect() == Dialect.MYSQL;
    }

    public Database database() {
        return database;
    }

    // ------------------------------------------------------------------ meta

    public Optional<String> meta(String key) throws SQLException {
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM " + t.meta() + " WHERE meta_key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public void setMeta(String key, String value) throws SQLException {
        database.transaction(c -> {
            setMeta(c, key, value);
            return null;
        });
    }

    void setMeta(Connection c, String key, String value) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + t.meta() + "(meta_key,meta_value) VALUES(?,?) ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)"
                : "INSERT INTO " + t.meta() + "(meta_key,meta_value) VALUES(?,?) ON CONFLICT(meta_key) DO UPDATE SET meta_value=excluded.meta_value";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ identities

    public void upsertIdentity(UUID uuid, String name, String ip, long now) throws SQLException {
        if (uuid == null || name == null || name.isBlank()) {
            if (name != null && ip != null && !ip.isBlank()) {
                recordKnownIp(null, name, ip, now);
            }
            return;
        }
        database.transaction(c -> {
            upsertIdentity(c, uuid, name, ip, now);
            return null;
        });
    }

    void upsertIdentity(Connection c, UUID uuid, String name, String ip, long now) throws SQLException {
        String identitySql = mysql
                ? "INSERT INTO " + t.identities() + "(uuid,current_name,name_key,last_ip,updated_at) VALUES(?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE current_name=VALUES(current_name), name_key=VALUES(name_key), "
                  + "last_ip=COALESCE(VALUES(last_ip), last_ip), updated_at=VALUES(updated_at)"
                : "INSERT INTO " + t.identities() + "(uuid,current_name,name_key,last_ip,updated_at) VALUES(?,?,?,?,?) "
                  + "ON CONFLICT(uuid) DO UPDATE SET current_name=excluded.current_name, name_key=excluded.name_key, "
                  + "last_ip=COALESCE(excluded.last_ip, " + t.identities() + ".last_ip), updated_at=excluded.updated_at";
        try (PreparedStatement ps = c.prepareStatement(identitySql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, limit(name, 64));
            ps.setString(3, limit(Names.key(name), 64));
            nullable(ps, 4, ip);
            ps.setLong(5, now);
            ps.executeUpdate();
        }

        String namesSql = mysql
                ? "INSERT INTO " + t.names() + "(uuid,name,name_key,last_seen) VALUES(?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen)"
                : "INSERT INTO " + t.names() + "(uuid,name,name_key,last_seen) VALUES(?,?,?,?) "
                  + "ON CONFLICT(uuid,name_key) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen";
        try (PreparedStatement ps = c.prepareStatement(namesSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, limit(name, 64));
            ps.setString(3, limit(Names.key(name), 64));
            ps.setLong(4, now);
            ps.executeUpdate();
        }

        if (ip != null && !ip.isBlank()) {
            upsertKnownIp(c, uuid, name, ip, now);
        }
    }

    public void recordKnownIp(UUID uuid, String name, String ip, long now) throws SQLException {
        if (name == null || name.isBlank() || ip == null || ip.isBlank()) {
            return;
        }
        database.transaction(c -> {
            upsertKnownIp(c, uuid, name, ip, now);
            return null;
        });
    }

    void upsertKnownIp(Connection c, UUID uuid, String name, String ip, long now) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + t.ips() + "(uuid,player_name,name_key,ip,first_seen,last_seen) VALUES(?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE uuid=COALESCE(VALUES(uuid), uuid), player_name=VALUES(player_name), "
                  + "last_seen=VALUES(last_seen)"
                : "INSERT INTO " + t.ips() + "(uuid,player_name,name_key,ip,first_seen,last_seen) VALUES(?,?,?,?,?,?) "
                  + "ON CONFLICT(name_key,ip) DO UPDATE SET uuid=COALESCE(excluded.uuid, " + t.ips() + ".uuid), "
                  + "player_name=excluded.player_name, last_seen=excluded.last_seen";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            nullable(ps, 1, uuid == null ? null : uuid.toString());
            ps.setString(2, limit(name, 64));
            ps.setString(3, limit(Names.key(name), 64));
            ps.setString(4, limit(ip, 64));
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    public Optional<PlayerIdentity> findIdentity(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = Names.key(name);
        return database.query(c -> {
            String sql = "SELECT i.uuid, i.current_name, i.last_ip, i.updated_at FROM " + t.identities() + " i "
                    + "WHERE i.name_key=? OR i.uuid IN (SELECT n.uuid FROM " + t.names() + " n WHERE n.name_key=?) "
                    + "ORDER BY CASE WHEN i.name_key=? THEN 0 ELSE 1 END, i.updated_at DESC LIMIT 1";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, key);
                ps.setString(3, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readIdentity(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<PlayerIdentity> findIdentity(UUID uuid) throws SQLException {
        if (uuid == null) {
            return Optional.empty();
        }
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, current_name, last_ip, updated_at FROM " + t.identities() + " WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readIdentity(rs)) : Optional.empty();
                }
            }
        });
    }

    private static PlayerIdentity readIdentity(ResultSet rs) throws SQLException {
        return new PlayerIdentity(UUID.fromString(rs.getString("uuid")), rs.getString("current_name"),
                rs.getString("last_ip"), rs.getLong("updated_at"));
    }

    public Optional<String> findLastIp(String name) throws SQLException {
        Optional<PlayerIdentity> identity = findIdentity(name);
        if (identity.isPresent() && identity.get().lastIp() != null && !identity.get().lastIp().isBlank()) {
            return Optional.of(identity.get().lastIp());
        }
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ip FROM " + t.ips() + " WHERE name_key=? ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, Names.key(name));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public int knownNameCountForIp(String ip) throws SQLException {
        return count("SELECT COUNT(DISTINCT name_key) FROM " + t.ips() + " WHERE ip=?", ip);
    }

    public int countIdentities() throws SQLException {
        return count("SELECT COUNT(*) FROM " + t.identities());
    }

    // ----------------------------------------------------------- punishments

    public List<PunishmentRecord> insertAll(List<NewPunishment> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        return database.transaction(c -> insertAll(c, rows));
    }

    /** Insere as punições e marca as de ban ativas como aplicadas neste servidor. */
    public List<PunishmentRecord> insertAndMirror(List<NewPunishment> rows, String serverId, long now) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        return database.transaction(c -> {
            List<PunishmentRecord> saved = insertAll(c, rows);
            List<Long> ids = saved.stream()
                    .filter(r -> r.active() && r.scope().isBan())
                    .map(PunishmentRecord::id)
                    .toList();
            addMirror(c, serverId, ids, now);
            return saved;
        });
    }

    List<PunishmentRecord> insertAll(Connection c, List<NewPunishment> rows) throws SQLException {
        List<PunishmentRecord> saved = new ArrayList<>();
        for (NewPunishment row : rows) {
            PunishmentRecord record = insert(c, row);
            if (record != null) {
                saved.add(record);
            }
        }
        return saved;
    }

    private PunishmentRecord insert(Connection c, NewPunishment row) throws SQLException {
        if (row.legacyKey() != null && existsLegacyKey(c, row.legacyKey())) {
            return null;
        }
        String sql = "INSERT INTO " + t.punishments() + "(case_id,scope,target_uuid,target_name,target_key,target_ip,"
                + "reason,actor,origin,status,created_at,expires_at,legacy_key,server_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String reason = limit(safe(row.reason(), "Sem motivo informado"), 500);
        String actor = limit(safe(row.actor(), "Servidor"), 64);
        String origin = limit(safe(row.origin(), "MODPANEL"), 32);
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, row.caseId());
            ps.setString(2, row.scope().name());
            nullable(ps, 3, row.targetUuid() == null ? null : row.targetUuid().toString());
            nullable(ps, 4, limit(row.targetName(), 64));
            nullable(ps, 5, row.targetName() == null ? null : limit(Names.key(row.targetName()), 64));
            nullable(ps, 6, limit(row.targetIp(), 64));
            ps.setString(7, reason);
            ps.setString(8, actor);
            ps.setString(9, origin);
            ps.setString(10, row.status().name());
            ps.setLong(11, row.createdAt());
            nullableLong(ps, 12, row.expiresAt());
            nullable(ps, 13, limit(row.legacyKey(), 191));
            nullable(ps, 14, row.serverId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("O banco não retornou o ID da punição");
                }
                return new PunishmentRecord(keys.getLong(1), row.caseId(), row.scope(), row.targetUuid(),
                        limit(row.targetName(), 64), limit(row.targetIp(), 64), reason, actor, origin, row.status(),
                        row.createdAt(), row.expiresAt(), null, null, row.legacyKey(), row.serverId());
            }
        }
    }

    private boolean existsLegacyKey(Connection c, String legacyKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + t.punishments() + " WHERE legacy_key=?")) {
            ps.setString(1, limit(legacyKey, 191));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean hasLegacyKey(String legacyKey) throws SQLException {
        return database.query(c -> existsLegacyKey(c, legacyKey));
    }

    public List<PunishmentRecord> active(PunishmentScope scope) throws SQLException {
        return active(EnumSet.of(scope));
    }

    public List<PunishmentRecord> active(Set<PunishmentScope> scopes) throws SQLException {
        if (scopes.isEmpty()) {
            return List.of();
        }
        return database.query(c -> selectActiveScopes(c, scopes));
    }

    public List<PunishmentRecord> due(long now) throws SQLException {
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                    + " WHERE status=? AND expires_at IS NOT NULL AND expires_at<=? ORDER BY expires_at ASC")) {
                ps.setString(1, ACTIVE);
                ps.setLong(2, now);
                return readAll(ps);
            }
        });
    }

    public List<PunishmentRecord> historyForPlayer(String name, UUID uuid, int limit, int offset) throws SQLException {
        String key = Names.key(name);
        String id = uuid == null ? "" : uuid.toString();
        return database.query(c -> {
            String sql = "SELECT * FROM " + t.punishments() + " WHERE case_id IN ("
                    + "SELECT case_id FROM " + t.punishments() + " WHERE target_key=? OR target_uuid=?"
                    + ") OR target_key=? OR target_uuid=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, id);
                ps.setString(3, key);
                ps.setString(4, id);
                ps.setInt(5, Math.max(1, limit));
                ps.setInt(6, Math.max(0, offset));
                return readAll(ps);
            }
        });
    }

    public List<PunishmentRecord> historyForIp(String ip, int limit, int offset) throws SQLException {
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                    + " WHERE target_ip=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, ip);
                ps.setInt(2, Math.max(1, limit));
                ps.setInt(3, Math.max(0, offset));
                return readAll(ps);
            }
        });
    }

    public List<PunishmentRecord> activeForPlayer(String name, UUID uuid, Set<PunishmentScope> scopes) throws SQLException {
        if (scopes.isEmpty()) {
            return List.of();
        }
        return database.query(c -> selectActivePlayer(c, name, uuid, scopes));
    }

    public List<PunishmentRecord> activeForIp(String ip) throws SQLException {
        return database.query(c -> selectActiveIp(c, ip));
    }

    /** Ban ativo (perfil ou IP) que deve barrar o login. */
    public Optional<PunishmentRecord> activeLoginBlock(UUID uuid, String ip, long now) throws SQLException {
        return database.query(c -> {
            String sql = "SELECT * FROM " + t.punishments() + " WHERE status=? AND ("
                    + "(scope='PROFILE' AND target_uuid=?) OR (scope='IP' AND target_ip=?)"
                    + ") AND (expires_at IS NULL OR expires_at>?) ORDER BY CASE WHEN scope='PROFILE' THEN 0 ELSE 1 END, "
                    + "created_at DESC LIMIT 1";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, ACTIVE);
                ps.setString(2, uuid == null ? "" : uuid.toString());
                ps.setString(3, ip == null ? "" : ip);
                ps.setLong(4, now);
                List<PunishmentRecord> rows = readAll(ps);
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
            }
        });
    }

    public List<PunishmentRecord> byIds(Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        return database.query(c -> {
            List<PunishmentRecord> out = new ArrayList<>();
            for (List<Long> chunk : chunks(new ArrayList<>(ids))) {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                        + " WHERE id IN (" + marks(chunk.size()) + ")")) {
                    int i = 1;
                    for (Long id : chunk) {
                        ps.setLong(i++, id);
                    }
                    out.addAll(readAll(ps));
                }
            }
            return out;
        });
    }

    public List<PunishmentRecord> revokePlayerBan(String name, UUID uuid, String actor, long now) throws SQLException {
        return database.transaction(c -> {
            List<PunishmentRecord> profiles = selectActivePlayer(c, name, uuid, EnumSet.of(PunishmentScope.PROFILE));
            Set<String> cases = new LinkedHashSet<>();
            profiles.forEach(r -> cases.add(r.caseId()));
            List<PunishmentRecord> affected = new ArrayList<>(profiles);
            if (!cases.isEmpty()) {
                affected.addAll(selectActiveCaseScopes(c, cases, EnumSet.of(PunishmentScope.IP)));
            }
            updateStatus(c, affected, PunishmentStatus.REVOKED, actor, now);
            return affected;
        });
    }

    public List<PunishmentRecord> revokeIpFull(String ip, String actor, long now) throws SQLException {
        return database.transaction(c -> {
            List<PunishmentRecord> ips = selectActiveIp(c, ip);
            Set<String> cases = new LinkedHashSet<>();
            ips.forEach(r -> cases.add(r.caseId()));
            List<PunishmentRecord> affected = new ArrayList<>(ips);
            if (!cases.isEmpty()) {
                affected.addAll(selectActiveCaseScopes(c, cases, EnumSet.of(PunishmentScope.PROFILE)));
            }
            updateStatus(c, affected, PunishmentStatus.REVOKED, actor, now);
            return affected;
        });
    }

    public List<PunishmentRecord> revokePlayerMutes(String name, UUID uuid, String actor, long now) throws SQLException {
        return database.transaction(c -> {
            List<PunishmentRecord> mutes = selectActivePlayer(c, name, uuid,
                    EnumSet.of(PunishmentScope.VOICE, PunishmentScope.CHAT));
            updateStatus(c, mutes, PunishmentStatus.REVOKED, actor, now);
            return mutes;
        });
    }

    public List<PunishmentRecord> revokeAllBans(String actor, long now) throws SQLException {
        return revokeAll(EnumSet.of(PunishmentScope.PROFILE, PunishmentScope.IP), actor, now);
    }

    public List<PunishmentRecord> revokeAllMutes(String actor, long now) throws SQLException {
        return revokeAll(EnumSet.of(PunishmentScope.VOICE, PunishmentScope.CHAT), actor, now);
    }

    private List<PunishmentRecord> revokeAll(Set<PunishmentScope> scopes, String actor, long now) throws SQLException {
        return database.transaction(c -> {
            List<PunishmentRecord> rows = selectActiveScopes(c, scopes);
            updateStatus(c, rows, PunishmentStatus.REVOKED, actor, now);
            return rows;
        });
    }

    /** Expira os registros vencidos de um caso. Retorna os que mudaram de estado. */
    public List<PunishmentRecord> expireCase(String caseId, long now) throws SQLException {
        return database.transaction(c -> {
            List<PunishmentRecord> rows;
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                    + " WHERE case_id=? AND status=? AND expires_at IS NOT NULL AND expires_at<=?")) {
                ps.setString(1, caseId);
                ps.setString(2, ACTIVE);
                ps.setLong(3, now);
                rows = readAll(ps);
            }
            return updateStatus(c, rows, PunishmentStatus.EXPIRED, "Sistema", now);
        });
    }

    public boolean markStatus(long id, PunishmentStatus status, String actor, long now) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.punishments()
                    + " SET status=?, revoked_at=?, revoked_by=? WHERE id=? AND status=?")) {
                ps.setString(1, status.name());
                ps.setLong(2, now);
                ps.setString(3, limit(actor, 64));
                ps.setLong(4, id);
                ps.setString(5, ACTIVE);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public int activeIpCount(String ip) throws SQLException {
        return count("SELECT COUNT(*) FROM " + t.punishments() + " WHERE status=? AND scope='IP' AND target_ip=?", ACTIVE, ip);
    }

    public int activeProfileCount(String name, UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + t.punishments()
                        + " WHERE status=? AND scope='PROFILE' AND (target_key=? OR target_uuid=?)",
                ACTIVE, Names.key(name), uuid == null ? "" : uuid.toString());
    }

    public Map<PunishmentScope, Integer> activeCounts() throws SQLException {
        Map<PunishmentScope, Integer> counts = new EnumMap<>(PunishmentScope.class);
        for (PunishmentScope scope : PunishmentScope.values()) {
            counts.put(scope, 0);
        }
        database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT scope, COUNT(*) FROM " + t.punishments() + " WHERE status=? GROUP BY scope")) {
                ps.setString(1, ACTIVE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PunishmentScope scope = parseScope(rs.getString(1));
                        if (scope != null) {
                            counts.put(scope, rs.getInt(2));
                        }
                    }
                }
            }
            return null;
        });
        return counts;
    }

    public int countPunishments() throws SQLException {
        return count("SELECT COUNT(*) FROM " + t.punishments());
    }

    // ---------------------------------------------------------------- mirror

    public Set<Long> mirroredIds(String serverId) throws SQLException {
        return database.query(c -> {
            Set<Long> ids = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT punishment_id FROM " + t.mirror() + " WHERE server_id=?")) {
                ps.setString(1, serverId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    public void addMirror(String serverId, Collection<Long> ids, long now) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        database.transaction(c -> {
            addMirror(c, serverId, ids, now);
            return null;
        });
    }

    void addMirror(Connection c, String serverId, Collection<Long> ids, long now) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String sql = (mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ") + t.mirror()
                + "(server_id,punishment_id,applied_at) VALUES(?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Long id : ids) {
                ps.setString(1, serverId);
                ps.setLong(2, id);
                ps.setLong(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void removeMirror(String serverId, Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        database.transaction(c -> {
            for (List<Long> chunk : chunks(new ArrayList<>(ids))) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t.mirror()
                        + " WHERE server_id=? AND punishment_id IN (" + marks(chunk.size()) + ")")) {
                    ps.setString(1, serverId);
                    int i = 2;
                    for (Long id : chunk) {
                        ps.setLong(i++, id);
                    }
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // --------------------------------------------------------------- helpers

    private List<PunishmentRecord> selectActivePlayer(Connection c, String name, UUID uuid,
                                                      Set<PunishmentScope> scopes) throws SQLException {
        String sql = "SELECT * FROM " + t.punishments() + " WHERE status=? AND scope IN (" + marks(scopes.size())
                + ") AND (target_key=? OR target_uuid=?) ORDER BY created_at DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, ACTIVE);
            for (PunishmentScope scope : scopes) {
                ps.setString(i++, scope.name());
            }
            ps.setString(i++, Names.key(name));
            ps.setString(i, uuid == null ? "" : uuid.toString());
            return readAll(ps);
        }
    }

    private List<PunishmentRecord> selectActiveIp(Connection c, String ip) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                + " WHERE status=? AND scope='IP' AND target_ip=? ORDER BY created_at DESC")) {
            ps.setString(1, ACTIVE);
            ps.setString(2, ip);
            return readAll(ps);
        }
    }

    private List<PunishmentRecord> selectActiveScopes(Connection c, Set<PunishmentScope> scopes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t.punishments()
                + " WHERE status=? AND scope IN (" + marks(scopes.size()) + ") ORDER BY created_at DESC")) {
            int i = 1;
            ps.setString(i++, ACTIVE);
            for (PunishmentScope scope : scopes) {
                ps.setString(i++, scope.name());
            }
            return readAll(ps);
        }
    }

    private List<PunishmentRecord> selectActiveCaseScopes(Connection c, Set<String> cases,
                                                          Set<PunishmentScope> scopes) throws SQLException {
        List<PunishmentRecord> out = new ArrayList<>();
        for (List<String> chunk : chunks(new ArrayList<>(cases))) {
            String sql = "SELECT * FROM " + t.punishments() + " WHERE status=? AND case_id IN (" + marks(chunk.size())
                    + ") AND scope IN (" + marks(scopes.size()) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, ACTIVE);
                for (String caseId : chunk) {
                    ps.setString(i++, caseId);
                }
                for (PunishmentScope scope : scopes) {
                    ps.setString(i++, scope.name());
                }
                out.addAll(readAll(ps));
            }
        }
        return out;
    }

    /** Atualiza só os que ainda estavam ativos e devolve exatamente esses. */
    private List<PunishmentRecord> updateStatus(Connection c, List<PunishmentRecord> rows, PunishmentStatus status,
                                                String actor, long now) throws SQLException {
        if (rows.isEmpty()) {
            return rows;
        }
        List<PunishmentRecord> changed = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.punishments()
                + " SET status=?, revoked_at=?, revoked_by=? WHERE id=? AND status=?")) {
            for (PunishmentRecord row : rows) {
                ps.setString(1, status.name());
                ps.setLong(2, now);
                ps.setString(3, limit(actor, 64));
                ps.setLong(4, row.id());
                ps.setString(5, ACTIVE);
                if (ps.executeUpdate() > 0) {
                    changed.add(row);
                }
            }
        }
        return changed;
    }

    private int count(String sql, String... args) throws SQLException {
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private List<PunishmentRecord> readAll(PreparedStatement ps) throws SQLException {
        List<PunishmentRecord> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PunishmentRecord record = read(rs);
                if (record != null) {
                    rows.add(record);
                }
            }
        }
        return rows;
    }

    private static PunishmentRecord read(ResultSet rs) throws SQLException {
        PunishmentScope scope = parseScope(rs.getString("scope"));
        PunishmentStatus status = parseStatus(rs.getString("status"));
        if (scope == null || status == null) {
            return null;
        }
        String uuid = rs.getString("target_uuid");
        long expires = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : expires;
        long revoked = rs.getLong("revoked_at");
        Long revokedAt = rs.wasNull() ? null : revoked;
        return new PunishmentRecord(
                rs.getLong("id"),
                rs.getString("case_id"),
                scope,
                parseUuid(uuid),
                rs.getString("target_name"),
                rs.getString("target_ip"),
                rs.getString("reason"),
                rs.getString("actor"),
                rs.getString("origin"),
                status,
                rs.getLong("created_at"),
                expiresAt,
                revokedAt,
                rs.getString("revoked_by"),
                rs.getString("legacy_key"),
                rs.getString("server_id"));
    }

    private static PunishmentScope parseScope(String value) {
        try {
            return value == null ? null : PunishmentScope.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PunishmentStatus parseStatus(String value) {
        try {
            return value == null ? null : PunishmentStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i += CHUNK) {
            out.add(values.subList(i, Math.min(values.size(), i + CHUNK)));
        }
        return out;
    }

    static void nullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    static void nullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
