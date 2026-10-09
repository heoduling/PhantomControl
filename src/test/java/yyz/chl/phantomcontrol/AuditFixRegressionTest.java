package yyz.chl.phantomcontrol;

import com.zaxxer.hikari.HikariDataSource;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yyz.chl.phantomcontrol.api.PhantomStatusChangeSource;
import yyz.chl.phantomcontrol.command.PhantomControlCommand;
import yyz.chl.phantomcontrol.listener.GUIListener;
import yyz.chl.phantomcontrol.manager.*;
import yyz.chl.phantomcontrol.util.MessageUtil;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuditFixRegressionTest {
    @TempDir Path dir;
    private PhantomControl plugin() {
        PhantomControl p = mock(PhantomControl.class);
        when(p.getDataFolder()).thenReturn(dir.toFile());
        when(p.getLogger()).thenReturn(Logger.getLogger("audit-regression"));
        when(p.isEnabled()).thenReturn(true);
        when(p.getLifecycle()).thenReturn(new yyz.chl.phantomcontrol.util.PluginLifecycle(p));
        when(p.getResource(anyString())).thenAnswer(i -> getClass().getResourceAsStream("/" + i.getArgument(0)));
        return p;
    }
    private ConfigManager config() {
        ConfigManager c = mock(ConfigManager.class);
        when(c.getDatabaseType()).thenReturn("flatfile");
        when(c.getMySQLPrefix()).thenReturn("test_");
        return c;
    }
    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenReturn(true);
        return p;
    }
    private void replace(DatabaseManager db, DatabaseHandler handler) throws Exception {
        var f = DatabaseManager.class.getDeclaredField("databaseHandler"); f.setAccessible(true); f.set(db, handler);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }
    private void barrier(DatabaseManager db) throws Exception {
        db.getPlayerPhantomsStatusAsync(UUID.randomUUID()).get(5, TimeUnit.SECONDS);
    }

    @Test void slowBulkDoesNotBlockChangeJoinOrQuitAndCannotOverwriteNewerValue() throws Exception {
        DatabaseManager db = new DatabaseManager(plugin(), config());
        Player p = player(); UUID id = p.getUniqueId();
        db.loadPlayerData(p).get(5, TimeUnit.SECONDS);
        DatabaseHandler handler = spy(new FlatFileDatabaseHandler(plugin()));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); await(release); i.callRealMethod(); return null; }).when(handler).saveAllData(anyMap());
        replace(db, handler);
        try {
            var bulk = db.saveAllDataAsync(); await(entered);
            assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> {
                db.setPlayerPhantomsStatus(id, false);
                db.savePlayerData(p);
                db.loadPlayerData(player());
            });
            release.countDown(); bulk.get(5, TimeUnit.SECONDS); barrier(db);
            db.saveAllDataAsync().get(5, TimeUnit.SECONDS);
            assertFalse(YamlConfiguration.loadConfiguration(dir.resolve("playerdata.yml").toFile()).getBoolean(id.toString()));
            assertFalse(db.getPlayerPhantomsStatusAsync(id).get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); db.closeConnection(); }
    }

    @Test void acknowledgedDisableDuringLoadingSurvivesLoadQuitAndReload() throws Exception {
        PhantomControl plugin = plugin(); ConfigManager config = config();
        DatabaseManager db = new DatabaseManager(plugin, config);
        Player p = player(); UUID id = p.getUniqueId();
        DatabaseHandler handler = spy(new FlatFileDatabaseHandler(plugin));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); await(release); return true; }).when(handler).loadPlayerData(id);
        replace(db, handler);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            var load = db.loadPlayerData(p); await(entered);
            assertTrue(new PhantomManager(plugin, db, config).disablePhantoms(p, PhantomStatusChangeSource.GUI));
            release.countDown(); load.get(5, TimeUnit.SECONDS); barrier(db);
            assertFalse(db.getPlayerPhantomsStatus(id));
            db.savePlayerData(p);
            db.saveAllDataAsync().get(5, TimeUnit.SECONDS);
            assertFalse(new FlatFileDatabaseHandler(plugin).loadPlayerData(id));
        } finally { release.countDown(); db.closeConnection(); }
    }

    @Test void malformedPlayerFileIsPreservedByteForByte() throws Exception {
        Path file = dir.resolve("playerdata.yml");
        String broken = "synthetic: [unterminated\n"; Files.writeString(file, broken);
        assertThrows(IllegalStateException.class, () -> new FlatFileDatabaseHandler(plugin()));
        assertEquals(broken, Files.readString(file));
    }

    @Test void mysqlReadSingleAndBulkFailuresArePropagated() throws Exception {
        MySQLDatabaseHandler handler = new MySQLDatabaseHandler(plugin(), config());
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenThrow(new SQLException("INJECTED disconnected"));
        var field = MySQLDatabaseHandler.class.getDeclaredField("dataSource"); field.setAccessible(true); field.set(handler, pool);
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> handler.loadPlayerData(id));
        assertThrows(IllegalStateException.class, () -> handler.savePlayerData(id, false));
        assertThrows(IllegalStateException.class, () -> handler.saveAllData(Map.of(id, false)));
    }

    @Test void failedAsyncWriteIsNotSuccessfulAndDepartedPreferenceRetries() throws Exception {
        DatabaseManager db = new DatabaseManager(plugin(), config()); Player p = player(); UUID id = p.getUniqueId();
        DatabaseHandler handler = mock(DatabaseHandler.class);
        doThrow(new IllegalStateException("INJECTED disk failure")).when(handler).savePlayerData(id, false);
        replace(db, handler);
        try {
            assertThrows(ExecutionException.class, () -> db.setPlayerPhantomsStatusAsync(id, false).get(5, TimeUnit.SECONDS));
            db.savePlayerData(p);
            db.saveAllDataAsync().get(5, TimeUnit.SECONDS);
            verify(handler).saveAllData(Map.of(id, false));
        } finally { db.closeConnection(); }
    }

    @Test void rejectedExecutorNeverRunsStorageOnCaller() throws Exception {
        DatabaseManager db = new DatabaseManager(plugin(), config());
        DatabaseHandler handler = mock(DatabaseHandler.class); replace(db, handler);
        db.closeConnection(); clearInvocations(handler);
        assertThrows(ExecutionException.class, () -> db.setPlayerPhantomsStatusAsync(UUID.randomUUID(), false).get());
        verifyNoInteractions(handler);
    }

    @Test void dragIntoTopIsDeniedButBottomOnlyAndOrdinaryContainersAreUntouched() {
        GUIManager gui = mock(GUIManager.class); Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
        when(top.getSize()).thenReturn(27); when(gui.isPhantomControlInventory(top)).thenReturn(true);
        GUIListener listener = new GUIListener(gui, mock(PhantomManager.class), config(), mock(MessageUtil.class));
        InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
        when(drag.getRawSlots()).thenReturn(Set.of(10,12)); listener.onInventoryDrag(drag); verify(drag).setCancelled(true);
        clearInvocations(drag); when(drag.getRawSlots()).thenReturn(Set.of(30,31)); listener.onInventoryDrag(drag); verify(drag, never()).setCancelled(anyBoolean());
        when(gui.isPhantomControlInventory(top)).thenReturn(false); when(drag.getRawSlots()).thenReturn(Set.of(10,12)); listener.onInventoryDrag(drag); verify(drag, never()).setCancelled(anyBoolean());
    }

    @Test void migrationUsesFullPathsPreservesCommentsAndHandlesUppercaseLanguageAndReload() throws Exception {
        PhantomControl plugin = plugin();
        for (String name : List.of("config.yml", "messages.yml", "messages_en.yml")) {
            try (var in = plugin.getResource(name)) { Files.copy(in, dir.resolve(name)); }
        }
        Path file = dir.resolve("config.yml");
        String initial = Files.readString(file).replace("config-version: 4", "config-version: 1")
                .replace("    status-enabled-material: \"GREEN_WOOL\"", "")
                .replace("mode: \"auto\"", "mode: \"CHINESE\"");
        Files.writeString(file, initial + "\n# keep my own comment\ncustom-key: custom-value\n");
        ConfigManager manager = new ConfigManager(plugin);
        YamlConfiguration after = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("GREEN_WOOL", after.getString("settings.gui.status-enabled-material"));
        assertFalse(after.isSet("settings.commands.status-enabled-material"));
        assertTrue(Files.readString(file).contains("# keep my own comment"));
        Player english = player(); when(english.getLocale()).thenReturn("en_us");
        assertEquals("幻翼控制", manager.getMessage(english, "gui.title"));
        assertTrue(manager.validateConfig());
        after.set("settings.gui.status-enabled-material", null); after.save(file.toFile());
        manager.reloadConfig();
        assertEquals("GREEN_WOOL", YamlConfiguration.loadConfiguration(file.toFile()).getString("settings.gui.status-enabled-material"));
    }

    @SuppressWarnings("unchecked")
    private void immediateScheduler(Player p) {
        EntityScheduler scheduler = mock(EntityScheduler.class); when(p.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenAnswer(i -> { ((Consumer<ScheduledTask>)i.getArgument(1)).accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class); });
    }

    @Test void adminEnableRefusalDoesNotReportSuccess() {
        PhantomControl plugin = plugin(); ConfigManager config = config();
        PhantomManager manager = mock(PhantomManager.class); MessageUtil messages = mock(MessageUtil.class);
        Player admin = player(), target = player(); immediateScheduler(admin); immediateScheduler(target);
        when(config.formatMessage(eq(admin), eq("admin.change-failed"), any(String[].class))).thenReturn("FAILED");
        when(config.formatMessage(eq(admin), eq("admin.enable-success"), any(String[].class))).thenReturn("SUCCESS");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer("Target")).thenReturn(target);
            new PhantomControlCommand(plugin, manager, config, mock(GUIManager.class), messages, "pc", "pcr")
                    .onCommand(admin, null, "pc", new String[]{"admin", "enable", "Target"});
            verify(manager).enablePhantoms(target, PhantomStatusChangeSource.ADMIN_COMMAND);
            verify(messages).sendMessage(admin, "FAILED");
            verify(messages, never()).sendMessage(admin, "SUCCESS");
        }
    }
}
