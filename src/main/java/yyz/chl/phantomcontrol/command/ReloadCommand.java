package yyz.chl.phantomcontrol.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import yyz.chl.phantomcontrol.PhantomControl;
import yyz.chl.phantomcontrol.manager.ConfigManager;

import java.util.logging.Level;

public class ReloadCommand implements CommandExecutor {
    
    private final PhantomControl plugin;
    private final ConfigManager configManager;
    
    public ReloadCommand(PhantomControl plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("phantomcontrol.reload")) {
            sender.sendMessage(configManager.getMessage("reload-command.no-permission"));
            return true;
        }
        
        plugin.reloadAllAsync().whenComplete((result, error) -> {
            Runnable reply = () -> {
                if (error != null) {
                    sender.sendMessage(configManager.formatMessage("reload-command.error", "%error%",
                            String.valueOf(error.getMessage())));
                    plugin.getLogger().log(Level.SEVERE, "重载配置失败", error);
                    return;
                }
                sender.sendMessage(configManager.getMessage("reload-command.success"));
                if (result.commandsRequireRestart()) {
                    sender.sendMessage(configManager.getMessage("reload-command.commands-restart-required"));
                }
                plugin.getLogger().info(sender.getName() + " 重载了插件配置");
            };
            if (sender instanceof org.bukkit.entity.Player player) {
                player.getScheduler().run(plugin, task -> reply.run(), () -> {});
            } else {
                org.bukkit.Bukkit.getGlobalRegionScheduler().execute(plugin, reply);
            }
        });

        return true;
    }
}
