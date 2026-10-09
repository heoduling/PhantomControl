package yyz.chl.phantomcontrol;

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import yyz.chl.phantomcontrol.listener.GUIListener;
import yyz.chl.phantomcontrol.listener.PhantomSpawnListener;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.DatabaseManager;
import yyz.chl.phantomcontrol.manager.GUIManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;
import yyz.chl.phantomcontrol.util.MessageUtil;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhantomControlRegressionTest {
    private final GUIManager gui = mock(GUIManager.class);

    @Test
    void pluginDescriptorKeepsApiVersionAsText() throws Exception {
        try (var input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            assertEquals("1.20", new org.bukkit.plugin.PluginDescriptionFile(input).getAPIVersion());
        }
    }

    private Inventory inventory(InventoryHolder holder) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenThrow(new AssertionError("Container snapshot requested"));
        when(inventory.getHolder(false)).thenReturn(holder);
        when(gui.isPhantomControlInventory(any())).thenCallRealMethod();
        return inventory;
    }

    @Test
    void ordinaryContainerDoesNotRequestSnapshotOrCancelClick() {
        Inventory inventory = inventory(mock(InventoryHolder.class));
        InventoryClickEvent event = click(inventory, 11);
        PhantomManager manager = mock(PhantomManager.class);
        new GUIListener(gui, manager, mock(ConfigManager.class), mock(MessageUtil.class))
                .onInventoryClick(event);
        verify(event, never()).setCancelled(anyBoolean());
        verifyNoInteractions(manager);
        verify(inventory).getHolder(false);
    }

    @Test
    void nullAndHolderlessInventoriesAreNotMenus() {
        inventory(null);
        assertFalse(gui.isPhantomControlInventory(null));
        assertFalse(gui.isPhantomControlInventory(inventory(null)));
    }

    @Test
    void realMenuRemainsRecognizedWithoutSnapshot() {
        assertTrue(gui.isPhantomControlInventory(inventory(new GUIManager.GUIHolder())));
    }

    @Test
    void menuBottomInventoryClickIsCancelledWithoutToggling() {
        InventoryClickEvent event = click(inventory(new GUIManager.GUIHolder()), 38);
        PhantomManager manager = mock(PhantomManager.class);
        new GUIListener(gui, manager, mock(ConfigManager.class), mock(MessageUtil.class))
                .onInventoryClick(event);
        verify(event).setCancelled(true);
        verifyNoInteractions(manager);
    }

    @Test
    void memberCanUseDisableButtonButPlayerWithoutPermissionCannot() {
        for (boolean permitted : new boolean[] {false, true}) {
            InventoryClickEvent event = click(inventory(new GUIManager.GUIHolder()), 15);
            Player player = (Player) event.getWhoClicked();
            PhantomManager manager = mock(PhantomManager.class);
            when(manager.canDisablePhantoms(player)).thenReturn(permitted);
            new GUIListener(gui, manager, mock(ConfigManager.class), mock(MessageUtil.class))
                    .onInventoryClick(event);
            verify(event).setCancelled(true);
            verify(manager, times(permitted ? 1 : 0)).disablePhantoms(eq(player), any());
        }
    }

    private InventoryClickEvent click(Inventory inventory, int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(mock(Player.class));
        when(event.getInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(rawSlot);
        return event;
    }

    @Test
    void spawnRequiresCurrentPermissionAndDisabledPreferenceAndAllowedWorld() {
        for (boolean permitted : new boolean[] {false, true}) {
            for (boolean enabled : new boolean[] {false, true}) {
                for (boolean allowed : new boolean[] {false, true}) {
                    PhantomControl plugin = mock(PhantomControl.class);
                    Server server = mock(Server.class);
                    when(plugin.getServer()).thenReturn(server);
                    when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
                    ConfigManager config = mock(ConfigManager.class);
                    when(config.getBoolean("whitelist.world-blacklist-enabled")).thenReturn(!allowed);
                    when(config.getStringList("whitelist.world-blacklist"))
                            .thenReturn(java.util.List.of("world"));
                    DatabaseManager database = mock(DatabaseManager.class);
                    Player player = mock(Player.class);
                    UUID id = UUID.randomUUID();
                    when(player.getUniqueId()).thenReturn(id);
                    when(player.hasPermission("phantomcontrol.use")).thenReturn(permitted);
                    World world = mock(World.class);
                    when(world.getName()).thenReturn("world");
                    when(player.getWorld()).thenReturn(world);
                    when(database.getPlayerPhantomsStatus(id)).thenReturn(enabled);
                    PhantomManager manager = new PhantomManager(plugin, database, config);
                    PhantomPreSpawnEvent event = mock(PhantomPreSpawnEvent.class);
                    when(event.getSpawningEntity()).thenReturn(player);
                    new PhantomSpawnListener(plugin, manager, config).onPhantomPreSpawn(event);
                    int expected = permitted && !enabled && allowed ? 1 : 0;
                    verify(event, times(expected)).setCancelled(true);
                    verify(event, times(expected)).setShouldAbortSpawn(true);
                    verify(database, never()).setPlayerPhantomsStatus(any(), anyBoolean());
                }
            }
        }
    }
}
