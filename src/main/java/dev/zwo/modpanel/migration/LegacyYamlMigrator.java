package dev.zwo.modpanel.migration;

import dev.zwo.modpanel.model.NewPunishment;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.model.PunishmentStatus;
import dev.zwo.modpanel.storage.PunishmentRepository;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Importa o data.yml da primeira versão do painel (anterior ao banco de dados). */
public final class LegacyYamlMigrator {

    public static final String META_KEY = "import.legacy_yaml";
    private static final String ORIGIN = "LEGACY_YAML";

    public record Result(boolean executed, int discovered, int inserted, File backup) {
    }

    private final PunishmentRepository repository;
    private final File backupFolder;
    private final String serverId;
    private final Logger logger;

    public LegacyYamlMigrator(PunishmentRepository repository, File backupFolder, String serverId, Logger logger) {
        this.repository = repository;
        this.backupFolder = backupFolder;
        this.serverId = serverId;
        this.logger = logger;
    }

    public Result migrate(File dataFile) throws Exception {
        if (repository.meta(META_KEY).isPresent()) {
            return new Result(false, 0, 0, null);
        }
        if (!dataFile.isFile()) {
            return new Result(false, 0, 0, null);
        }

        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar " + backupFolder);
        }
        File backup = new File(backupFolder, "data.yml.legacy-" + timestamp() + ".bak");
        Files.copy(dataFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        yaml.options().pathSeparator('|');
        long now = System.currentTimeMillis();

        Map<String, UUID> identities = importIdentities(yaml, now);
        List<NewPunishment> rows = new ArrayList<>();
        Set<String> profileIpSignatures = new HashSet<>();
        importPlayerBans(yaml, identities, rows, profileIpSignatures, now);
        importIpBans(yaml, identities, rows, profileIpSignatures, now);
        importVoiceMutes(yaml, identities, rows, now);

        int inserted = repository.insertAll(rows).size();
        repository.setMeta(META_KEY, "ok:" + now + ":inserted=" + inserted + ":source=" + rows.size());
        logger.info("Migração do data.yml concluída: " + inserted + " componente(s) importado(s); backup=" + backup.getName());
        return new Result(true, rows.size(), inserted, backup);
    }

    private Map<String, UUID> importIdentities(YamlConfiguration yaml, long now) throws Exception {
        Map<String, UUID> byName = new HashMap<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = players.getString(key + "|name", players.getString(key + ".name", ""));
                    if (name != null && !name.isBlank()) {
                        byName.put(normalize(name), uuid);
                        repository.upsertIdentity(uuid, name, null, now);
                    }
                } catch (IllegalArgumentException e) {
                    logger.warning("UUID legado inválido ignorado: " + key);
                }
            }
        }

        ConfigurationSection identity = yaml.getConfigurationSection("identity");
        if (identity != null) {
            for (String name : identity.getKeys(false)) {
                String base = "identity|" + name + "|";
                String lastIp = yaml.getString(base + "lastIp", "");
                UUID uuid = byName.get(normalize(name));
                repository.upsertIdentity(uuid, name, lastIp, now);
                for (String ip : yaml.getStringList(base + "ips")) {
                    repository.recordKnownIp(uuid, name, ip, now);
                }
            }
        }
        return byName;
    }

    private void importPlayerBans(YamlConfiguration yaml, Map<String, UUID> identities, List<NewPunishment> rows,
                                  Set<String> profileIpSignatures, long now) {
        ConfigurationSection bans = yaml.getConfigurationSection("bans");
        if (bans == null) {
            return;
        }
        for (String name : bans.getKeys(false)) {
            ConfigurationSection ban = bans.getConfigurationSection(name);
            if (ban == null || !ban.contains("reason")) {
                continue;
            }
            String reason = ban.getString("reason", "Motivo não informado");
            String staff = ban.getString("staff", "Servidor");
            String mode = ban.getString("mode", "perm");
            long until = ban.getLong("until", 0L);
            String ip = ban.getString("ip", "");
            long created = parseDate(ban.getString("date", ""), now);
            Long expiresAt = "temp".equalsIgnoreCase(mode) && until > 0 ? until : null;
            PunishmentStatus status = expiresAt != null && expiresAt <= now ? PunishmentStatus.EXPIRED : PunishmentStatus.ACTIVE;
            UUID uuid = identities.get(normalize(name));
            String signature = signature(name, reason, staff, created, until, ip);
            String caseId = "legacy-" + signature;

            rows.add(new NewPunishment(caseId, PunishmentScope.PROFILE, uuid, name, null, reason, staff, ORIGIN,
                    status, created, expiresAt, "legacy:profile:" + signature, serverId));
            if (ip != null && !ip.isBlank()) {
                rows.add(new NewPunishment(caseId, PunishmentScope.IP, uuid, name, ip, reason, staff, ORIGIN,
                        status, created, expiresAt, "legacy:profile-ip:" + signature, serverId));
                profileIpSignatures.add(ipSignature(ip, reason, mode, until));
            }
        }
    }

    private void importIpBans(YamlConfiguration yaml, Map<String, UUID> identities, List<NewPunishment> rows,
                              Set<String> profileIpSignatures, long now) {
        ConfigurationSection root = yaml.getConfigurationSection("ipbans");
        if (root == null) {
            return;
        }
        Map<String, ConfigurationSection> sections = new HashMap<>();
        collectIpSections(root, "", sections);
        for (Map.Entry<String, ConfigurationSection> entry : sections.entrySet()) {
            String ip = entry.getKey();
            ConfigurationSection ban = entry.getValue();
            String reason = ban.getString("reason", "Motivo não informado");
            String staff = ban.getString("staff", "Servidor");
            String mode = ban.getString("mode", "perm");
            long until = ban.getLong("until", 0L);
            if (profileIpSignatures.contains(ipSignature(ip, reason, mode, until))) {
                continue;
            }
            String name = ban.getString("name", "IP");
            List<String> bannedNames = ban.getStringList("bannedNames");
            if ((name == null || name.isBlank() || name.equalsIgnoreCase("IP")) && !bannedNames.isEmpty()) {
                name = bannedNames.getFirst();
            }
            long created = parseDate(ban.getString("date", ""), now);
            Long expiresAt = "temp".equalsIgnoreCase(mode) && until > 0 ? until : null;
            PunishmentStatus status = expiresAt != null && expiresAt <= now ? PunishmentStatus.EXPIRED : PunishmentStatus.ACTIVE;
            UUID uuid = identities.get(normalize(name));
            String signature = signature(name, reason, staff, created, until, ip);
            rows.add(new NewPunishment("legacy-" + signature, PunishmentScope.IP, uuid, name, ip, reason, staff, ORIGIN,
                    status, created, expiresAt, "legacy:ip:" + signature, serverId));
        }
    }

    private void importVoiceMutes(YamlConfiguration yaml, Map<String, UUID> identities, List<NewPunishment> rows, long now) {
        ConfigurationSection voice = yaml.getConfigurationSection("voice");
        if (voice == null) {
            return;
        }
        for (String name : voice.getKeys(false)) {
            ConfigurationSection mute = voice.getConfigurationSection(name);
            if (mute == null || !mute.contains("reason")) {
                continue;
            }
            String reason = mute.getString("reason", "Motivo não informado");
            String staff = mute.getString("staff", "Servidor");
            String ip = mute.getString("ip", "");
            long until = mute.getLong("until", 0L);
            long created = parseDate(mute.getString("date", ""), now);
            Long expiresAt = until > 0 ? until : null;
            PunishmentStatus status = expiresAt != null && expiresAt <= now ? PunishmentStatus.EXPIRED : PunishmentStatus.ACTIVE;
            UUID uuid = identities.get(normalize(name));
            String signature = signature(name, reason, staff, created, until, ip);
            String caseId = "legacy-voice-" + signature;
            rows.add(new NewPunishment(caseId, PunishmentScope.VOICE, uuid, name, null, reason, staff, ORIGIN,
                    status, created, expiresAt, "legacy:voice:" + signature, serverId));
            rows.add(new NewPunishment(caseId, PunishmentScope.CHAT, uuid, name, null, reason, staff, ORIGIN,
                    status, created, expiresAt, "legacy:chat:" + signature, serverId));
        }
    }

    /** Arquivos antigos podem ter IPs quebrados em seções aninhadas (192 > 168 > 0 > 1). */
    private void collectIpSections(ConfigurationSection section, String prefix, Map<String, ConfigurationSection> out) {
        if (hasMetadata(section) && !prefix.isBlank()) {
            out.put(prefix, section);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }
            collectIpSections(child, prefix.isBlank() ? key : prefix + "." + key, out);
        }
    }

    private static boolean hasMetadata(ConfigurationSection section) {
        return section.contains("reason") || section.contains("mode") || section.contains("bannedNames")
                || section.contains("name");
    }

    private static long parseDate(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).parse(value).getTime();
        } catch (ParseException e) {
            return fallback;
        }
    }

    private static String signature(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = Arrays.stream(values).map(String::valueOf).collect(Collectors.joining("|"));
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ipSignature(String ip, String reason, String mode, long until) {
        return normalize(ip) + "|" + normalize(reason) + "|" + normalize(mode) + "|" + until;
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
