package dev.zwo.modpanel.storage;

import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PlayerIdentity;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mesmo conjunto de testes para SQLite e MySQL. */
abstract class RepositoryContract {

    protected static final Logger LOG = Logger.getLogger("ModPanelTest");

    @TempDir
    File temp;

    protected Database database;
    protected PunishmentRepository repository;

    protected abstract Database createDatabase() throws Exception;

    @BeforeEach
    void setUp() throws Exception {
        database = createDatabase();
        database.open();
        Schema.create(database);
        Schema.create(database);
        repository = new PunishmentRepository(database);
    }

    /** Remove o que o teste criou (usado no MySQL, que é compartilhado). */
    protected void dropTables() throws Exception {
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            dropTables();
        } finally {
            database.close();
        }
    }

    private static NewPunishment row(String caseId, PunishmentScope scope, UUID uuid, String name, String ip,
                                     PunishmentStatus status, long created, Long expires, String legacy) {
        return new NewPunishment(caseId, scope, uuid, name, ip, "motivo", "Staff", "MODPANEL", status, created,
                expires, legacy, "srv-1");
    }

    @Test
    void identitiesAndKnownIps() throws Exception {
        long now = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        repository.upsertIdentity(uuid, "Steve", "10.0.0.1", now);
        repository.upsertIdentity(uuid, "SteveNovo", null, now + 1);
        repository.recordKnownIp(null, "Alex", "10.0.0.1", now);

        PlayerIdentity byOldName = repository.findIdentity("steve").orElseThrow();
        assertEquals(uuid, byOldName.uuid());
        assertEquals("SteveNovo", repository.findIdentity("STEVENOVO").orElseThrow().name());
        assertEquals("10.0.0.1", repository.findIdentity(uuid).orElseThrow().lastIp());
        assertEquals(2, repository.knownNameCountForIp("10.0.0.1"));
        assertEquals("10.0.0.1", repository.findLastIp("alex").orElseThrow());
    }

    @Test
    void banLifecycle() throws Exception {
        long now = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        String caseId = UUID.randomUUID().toString();
        List<PunishmentRecord> saved = repository.insertAndMirror(List.of(
                row(caseId, PunishmentScope.PROFILE, uuid, "Steve", null, PunishmentStatus.ACTIVE, now, null, null),
                row(caseId, PunishmentScope.IP, uuid, "Steve", "10.0.0.1", PunishmentStatus.ACTIVE, now, null, null)),
                "srv-1", now);

        assertEquals(2, saved.size());
        assertEquals(2, repository.mirroredIds("srv-1").size());
        assertEquals(1, repository.activeForPlayer("STEVE", null, EnumSet.of(PunishmentScope.PROFILE)).size());
        assertTrue(repository.activeLoginBlock(uuid, null, now).isPresent());
        assertTrue(repository.activeLoginBlock(UUID.randomUUID(), "10.0.0.1", now).isPresent());
        assertFalse(repository.activeLoginBlock(UUID.randomUUID(), "10.0.0.2", now).isPresent());
        assertEquals(2, repository.historyForPlayer("steve", uuid, 10, 0).size());

        List<PunishmentRecord> revoked = repository.revokePlayerBan("Steve", uuid, "Mod", now);
        assertEquals(2, revoked.size());
        assertTrue(repository.revokePlayerBan("Steve", uuid, "Mod", now).isEmpty());
        assertEquals(0, repository.activeIpCount("10.0.0.1"));
        assertEquals(PunishmentStatus.REVOKED, repository.byIds(List.of(saved.getFirst().id())).getFirst().status());

        repository.removeMirror("srv-1", revoked.stream().map(PunishmentRecord::id).toList());
        assertTrue(repository.mirroredIds("srv-1").isEmpty());
    }

    @Test
    void expirationAndLegacyKeys() throws Exception {
        long now = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        String caseId = UUID.randomUUID().toString();
        repository.insertAll(List.of(
                row(caseId, PunishmentScope.VOICE, uuid, "Steve", null, PunishmentStatus.ACTIVE, now - 5000, now - 1, null),
                row(caseId, PunishmentScope.CHAT, uuid, "Steve", null, PunishmentStatus.ACTIVE, now - 5000, now - 1, null)));
        assertEquals(2, repository.due(now).size());
        assertEquals(2, repository.expireCase(caseId, now).size());
        assertTrue(repository.expireCase(caseId, now).isEmpty());

        NewPunishment imported = row("native-1", PunishmentScope.PROFILE, UUID.randomUUID(), "Bob", null,
                PunishmentStatus.ACTIVE, now, null, "native:profile:abc");
        assertEquals(1, repository.insertAll(List.of(imported)).size());
        assertTrue(repository.insertAll(List.of(imported)).isEmpty());
        assertEquals(1, repository.activeCounts().get(PunishmentScope.PROFILE));

        repository.setMeta("chave", "1");
        repository.setMeta("chave", "2");
        assertEquals("2", repository.meta("chave").orElseThrow());
    }

    @Test
    void importsSerpensDatabase() throws Exception {
        File old = new File(temp, "serpens.db");
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE identities (uuid TEXT PRIMARY KEY, current_name TEXT NOT NULL, "
                    + "normalized_name TEXT NOT NULL, last_ip TEXT, updated_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE punishments (id INTEGER PRIMARY KEY AUTOINCREMENT, case_id TEXT NOT NULL, "
                    + "scope TEXT NOT NULL, target_uuid TEXT, target_name TEXT, target_ip TEXT, reason TEXT NOT NULL, "
                    + "actor TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, "
                    + "expires_at INTEGER, revoked_at INTEGER, revoked_by TEXT, legacy_key TEXT UNIQUE)");
            st.executeUpdate("INSERT INTO identities VALUES('" + uuid + "','Notch','notch','5.5.5.5'," + now + ")");
            st.executeUpdate("INSERT INTO punishments(id,case_id,scope,target_uuid,target_name,reason,actor,origin,status,"
                    + "created_at) VALUES(41,'c','PROFILE','" + uuid + "','Notch','hack','Admin','MODPANEL','ACTIVE'," + now + ")");
        }

        SqliteImporter.Result result = new SqliteImporter(repository, LOG)
                .importFrom(old, SqliteImporter.Layout.SERPENS, "", "srv-1");
        assertEquals(1, result.punishments());
        assertEquals(1, result.identities());
        PunishmentRecord record = repository.byIds(List.of(41L)).getFirst();
        assertEquals("srv-1", record.serverId());
        assertEquals(1, repository.activeForPlayer("NOTCH", null, EnumSet.of(PunishmentScope.PROFILE)).size());

        long nextId = repository.insertAll(List.of(row("d", PunishmentScope.CHAT, null, "x", null,
                PunishmentStatus.ACTIVE, now, now + 1000, null))).getFirst().id();
        assertTrue(nextId > 41);
        assertThrows(IllegalStateException.class, () -> new SqliteImporter(repository, LOG)
                .importFrom(old, SqliteImporter.Layout.SERPENS, "", "srv-1"));
    }
}
