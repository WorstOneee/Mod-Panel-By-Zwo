package dev.zwo.modpanel;

import dev.zwo.modpanel.command.ModPanelCommand;
import dev.zwo.modpanel.config.ConfigFiles;
import dev.zwo.modpanel.config.Messages;
import dev.zwo.modpanel.config.Settings;
import dev.zwo.modpanel.gui.GuiManager;
import dev.zwo.modpanel.listener.ChatListener;
import dev.zwo.modpanel.listener.ExternalCommandListener;
import dev.zwo.modpanel.listener.IdentityListener;
import dev.zwo.modpanel.listener.LoginListener;
import dev.zwo.modpanel.migration.SerpensUpgrade;
import dev.zwo.modpanel.paper.BanGateway;
import dev.zwo.modpanel.service.ActiveCache;
import dev.zwo.modpanel.service.MuteService;
import dev.zwo.modpanel.service.Notifier;
import dev.zwo.modpanel.service.PunishmentService;
import dev.zwo.modpanel.service.SyncService;
import dev.zwo.modpanel.service.Tasks;
import dev.zwo.modpanel.service.TargetResolver;
import dev.zwo.modpanel.storage.Database;
import dev.zwo.modpanel.storage.MysqlDatabase;
import dev.zwo.modpanel.storage.PunishmentRepository;
import dev.zwo.modpanel.storage.Schema;
import dev.zwo.modpanel.storage.SqliteDatabase;
import dev.zwo.modpanel.storage.Tables;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** ModPanel by Zwo. */
public final class ModPanelPlugin extends JavaPlugin {

    private static final String[] COMMANDS = {
            "modpanel", "banpanel", "tempbanpanel", "ipbanpanel", "voicepanel", "mpperfil",
            "mpban", "mpbanperfil", "mptempban", "mpipban", "mpunban", "mpunbanip", "mpunbanall",
            "mpmute", "mpunmute", "mpunmuteall", "mpmuteradius", "mpsyncbans", "mp"
    };

    private final List<BukkitTask> timers = new ArrayList<>();
    private final AtomicBoolean cleanupRunning = new AtomicBoolean();

    private volatile YamlConfiguration config;
    private volatile Settings settings;
    private Messages messages;
    private Tasks tasks;
    private Database database;
    private PunishmentRepository repository;
    private BanGateway gateway;
    private ActiveCache cache;
    private TargetResolver resolver;
    private PunishmentService punishments;
    private MuteService mutes;
    private SyncService sync;
    private GuiManager gui;

    @Override
    public void onEnable() {
        SerpensUpgrade.copyOldConfig(this);
        messages = new Messages(this, this::settings);
        loadConfiguration();

        int workers = settings.storageType() == Settings.StorageType.MYSQL
                ? Math.max(2, Math.min(4, settings.poolMaxSize()))
                : 1;
        tasks = new Tasks(this, workers);

        try {
            database = createDatabase();
            database.open();
            Schema.create(database);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Não foi possível iniciar o armazenamento (" + settings.storageType() + ")", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repository = new PunishmentRepository(database);

        try {
            SerpensUpgrade.importData(this, repository, settings.serverId());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Falha ao importar dados do SerpensModPanel; o plugin seguirá sem eles", e);
        }

        gateway = new BanGateway();
        cache = new ActiveCache();
        Notifier notifier = new Notifier(messages, this::config);
        resolver = new TargetResolver(repository, tasks, this::settings);
        punishments = new PunishmentService(repository, gateway, resolver, tasks, messages, notifier, cache,
                this::settings, getLogger());
        mutes = new MuteService(repository, resolver, tasks, messages, cache, this::settings, getLogger());
        sync = new SyncService(repository, gateway, tasks, notifier, cache, this::settings, getLogger());
        gui = new GuiManager(repository, punishments, mutes, resolver, messages, tasks, this::settings, this::config,
                getLogger());

        IdentityListener identities = new IdentityListener(repository, tasks, getLogger());
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(identities, this);
        pm.registerEvents(new LoginListener(repository, notifier, this::settings, getLogger()), this);
        pm.registerEvents(new ChatListener(cache, messages, this::settings), this);
        pm.registerEvents(new ExternalCommandListener(() ->
                Bukkit.getScheduler().runTaskLater(this, () -> sync.sync("comando-externo"), 2L)), this);
        pm.registerEvents(gui, this);

        registerCommands();
        for (Player online : Bukkit.getOnlinePlayers()) {
            identities.record(online);
        }
        scheduleTasks(true);

        getLogger().info("ModPanel v" + getPluginMeta().getVersion() + " ativo com " + database.describe()
                + (settings.network() ? " em rede como '" + settings.serverId() + "'." : "."));
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (tasks != null) {
            tasks.shutdown();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("ModPanel desligado.");
    }

    private void loadConfiguration() {
        config = ConfigFiles.loadWithDefaults(this, new File(getDataFolder(), "config.yml"), "config.yml");
        settings = Settings.load(config, getLogger());
        messages.load();
    }

    private Database createDatabase() {
        Tables tables = Tables.withPrefix(settings.tablePrefix());
        if (settings.storageType() == Settings.StorageType.MYSQL) {
            return new MysqlDatabase(settings, tables, getLogger());
        }
        return new SqliteDatabase(new File(getDataFolder(), settings.sqliteFile()), tables, getLogger());
    }

    private void registerCommands() {
        ModPanelCommand executor = new ModPanelCommand(this);
        for (String name : COMMANDS) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().severe("Comando ausente no plugin.yml: " + name);
                continue;
            }
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /** Recarrega config.yml e messages.yml. O armazenamento só muda reiniciando. */
    public void reload() {
        loadConfiguration();
        cancelTasks();
        scheduleTasks(false);
    }

    private void scheduleTasks(boolean startup) {
        // limpeza de expirados a cada 30s
        timers.add(Bukkit.getScheduler().runTaskTimer(this, this::cleanup, 20L, 600L));

        if (startup) {
            timers.add(Bukkit.getScheduler().runTaskLater(this, () -> tasks.async(() -> {
                mutes.reconcileActive();
                cache.refreshNames(repository);
                return null;
            }).exceptionally(error -> {
                tasks.log("Falha ao preparar os caches", error);
                return null;
            }), 20L));
        }

        long interval = settings.syncIntervalSeconds() * 20L;
        if (settings.syncEnabled()) {
            if (startup) {
                timers.add(Bukkit.getScheduler().runTaskLater(this, () -> sync.sync("inicialização"), 40L));
            }
            timers.add(Bukkit.getScheduler().runTaskTimer(this, () -> sync.sync("periódica"), interval, interval));
        }
        if (settings.network()) {
            timers.add(Bukkit.getScheduler().runTaskTimer(this, this::refreshCaches, interval, interval));
        }
    }

    private void cleanup() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            return;
        }
        tasks.async(() -> {
            punishments.cleanupExpired();
            mutes.cleanupExpired();
            return null;
        }).whenComplete((ignored, error) -> cleanupRunning.set(false));
    }

    public void refreshCaches() {
        tasks.async(() -> {
            cache.refreshNames(repository);
            mutes.refreshChatState();
            return null;
        }).exceptionally(error -> {
            getLogger().fine("Caches não atualizados: " + Tasks.unwrap(error).getMessage());
            return null;
        });
    }

    private void cancelTasks() {
        timers.forEach(BukkitTask::cancel);
        timers.clear();
    }

    public FileConfiguration config() {
        return config;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Tasks tasks() {
        return tasks;
    }

    public Database database() {
        return database;
    }

    public PunishmentRepository repository() {
        return repository;
    }

    public BanGateway gateway() {
        return gateway;
    }

    public ActiveCache cache() {
        return cache;
    }

    public TargetResolver resolver() {
        return resolver;
    }

    public PunishmentService punishments() {
        return punishments;
    }

    public MuteService mutes() {
        return mutes;
    }

    public SyncService sync() {
        return sync;
    }

    public GuiManager gui() {
        return gui;
    }
}
