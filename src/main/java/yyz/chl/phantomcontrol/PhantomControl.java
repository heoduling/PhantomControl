package yyz.chl.phantomcontrol;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import yyz.chl.phantomcontrol.util.PluginLifecycle;
import yyz.chl.phantomcontrol.listener.HotUnloadGuard;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import yyz.chl.phantomcontrol.api.DefaultPhantomControlAPI;
import yyz.chl.phantomcontrol.api.PhantomControlAPI;
import yyz.chl.phantomcontrol.command.CommandManager;
import yyz.chl.phantomcontrol.listener.ListenerManager;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.DatabaseManager;
import yyz.chl.phantomcontrol.manager.GUIManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;
import yyz.chl.phantomcontrol.util.MessageUtil;

public class PhantomControl extends JavaPlugin {
    public static PhantomControl instance;
    private PluginLifecycle lifecycle;
    private HotUnloadGuard hotUnloadGuard;
    private Runnable placeholderCleanup;
    private volatile CompletableFuture<Void> hotUnloadPreparation;
    public PluginLifecycle getLifecycle() { return lifecycle; }
    public boolean isAccepting() { return lifecycle != null && lifecycle.isAccepting(); }
    public boolean isPreparedForHotUnload() {
        return hotUnloadPreparation != null && hotUnloadPreparation.isDone()
                && !hotUnloadPreparation.isCompletedExceptionally();
    }
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PhantomManager phantomManager;
    private GUIManager guiManager;
    private CommandManager commandManager;
    private ListenerManager listenerManager;
    private MessageUtil messageUtil;
    private PhantomControlAPI api;
    private Object autoSaveTaskId;
    private int activeAutoSaveInterval = -1;

    @Override
    public void onEnable() {
        instance = this;
        lifecycle = new PluginLifecycle(this);
        hotUnloadPreparation = null;
        databaseManager = null;
        commandManager = null;
        guiManager = null;
        api = null;
        placeholderCleanup = null;
        
        if (!isPaperRuntime()) {
            getLogger().severe("PhantomControl 仅支持 Paper 或 Folia 服务器，不再支持 Spigot。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        yyz.chl.phantomcontrol.util.SchedulerUtil.setPluginEnabled(true);
        
        configManager = new ConfigManager(this);
        
        if (!configManager.validateConfig()) {
            getLogger().severe("配置验证失败，插件已禁用。请检查配置文件并修复错误。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        try {
            databaseManager = new DatabaseManager(this, configManager);
        } catch (RuntimeException e) {
            getLogger().severe("数据库初始化失败，插件已禁用: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        phantomManager = new PhantomManager(this, databaseManager, configManager);
        api = new DefaultPhantomControlAPI(phantomManager, databaseManager);
        getServer().getServicesManager().register(PhantomControlAPI.class, api, this, ServicePriority.Normal);
        
        guiManager = new GUIManager(this, phantomManager, configManager);
        
        messageUtil = new MessageUtil(configManager);
        
        commandManager = new CommandManager(this, configManager, phantomManager, guiManager, messageUtil);
        
        listenerManager = new ListenerManager(this, databaseManager, phantomManager, guiManager, configManager, messageUtil);
        
        startAutoSaveTask();
        
        registerPlaceholderAPI();
        hotUnloadGuard = new HotUnloadGuard(this);
        getServer().getPluginManager().registerEvents(hotUnloadGuard, this);
        hotUnloadGuard.registerGentleUnload();
        // Hot enable must initialise players who will not emit another join event.
        for (Player player : getServer().getOnlinePlayers()) {
            lifecycle.entity(player, () -> loadOnlinePlayer(player));
        }
        
        getLogger().info("PhantomControl 已成功加载！作者：CHL_chun");
    }

    private boolean isPaperRuntime() {
        try {
            Class.forName("com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    public void registerPlaceholderAPI() {
        if (placeholderCleanup != null) return;
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            var expansion = new yyz.chl.phantomcontrol.placeholder.PhantomControlPlaceholder(this, phantomManager, configManager);
            if (expansion.register()) {
                placeholderCleanup = expansion::unregister;
                getLogger().info("已成功注册PlaceholderAPI扩展！");
            } else getLogger().warning("PlaceholderAPI 扩展注册失败");
        } else {
            getLogger().info("未检测到PlaceholderAPI，跳过扩展注册。");
        }
    }

    public void unregisterPlaceholderAPI() {
        if (placeholderCleanup != null) {
            Runnable cleanup = placeholderCleanup;
            placeholderCleanup = null;
            cleanup.run();
        }
    }

    public void loadOnlinePlayer(Player player) {
        if (!isAccepting()) return;
        PluginLifecycle cycle = lifecycle;
        DatabaseManager database = databaseManager;
        PhantomManager manager = phantomManager;
        database.loadPlayerData(player).whenComplete((ignored, error) -> {
            if (error != null) {
                if (cycle.isAccepting()) getLogger().warning("加载玩家设置失败: " + error.getMessage());
                return;
            }
            cycle.entity(player, () -> manager.applyPhantomSettings(player));
        });
    }

    public synchronized CompletableFuture<Void> prepareForHotUnload() {
        if (hotUnloadPreparation != null && (!hotUnloadPreparation.isCompletedExceptionally()
                || lifecycle.pendingCount() != 0)) return hotUnloadPreparation;
        cancelAutoSaveTask();
        hotUnloadPreparation = lifecycle.stopAndDrain()
                .thenCompose(ignored -> guiManager.closeMenus())
                .thenCompose(ignored -> lifecycle.async(() -> {
                    databaseManager.saveAllDataAsync().join();
                    databaseManager.closeConnection();
                    databaseManager.verifyClosed();
                    return (Void) null;
                }, true))
                // PlugManX Beta.2 caches reflected fields of commands it removes. Remove our own
                // commands/help before handing off, so its cache cannot retain this classloader.
                .thenCompose(ignored -> lifecycle.global(() -> {
                    if (commandManager != null) commandManager.unregisterCommands();
                }, true))
                .orTimeout(20, TimeUnit.SECONDS);
        hotUnloadPreparation.whenComplete((ignored, error) -> {
            if (error != null) getLogger().log(java.util.logging.Level.SEVERE,
                    "热卸载准备失败，保持停止接单，未继续卸载；请处理存储错误后重试或完整重启", error);
        });
        return hotUnloadPreparation;
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) lifecycle.abort();
        yyz.chl.phantomcontrol.util.SchedulerUtil.setPluginEnabled(false);
        cancelAutoSaveTask();
        try {
            cleanup("PlaceholderAPI", this::unregisterPlaceholderAPI);
            if (hotUnloadGuard != null) cleanup("PlugManX", hotUnloadGuard::unregisterGentleUnload);
            if (commandManager != null) cleanup("命令", commandManager::unregisterCommands);
            cleanup("监听器", () -> HandlerList.unregisterAll(this));
            cleanup("API 服务", () -> getServer().getServicesManager().unregisterAll(this));
            api = null;
            if (guiManager != null) guiManager.clearSessions();
            if (databaseManager != null) cleanup("存储", databaseManager::closeConnection);
        } finally {
            if (isPaperRuntime()) yyz.chl.phantomcontrol.util.SchedulerUtil.shutdown();
            instance = null;
        }
        getLogger().info("PhantomControl 已成功卸载！");
    }

    private void cleanup(String resource, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { getLogger().log(java.util.logging.Level.SEVERE, "清理失败: " + resource, error); }
    }

    public static PhantomControl getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PhantomManager getPhantomManager() {
        return phantomManager;
    }

    public PhantomControlAPI getAPI() {
        return api;
    }
    
    public CommandManager getCommandManager() {
        return commandManager;
    }
    
    public GUIManager getGUIManager() {
        return guiManager;
    }
    
    /** Blocking compatibility entry point; use reloadAllAsync from commands. */
    public synchronized ReloadResult reloadAll() {
        if (!isAccepting()) throw new IllegalStateException("插件正在停止");
        ConfigManager.RuntimeConfigSnapshot previousConfig = configManager.snapshotRuntimeConfig();
        boolean databaseReloaded;

        try {
            configManager.reloadConfig();
            databaseReloaded = databaseManager.reloadDatabase();
        } catch (RuntimeException e) {
            configManager.restoreRuntimeConfig(previousConfig);
            throw e;
        }

        phantomManager.reloadConfig();
        guiManager.refreshGUIConfig();
        refreshAutoSaveTask();

        return new ReloadResult(databaseReloaded,
                !commandManager.isConfiguredCommandRegistrationCurrent());
    }

    public java.util.concurrent.CompletableFuture<ReloadResult> reloadAllAsync() {
        return lifecycle.async(this::reloadAll);
    }

    private void startAutoSaveTask() {
        int autoSaveInterval = configManager.getInt("database.auto-save-interval");
        this.autoSaveTaskId = createAutoSaveTask(autoSaveInterval);
        this.activeAutoSaveInterval = autoSaveInterval;
    }

    private void refreshAutoSaveTask() {
        int newInterval = configManager.getInt("database.auto-save-interval");
        if (newInterval == activeAutoSaveInterval) {
            return;
        }

        Object newTaskId = createAutoSaveTask(newInterval);
        Object oldTaskId = this.autoSaveTaskId;
        this.autoSaveTaskId = newTaskId;
        this.activeAutoSaveInterval = newInterval;
        if (oldTaskId != null) {
            yyz.chl.phantomcontrol.util.SchedulerUtil.cancelTask(oldTaskId);
        }
    }

    private Object createAutoSaveTask(int interval) {
        if (interval <= 0 || !isAccepting()) {
            return null;
        }
        return yyz.chl.phantomcontrol.util.SchedulerUtil.runAsyncTimer(
                databaseManager::saveAllData, interval, interval);
    }

    private void cancelAutoSaveTask() {
        if (this.autoSaveTaskId != null) {
            yyz.chl.phantomcontrol.util.SchedulerUtil.cancelTask(this.autoSaveTaskId);
            this.autoSaveTaskId = null;
        }
        this.activeAutoSaveInterval = -1;
    }

    public record ReloadResult(boolean databaseReloaded, boolean commandsRequireRestart) {
    }
}
