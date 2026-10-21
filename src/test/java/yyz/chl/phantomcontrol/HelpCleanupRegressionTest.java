package yyz.chl.phantomcontrol;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.junit.jupiter.api.Test;
import yyz.chl.phantomcontrol.command.CommandManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpCleanupRegressionTest {
    private final CommandManager manager = mock(CommandManager.class, CALLS_REAL_METHODS);
    private final Command ownCommand = mock(Command.class);
    private final Command foreignCommand = mock(Command.class);
    private final CommandMap commandMap = mock(CommandMap.class);
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, HelpTopic> topics = new LinkedHashMap<>();
    private final GenericCommandHelpTopic ownTopic = mock(GenericCommandHelpTopic.class);
    private final GenericCommandHelpTopic foreignTopic = mock(GenericCommandHelpTopic.class);

    HelpCleanupRegressionTest() throws Exception {
        var plugin = mock(PhantomControl.class);
        var server = mock(Server.class);
        var help = mock(HelpMap.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("PhantomControl");
        when(server.getHelpMap()).thenReturn(help);
        when(help.getHelpTopics()).thenAnswer(ignored -> topics.values());
        when(help.getHelpTopic("")).thenAnswer(ignored -> topics.get(""));
        field(CommandManager.class, manager, "plugin", plugin);
        field(CommandManager.class, manager, "commandMap", commandMap);
        field(CommandManager.class, manager, "knownCommands", commands);
        field(CommandManager.class, manager, "ownedCommands", new ArrayList<>(List.of(ownCommand)));
        field(GenericCommandHelpTopic.class, ownTopic, "command", ownCommand);
        field(GenericCommandHelpTopic.class, foreignTopic, "command", foreignCommand);
        commands.put("pc", foreignCommand); // A foreign plugin has taken over this alias.
        commands.put("phantomcontrol:pc", ownCommand);
        topics.put("/phantomcontrol:pc", ownTopic);
        topics.put("/foreign", foreignTopic);
    }

    @Test void unrelatedImmutableEmptyIndexDoesNotAbortCommandCleanup() throws Exception {
        var empty = List.<HelpTopic>of();
        var index = new IndexHelpTopic("OtherPlugin", "", "", empty);
        topics.put("OtherPlugin", index);
        assertDoesNotThrow(manager::unregisterCommands);
        assertSame(empty, children(index));
        assertSame(index, topics.get("OtherPlugin"));
        assertCleanCommands();
    }

    @Test void immutableMixedAndOwnedIndexesReleaseOurTopicsAndPreserveForeignTopics() throws Exception {
        var mixed = new IndexHelpTopic("Mixed", "", "", List.of(ownTopic, foreignTopic));
        var owned = new IndexHelpTopic("PhantomControl", "", "", List.of(ownTopic));
        topics.put("Mixed", mixed);
        topics.put("PhantomControl", owned);
        var root = new IndexHelpTopic("", "", "", List.of(mixed, owned, foreignTopic));
        topics.put("", root);
        assertDoesNotThrow(manager::unregisterCommands);
        assertEquals(List.of(foreignTopic), new ArrayList<>(children(mixed)));
        assertTrue(children(owned).isEmpty());
        assertEquals(List.of(mixed, foreignTopic), new ArrayList<>(children(root)));
        assertFalse(topics.containsKey("PhantomControl"));
        assertCleanCommands();
        assertDoesNotThrow(manager::unregisterCommands); // Stop after a prepared unload is idempotent.
    }

    @Test void mutableLiveIndexRetainsItsBackingCollectionForFutureCommands() throws Exception {
        var backing = new LinkedHashMap<String, HelpTopic>();
        backing.put("own", ownTopic);
        backing.put("foreign", foreignTopic);
        var liveView = backing.values();
        var index = new IndexHelpTopic("Live", "", "", liveView);
        topics.put("Live", index);
        manager.unregisterCommands();
        assertSame(liveView, children(index));
        assertEquals(List.of(foreignTopic), new ArrayList<>(children(index)));
        var later = mock(HelpTopic.class);
        backing.put("later", later);
        assertTrue(children(index).contains(later));
        assertCleanCommands();
    }

    private void assertCleanCommands() {
        assertEquals(Map.of("pc", foreignCommand), commands);
        assertFalse(topics.containsValue(ownTopic));
        assertSame(foreignTopic, topics.get("/foreign"));
        verify(ownCommand).unregister(commandMap);
        verify(foreignCommand, never()).unregister(commandMap);
    }

    private static Collection<?> children(IndexHelpTopic topic) throws Exception {
        var field = IndexHelpTopic.class.getDeclaredField("allTopics");
        field.setAccessible(true);
        return (Collection<?>) field.get(topic);
    }

    private static void field(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
