package yyz.chl.phantomcontrol.manager;

import org.bukkit.configuration.file.YamlConfiguration;
import yyz.chl.phantomcontrol.PhantomControl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import org.bukkit.configuration.InvalidConfigurationException;
import java.util.Map;
import java.util.UUID;

public class FlatFileDatabaseHandler implements DatabaseHandler {
    
    private final PhantomControl plugin;
    private final File dataFile;
    private final Object dataLock = new Object();
    private YamlConfiguration dataConfig;
    private volatile boolean dirty;
    
    public FlatFileDatabaseHandler(PhantomControl plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "playerdata.yml");
        this.dataConfig = new YamlConfiguration();
        if (dataFile.exists()) {
            try {
                dataConfig.load(dataFile);
            } catch (IOException | InvalidConfigurationException error) {
                throw new IllegalStateException("玩家数据文件读取失败，已停止以保护原文件", error);
            }
        }
    }
    
    @Override
    public void connect() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                throw new IllegalStateException("无法创建玩家数据文件", e);
            }
        }
    }
    
    @Override
    public void initialize() {
    }
    
    @Override
    public boolean loadPlayerData(UUID playerId) {
        synchronized (dataLock) {
            return dataConfig.getBoolean(playerId.toString(), true);
        }
    }
    
    @Override
    public void savePlayerData(UUID playerId, boolean phantomsEnabled) {
        synchronized (dataLock) {
            dataConfig.set(playerId.toString(), phantomsEnabled);
            dirty = true;
        }
    }
    
    @Override
    public void saveAllData(Map<UUID, Boolean> playerDataMap) {
        synchronized (dataLock) {
            for (Map.Entry<UUID, Boolean> entry : playerDataMap.entrySet()) {
                dataConfig.set(entry.getKey().toString(), entry.getValue());
            }
            dirty = true;
            saveConfig();
        }
    }
    
    @Override
    public void closeConnection() {
        synchronized (dataLock) {
            if (dirty) {
                saveConfig();
            }
        }
    }
    
    private void saveConfig() {
        try {
            java.nio.file.Path temporary = Files.createTempFile(dataFile.toPath().getParent(), "playerdata-", ".tmp");
            try {
                dataConfig.save(temporary.toFile());
                try {
                    Files.move(temporary, dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException("无法保存玩家数据", e);
        }
    }
}
