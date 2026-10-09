package yyz.chl.phantomcontrol;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.FlatFileDatabaseHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductionUpgradeTest {
    @TempDir Path directory;

    private PhantomControl plugin() throws Exception {
        String archive = System.getProperty("phantomcontrol.upgradeZip");
        for (String name : new String[] {"config.yml", "messages.yml", "messages_en.yml", "playerdata.yml"}) {
            if (archive != null) {
                // Local-only evidence: never commit production data or credentials to the repository.
                try (ZipFile zip = new ZipFile(archive);
                     var input = zip.getInputStream(zip.getEntry("PhantomControl/" + name))) {
                    Files.copy(input, directory.resolve(name));
                }
            } else {
                try (var input = getClass().getResourceAsStream("/legacy/" + name)) {
                    assertNotNull(input);
                    Files.copy(input, directory.resolve(name));
                }
            }
        }
        PhantomControl plugin = mock(PhantomControl.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PhantomControlUpgradeTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                getClass().getResourceAsStream("/" + invocation.getArgument(0)));
        when(plugin.getConfig()).thenAnswer(invocation -> {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
            try (var reader = new java.io.InputStreamReader(plugin.getResource("config.yml"),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            }
            return config;
        });
        return plugin;
    }

    @Test
    void upgradePreservesExistingSettingsMessagesAndPlayerFile() throws Exception {
        PhantomControl plugin = plugin();
        Map<String, Map<String, Object>> before = new HashMap<>();
        for (String name : new String[] {"config.yml", "messages.yml", "messages_en.yml"}) {
            before.put(name, leaves(directory.resolve(name)));
        }
        byte[] playerData = Files.readAllBytes(directory.resolve("playerdata.yml"));
        ConfigManager manager = new ConfigManager(plugin);
        assertTrue(manager.validateConfig());
        for (var file : before.entrySet()) {
            Map<String, Object> after = leaves(directory.resolve(file.getKey()));
            for (var entry : file.getValue().entrySet()) {
                if (!entry.getKey().equals("config-version")) {
                    assertEquals(entry.getValue(), after.get(entry.getKey()), file.getKey() + ":" + entry.getKey());
                }
            }
            assertEquals(4, after.get("config-version"));
        }
        assertArrayEquals(playerData, Files.readAllBytes(directory.resolve("playerdata.yml")));
        String migrated = Files.readString(directory.resolve("config.yml"));
        assertTrue(migrated.contains("cache-timeout-minutes: 60"));
        assertTrue(migrated.contains("# 缓存过期时间"));
        manager.reloadConfig();
        assertEquals(60, manager.getLong("database.cache-timeout-minutes", -1));
        assertEquals("flatfile", manager.getDatabaseType());
    }

    @Test
    void allLegacyPlayerPreferencesSurviveLoadAndSave() throws Exception {
        PhantomControl plugin = plugin();
        Map<String, Object> before = leaves(directory.resolve("playerdata.yml"));
        assertFalse(before.isEmpty());
        FlatFileDatabaseHandler handler = new FlatFileDatabaseHandler(plugin);
        handler.connect();
        handler.initialize();
        Map<UUID, Boolean> preferences = new HashMap<>();
        for (var entry : before.entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            assertEquals(entry.getValue(), handler.loadPlayerData(id));
            preferences.put(id, (Boolean) entry.getValue());
        }
        assertTrue(handler.loadPlayerData(UUID.randomUUID()));
        handler.saveAllData(preferences);
        handler.closeConnection();
        assertEquals(before, leaves(directory.resolve("playerdata.yml")));
        System.out.println("Legacy preferences verified: " + before.size());
    }

    private Map<String, Object> leaves(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        Map<String, Object> result = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) result.put(key, yaml.get(key));
        }
        return result;
    }
}
