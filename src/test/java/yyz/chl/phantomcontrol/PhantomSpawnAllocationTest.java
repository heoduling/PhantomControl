package yyz.chl.phantomcontrol;

import org.bukkit.event.EventHandler;
import org.bukkit.Server;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;
import yyz.chl.phantomcontrol.listener.PhantomSpawnListener;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PhantomSpawnAllocationTest {
    @Test
    void registersOutsideSharedPreCreatureHandlerList() {
        var handlers = Arrays.stream(PhantomSpawnListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class)).toList();
        assertEquals(1, handlers.size());
        assertArrayEquals(new Class<?>[] {CreatureSpawnEvent.class}, handlers.get(0).getParameterTypes(),
                "Use the final spawn event so the phantom listener does not enable ordinary pre-spawn allocation");
    }

    @Test
    void unrelatedSpawnsAndMissingTriggerDoNotReadPlayerRules() {
        var plugin = mock(PhantomControl.class);
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        var manager = mock(PhantomManager.class);
        var listener = new PhantomSpawnListener(plugin, manager, mock(ConfigManager.class));
        var ordinary = mock(CreatureSpawnEvent.class);
        when(ordinary.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        when(ordinary.getEntity()).thenReturn(mock(Zombie.class));
        listener.onCreatureSpawn(ordinary);
        verifyNoInteractions(server, manager);

        var phantom = mock(Phantom.class);
        var event = mock(CreatureSpawnEvent.class);
        when(event.getEntity()).thenReturn(phantom);
        for (var reason : CreatureSpawnEvent.SpawnReason.values()) {
            if (reason == CreatureSpawnEvent.SpawnReason.NATURAL) continue;
            when(event.getSpawnReason()).thenReturn(reason);
            listener.onCreatureSpawn(event);
        }
        verifyNoInteractions(phantom, server, manager);
        when(event.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        listener.onCreatureSpawn(event); // A phantom with no triggering UUID.
        verifyNoInteractions(server, manager);

        UUID id = UUID.randomUUID();
        when(phantom.getSpawningEntity()).thenReturn(id);
        listener.onCreatureSpawn(event); // Triggering player is absent.
        var player = mock(Player.class);
        when(server.getPlayer(id)).thenReturn(player);
        listener.onCreatureSpawn(event); // Foreign region's live player state must not be read.
        verifyNoInteractions(player, manager);
        verify(event, never()).setCancelled(anyBoolean());
    }
}
