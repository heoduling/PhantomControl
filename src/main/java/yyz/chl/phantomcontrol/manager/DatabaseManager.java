package yyz.chl.phantomcontrol.manager;

import org.bukkit.entity.Player;
import yyz.chl.phantomcontrol.PhantomControl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class DatabaseManager {
    private record CacheEntry(boolean value) {}

    private DatabaseHandler databaseHandler;
    private final Map<UUID, CacheEntry> playerDataCache = new ConcurrentHashMap<>();
    // Failed writes, including departed players, remain available for the next flush.
    private final Map<UUID, CacheEntry> pendingWrites = new HashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> loadingPlayers = new HashMap<>();
    private final java.util.Set<UUID> activePlayers = new java.util.HashSet<>();
    private final Object stateLock = new Object();
    private final ConfigManager configManager;
    private final PhantomControl plugin;
    private final ExecutorService databaseExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4096), runnable -> {
                Thread thread = new Thread(runnable, "PhantomControl-storage");
                thread.setDaemon(true);
                return thread;
            }) {
        @Override protected void terminated() {
            if (!closed) return;
            try {
                Map<UUID, CacheEntry> finalData;
                synchronized (stateLock) { finalData = snapshot(); }
                flush(finalData);
            } catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "停服保存失败", error);
            } finally {
                try { databaseHandler.closeConnection(); }
                catch (RuntimeException error) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "关闭数据库失败", error);
                }
            }
        }
    };
    private DatabaseSettings activeDatabaseSettings;
    private boolean closed;

    public DatabaseManager(PhantomControl plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        activeDatabaseSettings = DatabaseSettings.from(configManager);
        databaseHandler = createDatabaseHandler(activeDatabaseSettings);
        try {
            databaseHandler.connect();
            databaseHandler.initialize();
        } catch (RuntimeException error) {
            databaseExecutor.shutdown();
            databaseHandler.closeConnection();
            throw error;
        }
    }

    private DatabaseHandler createDatabaseHandler(DatabaseSettings settings) {
        return settings.type().equals("mysql")
                ? new MySQLDatabaseHandler(plugin, configManager) : new FlatFileDatabaseHandler(plugin);
    }

    // Only state changes and queue submission take this lock. Never hold it across I/O or waits.
    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("数据库已关闭"));
        try {
            databaseExecutor.execute(() -> {
                try { result.complete(operation.get()); }
                catch (RuntimeException error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    public CompletableFuture<Boolean> loadPlayerData(Player player) {
        UUID id = player.getUniqueId();
        synchronized (stateLock) {
            activePlayers.add(id);
            CacheEntry cached = playerDataCache.get(id);
            if (cached != null) return CompletableFuture.completedFuture(cached.value());
            CompletableFuture<Boolean> existing = loadingPlayers.get(id);
            if (existing != null) return existing;
            CompletableFuture<Boolean> load = new CompletableFuture<>();
            loadingPlayers.put(id, load);
            submit(() -> databaseHandler.loadPlayerData(id)).whenComplete((value, error) -> {
                synchronized (stateLock) {
                    if (loadingPlayers.remove(id, load) && error == null) {
                        playerDataCache.putIfAbsent(id, new CacheEntry(value));
                    }
                }
                if (error != null) load.completeExceptionally(error);
                else load.complete(getPlayerPhantomsStatus(id));
            });
            return load;
        }
    }

    public void savePlayerData(Player player) {
        synchronized (stateLock) {
            UUID id = player.getUniqueId();
            activePlayers.remove(id);
            loadingPlayers.remove(id);
            playerDataCache.remove(id);
            // All changes were already queued. Do not enqueue an older quit snapshot.
        }
    }

    public boolean isPlayerDataLoaded(UUID id) {
        return playerDataCache.containsKey(id);
    }

    /** Cached lookup only; joining members are protected until their preference loads. */
    public boolean getPlayerPhantomsStatus(UUID id) {
        synchronized (stateLock) {
            CacheEntry cached = playerDataCache.get(id);
            if (cached != null) return cached.value();
            CacheEntry pending = pendingWrites.get(id);
            if (pending != null) return pending.value();
            return !loadingPlayers.containsKey(id);
        }
    }

    public CompletableFuture<Boolean> getPlayerPhantomsStatusAsync(UUID id) {
        synchronized (stateLock) {
            CacheEntry cached = playerDataCache.get(id);
            if (cached != null) return CompletableFuture.completedFuture(cached.value());
            CompletableFuture<Boolean> loading = loadingPlayers.get(id);
            if (loading != null) return loading;
            return submit(() -> databaseHandler.loadPlayerData(id));
        }
    }

    public CompletableFuture<Boolean> setPlayerPhantomsStatusAsync(UUID id, boolean enabled) {
        synchronized (stateLock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("数据库已关闭"));
            CacheEntry entry = new CacheEntry(enabled);
            // Publish intent before an in-flight load can install its older result.
            playerDataCache.put(id, entry);
            pendingWrites.put(id, entry);
            CompletableFuture<Boolean> result = submit(() -> {
                databaseHandler.savePlayerData(id, enabled);
                synchronized (stateLock) {
                    if (pendingWrites.get(id) == entry) pendingWrites.remove(id);
                    if (!activePlayers.contains(id) && playerDataCache.get(id) == entry) playerDataCache.remove(id);
                }
                return true;
            });
            logFailure(result);
            return result;
        }
    }

    public void setPlayerPhantomsStatus(UUID id, boolean enabled) {
        setPlayerPhantomsStatusAsync(id, enabled);
    }

    /** Blocking compatibility methods: callers must use an I/O thread. Commands use the async API. */
    @Deprecated
    public boolean getPlayerPhantomsStatusDirect(UUID id) {
        return getPlayerPhantomsStatusAsync(id).join();
    }

    @Deprecated
    public void setPlayerPhantomsStatusDirect(UUID id, boolean enabled) {
        setPlayerPhantomsStatusAsync(id, enabled).join();
    }

    private Map<UUID, CacheEntry> snapshot() {
        Map<UUID, CacheEntry> snapshot = new HashMap<>(playerDataCache);
        snapshot.putAll(pendingWrites);
        return snapshot;
    }

    private void flush(Map<UUID, CacheEntry> snapshot) {
        Map<UUID, Boolean> values = new HashMap<>();
        snapshot.forEach((id, entry) -> values.put(id, entry.value()));
        databaseHandler.saveAllData(values);
        synchronized (stateLock) {
            snapshot.forEach((id, entry) -> {
                if (pendingWrites.get(id) == entry) pendingWrites.remove(id);
                if (!activePlayers.contains(id) && playerDataCache.get(id) == entry) playerDataCache.remove(id);
            });
        }
    }

    public CompletableFuture<Void> saveAllDataAsync() {
        synchronized (stateLock) {
            // Snapshot creation AND enqueue are ordered with single-player changes.
            Map<UUID, CacheEntry> snapshot = snapshot();
            CompletableFuture<Void> result = submit(() -> { flush(snapshot); return null; });
            logFailure(result);
            return result;
        }
    }

    public void saveAllData() {
        saveAllDataAsync();
    }

    private void logFailure(CompletableFuture<?> result) {
        result.whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "数据库操作失败；未保存的设置保留待重试", error);
        });
    }

    /** Called on the asynchronous reload thread, never a player tick. */
    public boolean reloadDatabase() {
        CompletableFuture<Boolean> result;
        synchronized (stateLock) {
            DatabaseSettings requested = DatabaseSettings.from(configManager);
            result = submit(() -> {
                if (requested.equals(activeDatabaseSettings)) return false;
                Map<UUID, CacheEntry> snapshot;
                synchronized (stateLock) { snapshot = snapshot(); }
                flush(snapshot);
                DatabaseHandler replacement = createDatabaseHandler(requested);
                try {
                    replacement.connect();
                    replacement.initialize();
                    Map<UUID, Boolean> values = new HashMap<>();
                    snapshot.forEach((id, entry) -> values.put(id, entry.value()));
                    replacement.saveAllData(values);
                } catch (RuntimeException error) {
                    replacement.closeConnection();
                    throw error;
                }
                DatabaseHandler previous = databaseHandler;
                previous.closeConnection();
                databaseHandler = replacement;
                activeDatabaseSettings = requested;
                return true;
            });
        }
        return result.join();
    }

    public void closeConnection() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
        }
        // shutdown may invoke terminated immediately when idle; keep I/O outside stateLock.
        databaseExecutor.shutdown();
        try {
            // Only server shutdown waits for storage. Normal gameplay never waits.
            if (!databaseExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("数据库关闭超时，未关闭仍在使用的连接；请检查数据库连接");
                return;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("等待数据保存时被中断");
        }
    }

    private record DatabaseSettings(String type, String address, String database, String username,
                                    String password, String prefix) {
        private static DatabaseSettings from(ConfigManager config) {
            String type = config.getDatabaseType();
            if (!type.equals("mysql")) return new DatabaseSettings(type, null, null, null, null, null);
            return new DatabaseSettings(type, config.getMySQLAddress(), config.getMySQLDatabase(),
                    config.getMySQLUsername(), config.getMySQLPassword(), config.getMySQLPrefix());
        }
    }
}
