package dev.zwo.modpanel.config;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Cópia imutável das opções do config.yml. Pode ser lida de qualquer thread;
 * um reload troca a instância inteira.
 */
public final class Settings {

    public enum StorageType { SQLITE, MYSQL }

    /** Permissões que a "main" legada não deve conceder. */
    private static final Set<String> DIRECT_ONLY = Set.of("bypass", "protected");

    private final String prefix;

    private final StorageType storageType;
    private final String tablePrefix;
    private final String sqliteFile;
    private final String mysqlHost;
    private final int mysqlPort;
    private final String mysqlDatabase;
    private final String mysqlUser;
    private final String mysqlPassword;
    private final String mysqlProperties;
    private final int poolMaxSize;
    private final int poolMinIdle;
    private final long poolConnectionTimeout;
    private final long poolMaxLifetime;

    private final boolean network;
    private final String serverId;

    private final boolean legacyMainGrantsActions;
    private final Map<String, String> permissions;

    private final int maxRadius;
    private final int maxMuteMinutes;
    private final long maxTempBanMillis;
    private final int ipAccountLimit;
    private final boolean kickSameIp;

    private final boolean syncEnabled;
    private final int syncIntervalSeconds;
    private final boolean loginCheck;

    private final boolean voiceEnabled;
    private final String speakPermission;
    private final String listenPermission;
    private final boolean muteListen;

    private final boolean chatMuteEnabled;
    private final boolean blockChat;
    private final Set<String> blockedCommands;

    private final String voiceMuteCommand;
    private final String voiceUnmuteCommand;
    private final String chatMuteCommand;
    private final String chatUnmuteCommand;

    private final int perPage;

    private Settings(FileConfiguration c, Logger logger) {
        prefix = c.getString("prefix", "&8[&cModPanel&8] &7");

        String type = c.getString("storage.type", "sqlite").trim().toLowerCase(Locale.ROOT);
        storageType = switch (type) {
            case "mysql", "mariadb" -> StorageType.MYSQL;
            case "sqlite" -> StorageType.SQLITE;
            default -> {
                logger.warning("storage.type desconhecido (" + type + "); usando sqlite.");
                yield StorageType.SQLITE;
            }
        };
        String rawPrefix = c.getString("storage.table-prefix", "modpanel_");
        if (!rawPrefix.matches("[A-Za-z0-9_]{0,32}")) {
            logger.warning("storage.table-prefix inválido (" + rawPrefix + "); usando modpanel_.");
            rawPrefix = "modpanel_";
        }
        tablePrefix = rawPrefix;
        sqliteFile = c.getString("storage.sqlite.file", "modpanel.db");
        mysqlHost = c.getString("storage.mysql.host", "localhost");
        mysqlPort = c.getInt("storage.mysql.port", 3306);
        mysqlDatabase = c.getString("storage.mysql.database", "modpanel");
        mysqlUser = c.getString("storage.mysql.username", "root");
        mysqlPassword = c.getString("storage.mysql.password", "");
        mysqlProperties = c.getString("storage.mysql.properties", "");
        poolMaxSize = clamp(c.getInt("storage.mysql.pool.maximum-size", 8), 1, 64);
        poolMinIdle = clamp(c.getInt("storage.mysql.pool.minimum-idle", 2), 0, poolMaxSize);
        poolConnectionTimeout = Math.max(1000L, c.getLong("storage.mysql.pool.connection-timeout-ms", 10_000L));
        poolMaxLifetime = Math.max(30_000L, c.getLong("storage.mysql.pool.max-lifetime-ms", 1_800_000L));

        network = c.getBoolean("network.enabled", false);
        String id = c.getString("network.server-id", "servidor-1").trim();
        serverId = id.isEmpty() ? "servidor-1" : (id.length() > 64 ? id.substring(0, 64) : id);

        legacyMainGrantsActions = c.getBoolean("permissions.legacy-main-grants-actions", true);
        Map<String, String> perms = new HashMap<>();
        for (String key : List.of("main", "view", "ban", "ipban", "unban", "unban-ip", "mute", "unmute",
                "mute-radius", "bulk", "sync", "reload", "bypass", "protected")) {
            perms.put(key, c.getString("permissions." + key, "modpanel." + key));
        }
        permissions = Map.copyOf(perms);

        maxRadius = Math.max(1, c.getInt("limits.max-radius", 100));
        maxMuteMinutes = Math.max(1, c.getInt("limits.max-mute-minutes", 43_200));
        long days = Math.max(1L, Math.min(c.getLong("limits.max-tempban-days", 3650L), 106_751_991_167L));
        maxTempBanMillis = days * 86_400_000L;
        ipAccountLimit = Math.max(2, c.getInt("limits.ip-ban-account-limit", 20));
        kickSameIp = c.getBoolean("ban.kick-same-ip", true);

        syncEnabled = c.getBoolean("ban-sync.enabled", true);
        syncIntervalSeconds = Math.max(10, c.getInt("ban-sync.interval-seconds", 30));
        loginCheck = c.getBoolean("login-check.enabled", true);

        voiceEnabled = c.getBoolean("voice-chat.enabled", true);
        speakPermission = c.getString("voice-chat.speak-permission", "voicechat.speak");
        listenPermission = c.getString("voice-chat.listen-permission", "voicechat.listen");
        muteListen = c.getBoolean("voice-chat.mute-listen", false);

        chatMuteEnabled = c.getBoolean("chat-mute.enabled", true);
        blockChat = c.getBoolean("chat-mute.block-chat", true);
        blockedCommands = c.getStringList("chat-mute.blocked-commands").stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .map(s -> s.startsWith("/") ? s.substring(1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        // Versões antigas usavam só "luckperms-command" para montar os comandos de voz.
        String lp = c.getString("commands.luckperms-command", "lp");
        voiceMuteCommand = c.contains("commands.voice-mute-command")
                ? c.getString("commands.voice-mute-command", "")
                : lp + " user {player} permission settemp {perm} false {time}";
        voiceUnmuteCommand = c.contains("commands.voice-unmute-command")
                ? c.getString("commands.voice-unmute-command", "")
                : lp + " user {player} permission unset {perm}";
        chatMuteCommand = c.getString("commands.chat-mute-command", "");
        chatUnmuteCommand = c.getString("commands.chat-unmute-command", "");

        perPage = clamp(c.getInt("gui.per-page", 28), 1, 28);
    }

    public static Settings load(FileConfiguration config, Logger logger) {
        return new Settings(config, logger);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public String permission(String key) {
        return permissions.getOrDefault(key, "modpanel." + key);
    }

    /** Checagem com as regras de compatibilidade da permissão principal. */
    public boolean allows(CommandSender sender, String key) {
        if (sender.hasPermission(permission(key))) {
            return true;
        }
        if (DIRECT_ONLY.contains(key)) {
            return false;
        }
        if (key.equals("main") || key.equals("view")) {
            return sender.hasPermission(permission("main"));
        }
        return legacyMainGrantsActions && sender.hasPermission(permission("main"));
    }

    public String prefix() { return prefix; }
    public StorageType storageType() { return storageType; }
    public String tablePrefix() { return tablePrefix; }
    public String sqliteFile() { return sqliteFile; }
    public String mysqlHost() { return mysqlHost; }
    public int mysqlPort() { return mysqlPort; }
    public String mysqlDatabase() { return mysqlDatabase; }
    public String mysqlUser() { return mysqlUser; }
    public String mysqlPassword() { return mysqlPassword; }
    public String mysqlProperties() { return mysqlProperties; }
    public int poolMaxSize() { return poolMaxSize; }
    public int poolMinIdle() { return poolMinIdle; }
    public long poolConnectionTimeout() { return poolConnectionTimeout; }
    public long poolMaxLifetime() { return poolMaxLifetime; }
    public boolean network() { return network; }
    public String serverId() { return serverId; }
    public int maxRadius() { return maxRadius; }
    public int maxMuteMinutes() { return maxMuteMinutes; }
    public long maxTempBanMillis() { return maxTempBanMillis; }
    public int ipAccountLimit() { return ipAccountLimit; }
    public boolean kickSameIp() { return kickSameIp; }
    public boolean syncEnabled() { return syncEnabled; }
    public int syncIntervalSeconds() { return syncIntervalSeconds; }
    public boolean loginCheck() { return loginCheck; }
    public boolean voiceEnabled() { return voiceEnabled; }
    public String speakPermission() { return speakPermission; }
    public String listenPermission() { return listenPermission; }
    public boolean muteListen() { return muteListen; }
    public boolean chatMuteEnabled() { return chatMuteEnabled; }
    public boolean blockChat() { return blockChat; }
    public Set<String> blockedCommands() { return blockedCommands; }
    public String voiceMuteCommand() { return voiceMuteCommand; }
    public String voiceUnmuteCommand() { return voiceUnmuteCommand; }
    public String chatMuteCommand() { return chatMuteCommand; }
    public String chatUnmuteCommand() { return chatUnmuteCommand; }
    public int perPage() { return perPage; }
}
