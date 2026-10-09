package yyz.chl.phantomcontrol.command;

import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import yyz.chl.phantomcontrol.PhantomControl;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.GUIManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;
import yyz.chl.phantomcontrol.util.MessageUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CommandManager {

    private final PhantomControl plugin;
    private final ConfigManager configManager;
    private final PhantomManager phantomManager;
    private final GUIManager guiManager;
    private final MessageUtil messageUtil;
    private CommandMap commandMap;
    private Map<String, Command> knownCommands;
    private final String registeredMainCommand;
    private final Set<String> registeredMainAliases;
    private final String registeredReloadCommand;
    private final Set<String> registeredReloadAliases;
    private final List<Command> ownedCommands = new ArrayList<>();
    
    public CommandManager(PhantomControl plugin, ConfigManager configManager, 
                           PhantomManager phantomManager, GUIManager guiManager, MessageUtil messageUtil) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.phantomManager = phantomManager;
        this.guiManager = guiManager;
        this.messageUtil = messageUtil;
        this.commandMap = getCommandMap();
        this.knownCommands = getKnownCommands();
        this.registeredMainCommand = normalize(configManager.getString("settings.commands.main-command"));
        this.registeredMainAliases = normalize(configManager.getStringList("settings.commands.main-aliases"));
        this.registeredReloadCommand = normalize(configManager.getString("settings.commands.reload-command"));
        this.registeredReloadAliases = normalize(configManager.getStringList("settings.commands.reload-aliases"));
        registerCommands();
    }
    
    private CommandMap getCommandMap() {
        try {
            PluginManager pluginManager = plugin.getServer().getPluginManager();
            
            if (pluginManager instanceof SimplePluginManager) {
                Field commandMapField = SimplePluginManager.class.getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                return (CommandMap) commandMapField.get(pluginManager);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            plugin.getLogger().severe("获取CommandMap失败: " + e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands() {
        if (commandMap == null) {
            return null;
        }

        Class<?> currentClass = commandMap.getClass();
        while (currentClass != null) {
            try {
                Field knownCommandsField = currentClass.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                return (Map<String, Command>) knownCommandsField.get(commandMap);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            } catch (IllegalAccessException e) {
                plugin.getLogger().warning("获取 knownCommands 失败: " + e.getMessage());
                return null;
            }
        }

        plugin.getLogger().warning("CommandMap 不包含 knownCommands 字段，无法完整检查命令冲突");
        return null;
    }
    
    private void registerCommands() {
        String mainCommand = registeredMainCommand;
        List<String> mainAliases = configManager.getStringList("settings.commands.main-aliases");
        String reloadCommand = registeredReloadCommand;
        List<String> reloadAliases = configManager.getStringList("settings.commands.reload-aliases");
        
        PhantomControlCommand mainExecutor = new PhantomControlCommand(
                plugin, phantomManager, configManager, guiManager, messageUtil, mainCommand, reloadCommand);
        PhantomControlTabCompleter mainTabCompleter = new PhantomControlTabCompleter();
        ReloadCommand reloadExecutor = new ReloadCommand(plugin, configManager);
        
        registerCommand(mainCommand, mainExecutor, mainTabCompleter, mainAliases);
        registerCommand(reloadCommand, reloadExecutor, null, reloadAliases);
    }

    public boolean isConfiguredCommandRegistrationCurrent() {
        return registeredMainCommand.equals(normalize(configManager.getString("settings.commands.main-command")))
                && registeredMainAliases.equals(normalize(configManager.getStringList("settings.commands.main-aliases")))
                && registeredReloadCommand.equals(normalize(configManager.getString("settings.commands.reload-command")))
                && registeredReloadAliases.equals(normalize(configManager.getStringList("settings.commands.reload-aliases")));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalize(List<String> values) {
        return values.stream()
                .map(CommandManager::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isPluginCommand(Command command) {
        return command instanceof PluginIdentifiableCommand
                && ((PluginIdentifiableCommand) command).getPlugin().equals(plugin);
    }
    
    private void registerCommand(String commandName, CommandExecutor executor, TabCompleter tabCompleter, List<String> aliases) {
        if (commandMap == null) {
            plugin.getLogger().severe("CommandMap 未初始化，无法注册命令: " + commandName);
            return;
        }
        
        if (commandName == null || commandName.isEmpty()) {
            plugin.getLogger().severe("命令名称不能为空");
            return;
        }
        
        Command existingCommand = knownCommands != null ? knownCommands.get(commandName.toLowerCase()) : null;
        if (existingCommand != null && !isPluginCommand(existingCommand)) {
            plugin.getLogger().warning("命令 /" + commandName + " 已被其他插件占用，可能只能通过命名空间命令访问");
        }
        
        for (String alias : aliases) {
            Command existingAlias = knownCommands != null ? knownCommands.get(alias.toLowerCase()) : null;
            if (!alias.equals(commandName) && existingAlias != null && !isPluginCommand(existingAlias)) {
                plugin.getLogger().warning("命令别名 /" + alias + " 已被其他插件占用，已跳过该别名");
            }
        }

        List<String> availableAliases = new ArrayList<>();
        for (String alias : aliases) {
            if (!alias.equals(commandName) && (knownCommands == null || !knownCommands.containsKey(alias.toLowerCase()))) {
                availableAliases.add(alias);
            }
        }

        DynamicPluginCommand dynamicCommand = new DynamicPluginCommand(commandName, availableAliases, plugin, executor, tabCompleter);
        ownedCommands.add(dynamicCommand);
        commandMap.register(plugin.getName().toLowerCase(), dynamicCommand);
    }

    public void unregisterCommands() {
        unregisterHelpTopics();
        if (knownCommands != null) {
            // Modern Paper's command map does not support Iterator.remove().
            for (var entry : new ArrayList<>(knownCommands.entrySet())) {
                if (ownedCommands.contains(entry.getValue())) knownCommands.remove(entry.getKey(), entry.getValue());
            }
        }
        if (commandMap != null) ownedCommands.forEach(command -> command.unregister(commandMap));
        ownedCommands.clear();
    }

    private void unregisterHelpTopics() {
        var help = plugin.getServer().getHelpMap();
        var topics = new ArrayList<>(help.getHelpTopics());
        var owned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<org.bukkit.help.HelpTopic, Boolean>());
        try {
            Field commandField = org.bukkit.help.GenericCommandHelpTopic.class.getDeclaredField("command");
            commandField.setAccessible(true);
            Field childrenField = org.bukkit.help.IndexHelpTopic.class.getDeclaredField("allTopics");
            childrenField.setAccessible(true);
            if (help.getHelpTopic("") != null) topics.add(help.getHelpTopic(""));
            for (var topic : topics) {
                if (topic instanceof org.bukkit.help.GenericCommandHelpTopic && ownedCommands.contains(commandField.get(topic))) owned.add(topic);
                if (topic.getClass().getName().equals("org.bukkit.craftbukkit.help.CommandAliasHelpTopic")
                        && knownCommands != null && topic.getName().startsWith("/")
                        && ownedCommands.contains(knownCommands.get(topic.getName().substring(1)))) owned.add(topic);
            }
            // The core's plugin index holds a separate collection; removing only the root help topic leaks commands.
            for (var topic : topics) {
                if (topic instanceof org.bukkit.help.IndexHelpTopic) {
                    var children = (java.util.Collection<?>) childrenField.get(topic);
                    if (topic.getName().equals(plugin.getName()) && !children.isEmpty() && children.stream().allMatch(owned::contains)) owned.add(topic);
                    children.removeIf(owned::contains);
                }
            }
            help.getHelpTopics().removeIf(owned::contains);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法注销本插件的命令帮助", error);
        }
    }

    private static class DynamicPluginCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin plugin;
        private final CommandExecutor executor;
        private final TabCompleter tabCompleter;
        private final yyz.chl.phantomcontrol.util.PluginLifecycle lifecycle;

        private DynamicPluginCommand(String name, List<String> aliases, Plugin plugin,
                                     CommandExecutor executor, TabCompleter tabCompleter) {
            super(name, "", "/" + name, aliases);
            this.plugin = plugin;
            this.lifecycle = ((PhantomControl) plugin).getLifecycle();
            this.executor = executor;
            this.tabCompleter = tabCompleter;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return lifecycle.callIfRunning(() -> executor.onCommand(sender, this, commandLabel, args), true);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
            if (tabCompleter == null) {
                return super.tabComplete(sender, alias, args);
            }
            List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
            return completions != null ? completions : super.tabComplete(sender, alias, args);
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }
}
