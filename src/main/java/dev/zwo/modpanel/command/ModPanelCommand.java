package dev.zwo.modpanel.command;

import dev.zwo.modpanel.ModPanelPlugin;
import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.model.ActionResult;
import dev.zwo.modpanel.model.Actor;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.service.MuteService;
import dev.zwo.modpanel.service.SyncService;
import dev.zwo.modpanel.service.Tasks;
import dev.zwo.modpanel.service.TargetResolver.ResolvedTarget;
import dev.zwo.modpanel.storage.SqliteImporter;
import dev.zwo.modpanel.util.Durations;
import dev.zwo.modpanel.util.Ips;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class ModPanelCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Set<String> PLAYER_TARGET = Set.of("mpban", "mpbanperfil", "mptempban", "mpipban", "mpmute", "mpperfil");

    private final ModPanelPlugin plugin;

    public ModPanelCommand(ModPanelPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.messages();
    }

    private Settings settings() {
        return plugin.settings();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "modpanel" -> openPanel(sender, "main", args);
                case "banpanel" -> openPanel(sender, "bans", args);
                case "tempbanpanel" -> openPanel(sender, "tempbans", args);
                case "ipbanpanel" -> openPanel(sender, "ipbans", args);
                case "voicepanel" -> openPanel(sender, "voice", args);
                case "mpperfil" -> profile(sender, args);
                case "mpban" -> ban(sender, args, false);
                case "mpbanperfil" -> profileBan(sender, args);
                case "mptempban" -> ban(sender, args, true);
                case "mpipban" -> ipBan(sender, args);
                case "mpunban" -> unban(sender, args);
                case "mpunbanip" -> unbanIp(sender, args);
                case "mpunbanall" -> unbanAll(sender, args);
                case "mpmute" -> mute(sender, args);
                case "mpunmute" -> unmute(sender, args);
                case "mpunmuteall" -> unmuteAll(sender, args);
                case "mpmuteradius" -> muteRadius(sender, args);
                case "mpsyncbans" -> sync(sender);
                case "mp" -> admin(sender, args);
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Erro no comando /" + label, e);
            messages().send(sender, "general.command-error");
        }
        return true;
    }

    // --------------------------------------------------------------- painéis

    private void openPanel(CommandSender sender, String panel, String[] args) {
        if (!allowed(sender, "view")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "general.only-player");
            return;
        }
        int page = page(args.length > 0 ? args[0] : "1");
        switch (panel) {
            case "bans" -> plugin.gui().openBans(player, page, false);
            case "tempbans" -> plugin.gui().openBans(player, page, true);
            case "ipbans" -> plugin.gui().openIpBans(player, page);
            case "voice" -> plugin.gui().openMutes(player, page);
            default -> plugin.gui().openMain(player);
        }
    }

    private void profile(CommandSender sender, String[] args) {
        if (!allowed(sender, "view")) {
            return;
        }
        String target;
        int page = 1;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                usage(sender, messages().raw("profile.usage"));
                return;
            }
            target = player.getName();
        } else {
            target = args[0];
            if (args.length > 1) {
                page = page(args[1]);
            }
        }

        if (sender instanceof Player player) {
            plugin.gui().openProfile(player, target, page);
            return;
        }

        int perPage = 10;
        int current = page;
        plugin.tasks().async(() -> {
            ResolvedTarget resolved = plugin.resolver().resolve(target);
            return Map.entry(resolved, plugin.punishments().history(resolved, current, perPage));
        }).whenComplete((data, error) -> plugin.tasks().sync(() -> {
            if (error != null) {
                plugin.tasks().log("Falha ao carregar o perfil de " + target, error);
                messages().send(sender, "general.command-error");
                return;
            }
            String name = data.getKey().name();
            List<PunishmentRecord> history = data.getValue();
            messages().send(sender, "profile.header", "player", name);
            if (history.isEmpty()) {
                messages().send(sender, "profile.empty");
                return;
            }
            for (PunishmentRecord record : history.subList(0, Math.min(perPage, history.size()))) {
                messages().send(sender, "profile.line",
                        "id", record.id(),
                        "scope", messages().scope(record.scope()),
                        "status", messages().status(record.status()),
                        "reason", record.reason(),
                        "date", DATE.format(Instant.ofEpochMilli(record.createdAt())));
            }
            if (history.size() > perPage) {
                messages().send(sender, "profile.more", "player", name, "next", current + 1);
            }
        }));
    }

    // ------------------------------------------------------------------ bans

    private void ban(CommandSender sender, String[] args, boolean temporary) {
        if (!allowed(sender, "ban")) {
            return;
        }
        if ((!temporary && args.length < 2) || (temporary && args.length < 3)) {
            usage(sender, temporary ? "/mptempban <jogador> <tempo> <motivo>" : "/mpban <jogador> <motivo>");
            return;
        }
        Actor actor = actor(sender);
        if (temporary) {
            Duration duration = Durations.parse(args[1]).orElse(null);
            if (duration == null) {
                messages().send(sender, "general.invalid-duration");
                return;
            }
            reply(sender, plugin.punishments().tempBan(actor, args[0], duration, join(args, 2)));
        } else {
            reply(sender, plugin.punishments().banPermanent(actor, args[0], join(args, 1)));
        }
    }

    /** Ban permanente só do perfil/UUID: não toca em IP (seguro para automações e IPs compartilhados). */
    private void profileBan(CommandSender sender, String[] args) {
        if (!allowed(sender, "ban")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/mpbanperfil <jogador|uuid> <motivo>");
            return;
        }
        reply(sender, plugin.punishments().banProfileOnly(actor(sender), args[0], join(args, 1)));
    }

    private void ipBan(CommandSender sender, String[] args) {
        if (!allowed(sender, "ipban")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/mpipban <jogador|ip> <motivo>");
            return;
        }
        reply(sender, plugin.punishments().banIp(actor(sender), args[0], join(args, 1)));
    }

    private void unban(CommandSender sender, String[] args) {
        if (!allowed(sender, "unban")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/mpunban <jogador>");
            return;
        }
        reply(sender, plugin.punishments().unbanPlayer(actor(sender), args[0]));
    }

    private void unbanIp(CommandSender sender, String[] args) {
        if (!allowed(sender, "unban-ip")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/mpunbanip <ip>");
            return;
        }
        reply(sender, plugin.punishments().unbanIp(actor(sender), args[0]));
    }

    private void unbanAll(CommandSender sender, String[] args) {
        if (!allowed(sender, "bulk")) {
            return;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            messages().send(sender, "unban.all-confirm");
            return;
        }
        reply(sender, plugin.punishments().unbanAll(actor(sender)));
    }

    // ----------------------------------------------------------------- mutes

    private void mute(CommandSender sender, String[] args) {
        if (!allowed(sender, "mute")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/mpmute <jogador> <minutos|tempo> [motivo]");
            return;
        }
        int minutes = Durations.parseMinutes(args[1]);
        if (minutes <= 0) {
            usage(sender, messages().raw("mute.positive-minutes"));
            return;
        }
        String reason = args.length > 2 ? join(args, 2) : null;
        reply(sender, plugin.mutes().mute(actor(sender), args[0], minutes, reason));
    }

    private void unmute(CommandSender sender, String[] args) {
        if (!allowed(sender, "unmute")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/mpunmute <jogador>");
            return;
        }
        reply(sender, plugin.mutes().unmute(actor(sender), args[0]));
    }

    private void unmuteAll(CommandSender sender, String[] args) {
        if (!allowed(sender, "bulk")) {
            return;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            messages().send(sender, "mute.all-confirm");
            return;
        }
        reply(sender, plugin.mutes().unmuteAll(actor(sender)));
    }

    private void muteRadius(CommandSender sender, String[] args) {
        if (!allowed(sender, "mute-radius")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "general.only-player");
            return;
        }
        if (args.length < 2) {
            usage(sender, "/mpmuteradius <raio> <minutos|tempo> [motivo]");
            return;
        }
        Settings s = settings();
        int radius = positiveInt(args[0]);
        int minutes = Durations.parseMinutes(args[1]);
        if (radius <= 0 || radius > s.maxRadius() || minutes <= 0 || minutes > s.maxMuteMinutes()) {
            usage(sender, messages().raw("mute.radius-invalid"));
            return;
        }
        String reason = args.length > 2 ? join(args, 2) : messages().raw("mute.radius-reason");

        double max = (double) radius * radius;
        List<String> targets = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || s.allows(other, "bypass") || !other.getWorld().equals(player.getWorld())) {
                continue;
            }
            if (other.getLocation().distanceSquared(player.getLocation()) <= max) {
                targets.add(other.getName());
            }
        }

        CompletableFuture<MuteService.RadiusResult> future = plugin.mutes().muteRadius(actor(sender), targets, minutes, reason);
        future.whenComplete((result, error) -> plugin.tasks().sync(() -> {
            if (error != null) {
                plugin.tasks().log("Falha no mute em raio", error);
                messages().send(sender, "general.command-error");
                return;
            }
            messages().send(sender, "mute.radius-done", "count", result.muted());
            if (!result.failed().isEmpty()) {
                messages().send(sender, "mute.radius-failed", "players", String.join(", ", result.failed()));
            }
        }));
    }

    // ------------------------------------------------------------ administração

    private void sync(CommandSender sender) {
        if (!allowed(sender, "sync")) {
            return;
        }
        plugin.sync().sync("manual:" + sender.getName()).whenComplete((report, error) -> plugin.tasks().sync(() -> {
            if (error != null || report == null) {
                messages().send(sender, "sync.failed", "error", error == null ? "?" : Tasks.unwrap(error).getMessage());
                return;
            }
            sendReport(sender, report);
        }));
    }

    private void sendReport(CommandSender sender, SyncService.SyncReport report) {
        if (report.skipped()) {
            messages().send(sender, "sync.busy");
        } else if (!report.success()) {
            messages().send(sender, "sync.failed", "error", report.error());
        } else {
            messages().send(sender, "sync.result", "imported", report.imported(), "revoked", report.revokedExternal(),
                    "repaired", report.repaired(), "applied", report.applied(), "review", report.review());
        }
    }

    private void admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                if (!allowed(sender, "reload")) {
                    return;
                }
                plugin.reload();
                messages().send(sender, "general.reloaded");
                messages().send(sender, "general.reload-storage-note");
            }
            case "importar", "import" -> importSqlite(sender, args);
            default -> usage(sender, "/mp [status|reload|importar]");
        }
    }

    private void status(CommandSender sender) {
        if (!allowed(sender, "view")) {
            return;
        }
        plugin.tasks().async(() -> plugin.repository().activeCounts()).whenComplete((counts, error) -> plugin.tasks().sync(() -> {
            if (error != null) {
                plugin.tasks().log("Falha ao carregar o status", error);
                messages().send(sender, "general.command-error");
                return;
            }
            messages().send(sender, "status.counts",
                    "profiles", counts.get(PunishmentScope.PROFILE),
                    "ips", counts.get(PunishmentScope.IP),
                    "voice", counts.get(PunishmentScope.VOICE),
                    "chat", counts.get(PunishmentScope.CHAT));
            messages().send(sender, "status.native",
                    "profiles", plugin.gateway().profileBans().size(),
                    "ips", plugin.gateway().ipBans().size());
            Settings s = settings();
            messages().send(sender, "status.storage",
                    "storage", plugin.database().describe(),
                    "server", s.serverId(),
                    "network", s.network() ? "ativa" : "desativada");
        }));
    }

    private void importSqlite(CommandSender sender, String[] args) {
        if (!allowed(sender, "reload")) {
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("sqlite") || !args[2].equalsIgnoreCase("confirm")) {
            usage(sender, messages().raw("import.usage"));
            return;
        }
        Settings s = settings();
        if (s.storageType() != Settings.StorageType.MYSQL) {
            messages().send(sender, "import.not-mysql");
            return;
        }
        File file = new File(plugin.getDataFolder(), s.sqliteFile());
        if (!file.isFile()) {
            messages().send(sender, "import.missing-file", "file", file.getName());
            return;
        }
        messages().send(sender, "import.started");
        plugin.tasks().async(() -> {
            if (plugin.repository().countPunishments() > 0) {
                return null;
            }
            return new SqliteImporter(plugin.repository(), plugin.getLogger())
                    .importFrom(file, SqliteImporter.Layout.MODPANEL, s.tablePrefix(), s.serverId());
        }).whenComplete((result, error) -> plugin.tasks().sync(() -> {
            if (error != null) {
                plugin.tasks().log("Falha na importação do SQLite", error);
                messages().send(sender, "import.failed", "error", Tasks.unwrap(error).getMessage());
            } else if (result == null) {
                messages().send(sender, "import.not-empty");
            } else {
                messages().send(sender, "import.done", "punishments", result.punishments(),
                        "identities", result.identities());
                plugin.refreshCaches();
            }
        }));
    }

    // -------------------------------------------------------------- helpers

    private void reply(CommandSender sender, CompletableFuture<ActionResult> future) {
        future.whenComplete((result, error) -> plugin.tasks().sync(() -> {
            if (error != null || result == null) {
                messages().send(sender, "general.command-error");
                return;
            }
            messages().sendResult(sender, result);
        }));
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (settings().allows(sender, permission)) {
            return true;
        }
        messages().send(sender, "general.no-permission");
        return false;
    }

    private void usage(CommandSender sender, String usage) {
        messages().send(sender, "general.usage", "usage", usage);
    }

    private static Actor actor(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Actor(player.getName(), player.getUniqueId(), Ips.normalize(player.getAddress()));
        }
        return Actor.system(sender.getName());
    }

    private static int page(String value) {
        return Math.max(1, positiveInt(value));
    }

    private static int positiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    // ---------------------------------------------------------- tab-complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && PLAYER_TARGET.contains(name)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
        }
        if (args.length == 1 && name.equals("mpunban")) {
            return filter(plugin.cache().bannedNames(), args[0]);
        }
        if (args.length == 1 && name.equals("mpunmute")) {
            return filter(plugin.cache().mutedNames(), args[0]);
        }
        if (name.equals("mp")) {
            if (args.length == 1) {
                return filter(List.of("status", "reload", "importar"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("importar")) {
                return filter(List.of("sqlite"), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("importar")) {
                return filter(List.of("confirm"), args[2]);
            }
        }
        if (args.length == 1 && (name.equals("mpunbanall") || name.equals("mpunmuteall"))) {
            return filter(List.of("confirm"), args[0]);
        }
        if (args.length == 2 && name.equals("mptempban")) {
            return filter(List.of("10m", "1h", "12h", "1d", "7d", "30d"), args[1]);
        }
        if (args.length == 2 && (name.equals("mpmute") || name.equals("mpmuteradius"))) {
            return filter(List.of("10", "30", "60", "120", "1d"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
