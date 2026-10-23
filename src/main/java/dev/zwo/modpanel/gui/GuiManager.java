package dev.zwo.modpanel.gui;

import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.gui.PanelHolder.Action;
import dev.zwo.modpanel.gui.PanelHolder.Click;
import dev.zwo.modpanel.gui.PanelHolder.Kind;
import dev.zwo.modpanel.gui.PanelHolder.View;
import dev.zwo.modpanel.model.ActionResult;
import dev.zwo.modpanel.model.Actor;
import dev.zwo.modpanel.model.PunishmentRecord;
import dev.zwo.modpanel.model.PunishmentScope;
import dev.zwo.modpanel.service.MuteService;
import dev.zwo.modpanel.service.PunishmentService;
import dev.zwo.modpanel.service.Tasks;
import dev.zwo.modpanel.service.TargetResolver;
import dev.zwo.modpanel.service.TargetResolver.ResolvedTarget;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.util.Durations;
import dev.zwo.modpanel.util.Ips;
import dev.zwo.modpanel.util.Placeholders;
import dev.zwo.modpanel.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Painéis de inventário. Os dados são carregados fora da thread principal. */
public final class GuiManager implements Listener {

    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_MAIN = 49;
    private static final int SLOT_REFRESH = 50;
    private static final int SLOT_NEXT = 53;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private record MainData(int bannedPlayers, int tempBans, int bannedIps, int voice, int chat) {
    }

    private record ListData(List<PunishmentRecord> records, boolean hasNext) {
    }

    private record ProfileData(ResolvedTarget target, ListData list) {
    }

    private final PunishmentRepository repository;
    private final PunishmentService punishments;
    private final MuteService mutes;
    private final TargetResolver resolver;
    private final Messages messages;
    private final Tasks tasks;
    private final Supplier<Settings> settings;
    private final Supplier<FileConfiguration> config;
    private final Logger logger;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public GuiManager(PunishmentRepository repository, PunishmentService punishments, MuteService mutes,
                      TargetResolver resolver, Messages messages, Tasks tasks, Supplier<Settings> settings,
                      Supplier<FileConfiguration> config, Logger logger) {
        this.repository = repository;
        this.punishments = punishments;
        this.mutes = mutes;
        this.resolver = resolver;
        this.messages = messages;
        this.tasks = tasks;
        this.settings = settings;
        this.config = config;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- abrir

    public void openMain(Player player) {
        tasks.async(() -> {
            List<PunishmentRecord> profiles = repository.active(PunishmentScope.PROFILE);
            int banned = distinct(profiles).size();
            int temp = distinct(profiles.stream().filter(PunishmentRecord::temporary).toList()).size();
            int ips = distinct(repository.active(PunishmentScope.IP)).size();
            int voice = distinct(repository.active(PunishmentScope.VOICE)).size();
            int chat = distinct(repository.active(PunishmentScope.CHAT)).size();
            return new MainData(banned, temp, ips, voice, chat);
        }).whenComplete((data, error) -> tasks.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                fail(player, error);
                return;
            }
            showMain(player, data);
        }));
    }

    public void openList(Player player, View view, int page) {
        int perPage = settings.get().perPage();
        int current = Math.max(1, page);
        tasks.async(() -> {
            List<PunishmentRecord> records = switch (view) {
                case BANS -> distinct(repository.active(PunishmentScope.PROFILE));
                case TEMP_BANS -> distinct(repository.active(PunishmentScope.PROFILE).stream()
                        .filter(PunishmentRecord::temporary).toList());
                case IP_BANS -> distinct(repository.active(PunishmentScope.IP));
                case MUTES -> distinct(repository.active(EnumSet.of(PunishmentScope.VOICE, PunishmentScope.CHAT)));
                default -> List.of();
            };
            int from = Math.min(records.size(), (current - 1) * perPage);
            int to = Math.min(records.size(), from + perPage);
            return new ListData(new ArrayList<>(records.subList(from, to)), to < records.size());
        }).whenComplete((data, error) -> tasks.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                fail(player, error);
                return;
            }
            showList(player, view, current, data);
        }));
    }

    public void openBans(Player player, int page, boolean temporary) {
        openList(player, temporary ? View.TEMP_BANS : View.BANS, page);
    }

    public void openIpBans(Player player, int page) {
        openList(player, View.IP_BANS, page);
    }

    public void openMutes(Player player, int page) {
        openList(player, View.MUTES, page);
    }

    public void openProfile(Player player, String name, int page) {
        int perPage = settings.get().perPage();
        int current = Math.max(1, page);
        tasks.async(() -> {
            ResolvedTarget target = resolver.resolve(name);
            List<PunishmentRecord> history = punishments.history(target, current, perPage);
            boolean hasNext = history.size() > perPage;
            List<PunishmentRecord> visible = hasNext ? new ArrayList<>(history.subList(0, perPage)) : history;
            return new ProfileData(target, new ListData(visible, hasNext));
        }).whenComplete((data, error) -> tasks.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                fail(player, error);
                return;
            }
            showProfile(player, data.target().name(), current, data.list());
        }));
    }

    // -------------------------------------------------------------- montar

    private void showMain(Player player, MainData data) {
        PanelHolder holder = create(View.MAIN, 1, null, title("main", "&8ModPanel"));
        Map<String, String> values = Placeholders.of(
                "banned_players", data.bannedPlayers(),
                "temp_bans", data.tempBans(),
                "banned_ips", data.bannedIps(),
                "voice_mutes", data.voice(),
                "chat_mutes", data.chat());

        putConfigured(holder, "bans", 11, Material.RED_CONCRETE, "&cBans ativos",
                List.of("&7Perfis: &f{banned_players}", "&eClique para abrir"),
                Action.of(Kind.OPEN_BANS), with(values, data.bannedPlayers()));
        putConfigured(holder, "tempbans", 13, Material.CLOCK, "&eBans temporários",
                List.of("&7Punições com expiração: &f{temp_bans}", "&eClique para abrir"),
                Action.of(Kind.OPEN_TEMP_BANS), with(values, data.tempBans()));
        putConfigured(holder, "ipbans", 15, Material.IRON_BARS, "&4Bans de IP",
                List.of("&7IPs ativos: &f{banned_ips}", "&eClique para abrir"),
                Action.of(Kind.OPEN_IP_BANS), with(values, data.bannedIps()));
        putConfigured(holder, "voice", 29, Material.NOTE_BLOCK, "&bMutes",
                List.of("&7Voz: &f{voice_mutes}", "&7Chat: &f{chat_mutes}", "&eClique para abrir"),
                Action.of(Kind.OPEN_MUTES), with(values, data.voice()));

        if (settings.get().allows(player, "bulk")) {
            putConfigured(holder, "unbanall", 31, Material.LAVA_BUCKET, "&cDesbanir todos",
                    List.of("&4Ação destrutiva", "&eExige confirmação"), Action.of(Kind.UNBAN_ALL), values);
            putConfigured(holder, "unmuteall", 33, Material.MILK_BUCKET, "&aDesmutar todos",
                    List.of("&eExige confirmação"), Action.of(Kind.UNMUTE_ALL), values);
        }
        decorate(holder);
        player.openInventory(holder.getInventory());
    }

    private void showList(Player player, View view, int page, ListData data) {
        String key = switch (view) {
            case BANS -> "bans";
            case TEMP_BANS -> "tempbans";
            case IP_BANS -> "ipbans";
            default -> "voice";
        };
        String fallback = switch (view) {
            case BANS -> "&8Bans ativos";
            case TEMP_BANS -> "&8Bans temporários";
            case IP_BANS -> "&8Bans de IP";
            default -> "&8Mutes ativos";
        };
        PanelHolder holder = create(view, page, null, title(key, fallback));
        Kind remove = switch (view) {
            case IP_BANS -> Kind.UNBAN_IP;
            case MUTES -> Kind.UNMUTE;
            default -> Kind.UNBAN_PROFILE;
        };

        List<PunishmentRecord> records = data.records();
        for (int i = 0; i < records.size() && i < CONTENT.length; i++) {
            PunishmentRecord record = records.get(i);
            String value = remove == Kind.UNBAN_IP ? record.targetIp() : record.targetName();
            boolean profileLink = hasProfile(record);
            holder.getInventory().setItem(CONTENT[i], recordItem(record, true, profileLink));
            holder.bind(CONTENT[i], new Action(remove, value),
                    profileLink ? new Action(Kind.OPEN_PROFILE, record.targetName()) : null);
        }
        if (records.isEmpty()) {
            holder.getInventory().setItem(22, item(Material.PAPER, messages.raw("gui.empty"), List.of()));
        }
        navigation(holder, page, data.hasNext());
        decorate(holder);
        player.openInventory(holder.getInventory());
    }

    private void showProfile(Player player, String name, int page, ListData data) {
        String title = title("profile", "&8Perfil: &f{player}").replace("{player}", name == null ? "?" : name);
        PanelHolder holder = create(View.PROFILE, page, name, title);
        List<PunishmentRecord> records = data.records();
        for (int i = 0; i < records.size() && i < CONTENT.length; i++) {
            PunishmentRecord record = records.get(i);
            boolean removable = record.active();
            holder.getInventory().setItem(CONTENT[i], recordItem(record, removable, false));
            if (removable) {
                Kind kind = switch (record.scope()) {
                    case PROFILE -> Kind.UNBAN_PROFILE;
                    case IP -> Kind.UNBAN_IP;
                    default -> Kind.UNMUTE;
                };
                String value = record.scope() == PunishmentScope.IP ? record.targetIp() : record.targetName();
                holder.bind(CONTENT[i], new Action(kind, value));
            }
        }
        if (records.isEmpty()) {
            holder.getInventory().setItem(22, item(Material.PAPER, messages.raw("profile.empty"), List.of()));
        }
        navigation(holder, page, data.hasNext());
        decorate(holder);
        player.openInventory(holder.getInventory());
    }

    private void showConfirmation(Player player, PanelHolder from, Action action) {
        PanelHolder holder = new PanelHolder(View.CONFIRM, from.page(), from.target(), from, action);
        Inventory inventory = Bukkit.createInventory(holder, 27, Text.component(title("confirm", "&4Confirmar operação")));
        holder.inventory(inventory);

        String target = displayTarget(action.value());
        List<String> lore = messages.lines("gui.confirm-lore").stream()
                .map(line -> messages.apply(line, Placeholders.of("target", target)))
                .toList();
        inventory.setItem(11, item(Material.LIME_CONCRETE, messages.raw("gui.confirm"), lore));
        holder.bind(11, Action.of(Kind.CONFIRM));
        inventory.setItem(15, item(Material.RED_CONCRETE, messages.raw("gui.cancel"),
                List.of(messages.raw("gui.cancel-lore"))));
        holder.bind(15, Action.of(Kind.CANCEL));
        decorate(holder);
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------- cliques

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PanelHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        Click click = holder.click(slot);
        if (click == null) {
            return;
        }
        Action action = event.isRightClick() && click.right() != null ? click.right() : click.left();
        if (action != null) {
            // abrir/fechar inventário dentro do próprio evento de clique não é seguro
            tasks.nextTick(() -> {
                if (player.isOnline()) {
                    handle(player, holder, action);
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PanelHolder) {
            event.setCancelled(true);
        }
    }

    private void handle(Player player, PanelHolder holder, Action action) {
        switch (action.kind()) {
            case OPEN_MAIN -> openMain(player);
            case OPEN_BANS -> openList(player, View.BANS, 1);
            case OPEN_TEMP_BANS -> openList(player, View.TEMP_BANS, 1);
            case OPEN_IP_BANS -> openList(player, View.IP_BANS, 1);
            case OPEN_MUTES -> openList(player, View.MUTES, 1);
            case OPEN_PROFILE -> openProfile(player, action.value(), 1);
            case PREVIOUS -> reopen(player, holder, holder.page() - 1);
            case NEXT -> reopen(player, holder, holder.page() + 1);
            case REFRESH -> reopen(player, holder, holder.page());
            case UNBAN_PROFILE, UNBAN_IP, UNMUTE, UNBAN_ALL, UNMUTE_ALL -> {
                if (check(player, permissionFor(action.kind()))) {
                    showConfirmation(player, holder, action);
                }
            }
            case CONFIRM -> confirm(player, holder);
            case CANCEL -> reopen(player, holder.back(), holder.back() == null ? 1 : holder.back().page());
        }
    }

    private void confirm(Player player, PanelHolder holder) {
        Action pending = holder.pending();
        if (pending == null || !check(player, permissionFor(pending.kind()))) {
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            messages.send(player, "general.busy");
            return;
        }
        Actor actor = new Actor(player.getName(), player.getUniqueId(), Ips.normalize(player.getAddress()));
        CompletableFuture<ActionResult> future = switch (pending.kind()) {
            case UNBAN_PROFILE -> punishments.unbanPlayer(actor, pending.value());
            case UNBAN_IP -> punishments.unbanIp(actor, pending.value());
            case UNMUTE -> mutes.unmute(actor, pending.value());
            case UNBAN_ALL -> punishments.unbanAll(actor);
            case UNMUTE_ALL -> mutes.unmuteAll(actor);
            default -> CompletableFuture.completedFuture(ActionResult.fail(messages.raw("gui.invalid-action")));
        };
        player.closeInventory();
        future.whenComplete((result, error) -> {
            busy.remove(player.getUniqueId());
            tasks.sync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                messages.sendResult(player, result != null ? result
                        : ActionResult.fail(messages.format("general.database-error", "action", "executar a ação")));
                PanelHolder back = holder.back();
                reopen(player, back, back == null ? 1 : back.page());
            });
        });
    }

    private void reopen(Player player, PanelHolder holder, int page) {
        if (holder == null) {
            openMain(player);
            return;
        }
        int target = Math.max(1, page);
        switch (holder.view()) {
            case BANS, TEMP_BANS, IP_BANS, MUTES -> openList(player, holder.view(), target);
            case PROFILE -> openProfile(player, holder.target() == null ? player.getName() : holder.target(), target);
            case CONFIRM -> reopen(player, holder.back(), target);
            default -> openMain(player);
        }
    }

    private boolean check(Player player, String permission) {
        if (settings.get().allows(player, permission)) {
            return true;
        }
        messages.send(player, "general.no-permission");
        player.closeInventory();
        return false;
    }

    private static String permissionFor(Kind kind) {
        return switch (kind) {
            case UNBAN_PROFILE -> "unban";
            case UNBAN_IP -> "unban-ip";
            case UNMUTE -> "unmute";
            default -> "bulk";
        };
    }

    // -------------------------------------------------------------- itens

    private ItemStack recordItem(PunishmentRecord record, boolean removable, boolean profileLink) {
        Material material = switch (record.scope()) {
            case PROFILE -> Material.PLAYER_HEAD;
            case IP -> Material.IRON_BARS;
            case VOICE -> Material.NOTE_BLOCK;
            case CHAT -> Material.WRITABLE_BOOK;
        };
        String target = record.scope() == PunishmentScope.IP ? record.targetIp() : record.targetName();
        Map<String, String> values = Placeholders.of(
                "target", displayTarget(target),
                "player", displayTarget(record.targetName()),
                "ip", displayTarget(record.targetIp()),
                "case", shortCase(record.caseId()),
                "scope", messages.scope(record.scope()),
                "status", messages.status(record.status()),
                "reason", record.reason(),
                "actor", record.actor(),
                "origin", record.origin(),
                "created", DATE.format(Instant.ofEpochMilli(record.createdAt())),
                "expires", remaining(record.expiresAt()));

        List<String> lore = new ArrayList<>();
        for (String line : messages.lines("gui.record.lore")) {
            lore.add(messages.apply(line, values));
        }
        if (removable) {
            lore.add(messages.raw("gui.record.click-remove"));
        }
        if (profileLink) {
            lore.add(messages.raw("gui.record.click-profile"));
        }
        return item(material, messages.apply(messages.raw("gui.record.name"), values), lore);
    }

    private void navigation(PanelHolder holder, int page, boolean hasNext) {
        Inventory inventory = holder.getInventory();
        if (page > 1) {
            inventory.setItem(SLOT_PREVIOUS, item(Material.ARROW, messages.raw("gui.previous"), List.of()));
            holder.bind(SLOT_PREVIOUS, Action.of(Kind.PREVIOUS));
        }
        if (hasNext) {
            inventory.setItem(SLOT_NEXT, item(Material.ARROW, messages.raw("gui.next"), List.of()));
            holder.bind(SLOT_NEXT, Action.of(Kind.NEXT));
        }
        inventory.setItem(SLOT_MAIN, item(Material.NETHER_STAR, messages.raw("gui.back"), List.of()));
        holder.bind(SLOT_MAIN, Action.of(Kind.OPEN_MAIN));
        inventory.setItem(SLOT_REFRESH, item(Material.SUNFLOWER, messages.raw("gui.refresh"),
                List.of(messages.format("gui.refresh-lore", "page", page))));
        holder.bind(SLOT_REFRESH, Action.of(Kind.REFRESH));
    }

    private void putConfigured(PanelHolder holder, String key, int slot, Material material, String name,
                               List<String> lore, Action action, Map<String, String> values) {
        FileConfiguration c = config.get();
        String path = "gui.main.items." + key;
        String legacyKey = switch (key) {
            case "bans" -> "banned-players";
            case "tempbans" -> "temp-bans";
            case "ipbans" -> "ip-bans";
            case "voice" -> "voice-mutes";
            default -> null;
        };
        if (!c.isConfigurationSection(path) && legacyKey != null && c.isConfigurationSection("gui-items.main." + legacyKey)) {
            path = "gui-items.main." + legacyKey;
        }
        if (!c.getBoolean(path + ".enabled", true)) {
            return;
        }
        int configuredSlot = c.getInt(path + ".slot", slot);
        if (configuredSlot < 0 || configuredSlot >= holder.getInventory().getSize()) {
            configuredSlot = slot;
        }
        Material configured = Material.matchMaterial(c.getString(path + ".material", material.name()));
        String display = messages.apply(c.getString(path + ".name", name), values);
        List<String> lines = c.isList(path + ".lore") ? c.getStringList(path + ".lore") : lore;
        List<String> rendered = lines.stream().map(line -> messages.apply(line, values)).toList();
        holder.getInventory().setItem(configuredSlot, item(configured == null ? material : configured, display, rendered));
        holder.bind(configuredSlot, action);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            if (!lore.isEmpty()) {
                meta.lore(Text.lore(lore));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private PanelHolder create(View view, int page, String target, String title) {
        PanelHolder holder = new PanelHolder(view, page, target);
        holder.inventory(Bukkit.createInventory(holder, 54, Text.component(title)));
        return holder;
    }

    private void decorate(PanelHolder holder) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        Inventory inventory = holder.getInventory();
        boolean list = inventory.getSize() == 54 && holder.view() != View.MAIN;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null && !(list && isContent(slot))) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private static boolean isContent(int slot) {
        for (int content : CONTENT) {
            if (content == slot) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- textos

    private String title(String key, String fallback) {
        FileConfiguration c = config.get();
        String path = "gui.titles." + key;
        if (key.equals("voice") && !c.contains(path) && c.contains("gui.titles.voice-mutes")) {
            path = "gui.titles.voice-mutes";
        }
        return c.getString(path, fallback);
    }

    private String remaining(Long expiresAt) {
        if (expiresAt == null) {
            return messages.raw("time.never");
        }
        long left = expiresAt - System.currentTimeMillis();
        return left <= 0 ? messages.raw("time.expired") : Durations.format(left);
    }

    private String displayTarget(String target) {
        return target == null || target.isBlank() ? messages.raw("gui.record.unknown") : target;
    }

    private static String shortCase(String caseId) {
        if (caseId == null) {
            return "-";
        }
        return caseId.length() <= 8 ? caseId : caseId.substring(0, 8);
    }

    private static boolean hasProfile(PunishmentRecord record) {
        String name = record.targetName();
        return name != null && !name.isBlank() && !name.equalsIgnoreCase("IP") && !name.equalsIgnoreCase("Desconhecido");
    }

    private static Map<String, String> with(Map<String, String> values, int total) {
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put("total", String.valueOf(total));
        return copy;
    }

    /** Um item por alvo, mantendo o registro mais recente. */
    private static List<PunishmentRecord> distinct(Collection<PunishmentRecord> records) {
        Map<String, PunishmentRecord> unique = new LinkedHashMap<>();
        for (PunishmentRecord record : records) {
            unique.putIfAbsent(record.targetKey(), record);
        }
        return new ArrayList<>(unique.values());
    }

    private void fail(Player player, Throwable error) {
        logger.severe("Falha ao montar o painel: " + Tasks.unwrap(error).getMessage());
        messages.send(player, "gui.load-error");
    }
}
