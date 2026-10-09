package yyz.chl.phantomcontrol;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import yyz.chl.phantomcontrol.util.PluginLifecycle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HotLifecycleTest {
    final Plugin plugin = mock(Plugin.class);
    final Player player = mock(Player.class);
    final EntityScheduler scheduler = mock(EntityScheduler.class);
    final PluginLifecycle cycle = new PluginLifecycle(plugin);
    HotLifecycleTest() { when(plugin.isEnabled()).thenReturn(true); when(player.getScheduler()).thenReturn(scheduler); }

    @Test void retiredEntitySettlesWithoutRunningBody() {
        var result = cycle.entity(player, () -> fail("retired body"));
        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, cycle.pendingCount());
        assertTrue(cycle.stopAndDrain().isDone());
    }

    @Test void submissionFailureSettles() {
        when(scheduler.run(any(), any(), any())).thenThrow(new IllegalStateException("disabled race"));
        assertTrue(cycle.entity(player, () -> 1).isCompletedExceptionally());
        assertEquals(0, cycle.pendingCount());
    }

    @Test void cancellingResultDoesNotPretendQueuedCallbackHasDrained() {
        AtomicReference<Consumer<ScheduledTask>> body = new AtomicReference<>();
        when(scheduler.run(any(), any(), any())).thenAnswer(i -> { body.set(i.getArgument(1)); return mock(ScheduledTask.class); });
        var result = cycle.entity(player, () -> fail("stopped body"));
        result.cancel(false);
        var drain = cycle.stopAndDrain();
        assertFalse(drain.isDone());
        body.get().accept(mock(ScheduledTask.class));
        assertTrue(drain.isDone());
        assertEquals(0, cycle.pendingCount());
        assertFalse(cycle.callIfRunning(() -> true, false));
    }

    @Test void retiredCallbackAlsoReleasesDrain() {
        AtomicReference<Runnable> retired = new AtomicReference<>();
        when(scheduler.run(any(), any(), any())).thenAnswer(i -> { retired.set(i.getArgument(2)); return mock(ScheduledTask.class); });
        var result = cycle.entity(player, () -> 1);
        var drain = cycle.stopAndDrain();
        assertFalse(drain.isDone());
        retired.get().run();
        assertTrue(result.isCompletedExceptionally());
        assertTrue(drain.isDone());
    }

    @Test void cleanupCanRunAfterAdmissionStopsButNewWorkCannot() {
        cycle.stopAndDrain();
        when(scheduler.run(any(), any(), any())).thenAnswer(i -> {
            Consumer<ScheduledTask> run = i.getArgument(1); run.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        });
        assertTrue(cycle.entity(player, () -> 1).isCompletedExceptionally());
        assertEquals(2, cycle.entity(player, () -> 2, true).join());
        assertEquals(0, cycle.pendingCount());
    }

    private static void field(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    @Test void failedSaveRefusesUnloadAndCanBeRetriedWithoutClosingDatabase() throws Exception {
        PhantomControl owner = mock(PhantomControl.class, CALLS_REAL_METHODS);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var lifecycle = new PluginLifecycle(owner);
        var db = mock(yyz.chl.phantomcontrol.manager.DatabaseManager.class);
        var gui = mock(yyz.chl.phantomcontrol.manager.GUIManager.class);
        field(owner, "lifecycle", lifecycle); field(owner, "databaseManager", db); field(owner, "guiManager", gui);
        when(gui.closeMenus()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(db.saveAllDataAsync()).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected save failure")));
        var async = mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        var global = mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        doAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return null; }).when(global).execute(any(),any());
        when(async.runNow(any(),any())).thenAnswer(i -> {
            Consumer<ScheduledTask> body = i.getArgument(1); body.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        });
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getAsyncScheduler).thenReturn(async);
            bukkit.when(org.bukkit.Bukkit::getGlobalRegionScheduler).thenReturn(global);
            assertTrue(owner.prepareForHotUnload().isCompletedExceptionally());
            assertFalse(owner.isPreparedForHotUnload()); assertFalse(lifecycle.isAccepting());
            verify(db, never()).closeConnection();
            when(db.saveAllDataAsync()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            owner.prepareForHotUnload().join();
            assertTrue(owner.isPreparedForHotUnload()); verify(db).closeConnection(); verify(db).verifyClosed();
        }
    }

    @Test void unregisterUsesMapRemovalAndPreservesForeignAliasReplacement() throws Exception {
        var manager = mock(yyz.chl.phantomcontrol.command.CommandManager.class, CALLS_REAL_METHODS);
        var owner = mock(PhantomControl.class);
        var server = mock(org.bukkit.Server.class);
        var help = mock(org.bukkit.help.HelpMap.class);
        when(owner.getServer()).thenReturn(server); when(server.getHelpMap()).thenReturn(help);
        when(help.getHelpTopics()).thenReturn(new java.util.ArrayList<>());
        field(manager,"plugin",owner);
        var own = mock(org.bukkit.command.Command.class);
        var foreign = mock(org.bukkit.command.Command.class);
        var entries = new java.util.HashMap<String,org.bukkit.command.Command>();
        entries.put("pc", foreign); entries.put("phantomcontrol:pc", own); entries.put("phantomcontrol", own);
        java.util.Map<String,org.bukkit.command.Command> forwarding = new java.util.AbstractMap<>() {
            public java.util.Set<Entry<String,org.bukkit.command.Command>> entrySet() { return java.util.Collections.unmodifiableMap(entries).entrySet(); }
            public org.bukkit.command.Command remove(Object key) { return entries.remove(key); }
        };
        field(manager,"knownCommands",forwarding); field(manager,"ownedCommands",new java.util.ArrayList<>(java.util.List.of(own)));
        manager.unregisterCommands();
        assertEquals(java.util.Map.of("pc",foreign),entries);
    }
}
