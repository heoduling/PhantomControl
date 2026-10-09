package yyz.chl.phantomcontrol.command;

import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import yyz.chl.phantomcontrol.PhantomControl;
import yyz.chl.phantomcontrol.api.PhantomStatusChangeSource;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.GUIManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;
import yyz.chl.phantomcontrol.util.MessageUtil;

public class PhantomControlCommand implements CommandExecutor {
    
    private final PhantomControl plugin;
    private final PhantomManager phantomManager;
    private final ConfigManager configManager;
    private final GUIManager guiManager;
    private final MessageUtil messageUtil;
    private final String registeredMainCommand;
    private final String registeredReloadCommand;
    
    public PhantomControlCommand(PhantomControl plugin, PhantomManager phantomManager, 
                                  ConfigManager configManager, GUIManager guiManager, MessageUtil messageUtil,
                                  String registeredMainCommand, String registeredReloadCommand) {
        this.plugin = plugin;
        this.phantomManager = phantomManager;
        this.configManager = configManager;
        this.guiManager = guiManager;
        this.messageUtil = messageUtil;
        this.registeredMainCommand = registeredMainCommand;
        this.registeredReloadCommand = registeredReloadCommand;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(configManager.getMessage("command.not-player"));
            return true;
        }
        
        Player player = (Player) sender;
        
        if (!player.hasPermission("phantomcontrol.use")) {
            messageUtil.sendMessage(player, configManager.getMessage(player, "command.no-permission"));
            return true;
        }
        
        if (args.length < 1) {
            showHelp(player);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "enable":
            case "on":
                enablePlayer(player);
                break;
            case "disable":
            case "off":
                if (!disablePlayer(player)) {
                    messageUtil.sendMessage(player, configManager.getMessage(player, "command.cannot-disable"));
                }
                break;
            case "toggle":
            case "switch":
            case "切换":
                if (phantomManager.hasPhantomsEnabled(player)) {
                    if (!disablePlayer(player)) {
                        messageUtil.sendMessage(player, configManager.getMessage(player, "command.cannot-disable"));
                    }
                } else {
                    enablePlayer(player);
                }
                break;
            case "status":
            case "check":
                boolean status = phantomManager.hasPhantomsEnabled(player);
                String statusText = status ? configManager.getMessage(player, "command.status_enabled") : configManager.getMessage(player, "command.status_disabled");
                String statusMessage = configManager.formatMessage(player, "command.status", 
                    "%status%", statusText);
                messageUtil.sendMessage(player, statusMessage);
                break;
            case "gui":
            case "menu":
            case "界面":
                guiManager.openPhantomControlGUI(player);
                break;
            case "admin":
                handleAdminCommand(player, args);
                break;
            case "help":
                showHelp(player);
                break;
            default:
                messageUtil.sendMessage(player, configManager.getMessage("command.usage"));
                break;
        }
        
        return true;
    }
    
    private void handleAdminCommand(Player player, String[] args) {
        if (!player.hasPermission("phantomcontrol.admin")) {
            messageUtil.sendMessage(player, configManager.getMessage(player, "command.no-permission"));
            return;
        }

        if (args.length < 2) {
            String message = configManager.formatMessage(
                    player, "admin.usage", "%maincommand%", registeredMainCommand);
            messageUtil.sendMessage(player, message);
            return;
        }

        String adminSubCommand = args[1].toLowerCase();
        if (adminSubCommand.equals("server")) {
            handleServerCommand(player);
            return;
        }
        if (adminSubCommand.equals("batch")) {
            handleBatchCommand(player, args);
            return;
        }
        if (!adminSubCommand.equals("enable")
                && !adminSubCommand.equals("disable")
                && !adminSubCommand.equals("status")) {
            String invalidMsg = configManager.formatMessage(
                    player, "admin.invalid-subcommand", "%maincommand%", registeredMainCommand);
            messageUtil.sendMessage(player, invalidMsg);
            return;
        }
        if (args.length < 3) {
            String message = configManager.formatMessage(
                    player, "admin.usage", "%maincommand%", registeredMainCommand);
            messageUtil.sendMessage(player, message);
            return;
        }

        String targetPlayerName = args[2];

        performAdminAction(targetPlayerName, adminSubCommand).whenComplete((result, error) ->
                player.getScheduler().run(plugin, task -> {
                    if (error != null || (result.found() && !result.success())) {
                        messageUtil.sendMessage(player, configManager.formatMessage(player,
                                "admin.change-failed", "%player%", targetPlayerName));
                    } else if (!result.found()) {
                        messageUtil.sendMessage(player, configManager.formatMessage(player,
                                "admin.player-not-found", "%player%", targetPlayerName));
                    } else if (adminSubCommand.equals("status")) {
                        String status = configManager.getMessage(player,
                                result.enabled() ? "admin.status-enabled" : "admin.status-disabled");
                        String message = configManager.formatMessage(player, "admin.status",
                                "%player%", targetPlayerName, "%status%", status);
                        if (result.offline()) message += " " + configManager.getMessage(player, "admin.status-offline");
                        messageUtil.sendMessage(player, message);
                    } else {
                        messageUtil.sendMessage(player, configManager.formatMessage(player,
                                "admin." + adminSubCommand + "-success", "%player%", targetPlayerName));
                    }
                }, () -> {}));
    }

    private record AdminResult(boolean found, boolean success, boolean enabled, boolean offline) {}

    private CompletableFuture<AdminResult> performAdminAction(String name, String action) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return performOnlineAction(online, action);
        CompletableFuture<AdminResult> result = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                OfflinePlayer offline = resolveOfflinePlayer(name);
                if (offline == null) {
                    result.complete(new AdminResult(false, false, true, true));
                    return;
                }
                Player joined = Bukkit.getPlayer(offline.getUniqueId());
                CompletableFuture<AdminResult> operation;
                if (joined != null) operation = performOnlineAction(joined, action);
                else if (action.equals("status")) {
                    operation = plugin.getDatabaseManager().getPlayerPhantomsStatusAsync(offline.getUniqueId())
                            .thenApply(value -> new AdminResult(true, true, value, true));
                } else {
                    boolean enabled = action.equals("enable");
                    operation = plugin.getDatabaseManager().setPlayerPhantomsStatusAsync(offline.getUniqueId(), enabled)
                            .thenApply(success -> new AdminResult(true, success, enabled, true));
                }
                operation.whenComplete((value, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(value);
                });
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private CompletableFuture<AdminResult> performOnlineAction(Player target, String action) {
        CompletableFuture<AdminResult> result = new CompletableFuture<>();
        Runnable retired = () -> result.complete(new AdminResult(true, false, true, false));
        if (target.getScheduler().run(plugin, task -> {
            try {
                boolean success = switch (action) {
                    case "enable" -> enablePlayer(target, PhantomStatusChangeSource.ADMIN_COMMAND);
                    case "disable" -> disablePlayer(target, PhantomStatusChangeSource.ADMIN_COMMAND);
                    default -> true;
                };
                result.complete(new AdminResult(true, success, phantomManager.hasPhantomsEnabled(target), false));
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        }, retired) == null) retired.run();
        return result;
    }

    private void showHelp(Player player) {
        String mainCommand = registeredMainCommand;
        String reloadCommand = registeredReloadCommand;
        
        messageUtil.sendMessage(player,
            configManager.getMessage(player, "help.header") + "\n"
            + configManager.formatMessage(player, "help.enable", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.disable", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.toggle", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.status", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.gui", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.admin", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.batch", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.server", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.help", "%maincommand%", mainCommand) + "\n"
            + configManager.formatMessage(player, "help.reload", "%maincommand%", mainCommand, "%reloadcommand%", reloadCommand)
        );
    }
    
    private void handleBatchCommand(Player player, String[] args) {
        if (args.length < 4) {
            String batchUsage = configManager.formatMessage(
                    player, "admin.batch-usage", "%maincommand%", registeredMainCommand);
            messageUtil.sendMessage(player, batchUsage);
            return;
        }

        String batchSubCommand = args[2].toLowerCase();
        if (!batchSubCommand.equals("enable") && !batchSubCommand.equals("disable")) {
            String batchInvalid = configManager.formatMessage(
                    player, "admin.batch-invalid-subcommand", "%maincommand%", registeredMainCommand);
            messageUtil.sendMessage(player, batchInvalid);
            return;
        }

        List<CompletableFuture<Boolean>> operations = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            operations.add(performAdminAction(args[i], batchSubCommand)
                    .handle((result, error) -> error == null && result.found() && result.success()));
        }
        CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).thenRun(() ->
                player.getScheduler().run(plugin, task -> {
                    long successCount = operations.stream().filter(CompletableFuture::join).count();
                    String action = configManager.getMessage(player, batchSubCommand.equals("enable")
                            ? "command.status_enabled" : "command.status_disabled");
                    messageUtil.sendMessage(player, configManager.formatMessage(player, "admin.batch-success",
                            "%action%", action, "%success%", String.valueOf(successCount),
                            "%fail%", String.valueOf(operations.size() - successCount)));
                }, () -> {}));
    }

    private boolean enablePlayer(Player player) {
        return enablePlayer(player, PhantomStatusChangeSource.COMMAND);
    }

    private boolean enablePlayer(Player player, PhantomStatusChangeSource source) {
        if (!phantomManager.enablePhantoms(player, source)) {
            return false;
        }
        String message = configManager.getMessage(player, "command.enabled");
        messageUtil.sendOnChange(player, message,
            configManager.getMessage(player, "command.enabled"),
            configManager.getMessage(player, "command.enabled"));
        return true;
    }
    
    /**
     * 为玩家禁用幻翼。
     * @return true 表示已成功禁用，false 表示玩家无权限禁用
     */
    private boolean disablePlayer(Player player) {
        return disablePlayer(player, PhantomStatusChangeSource.COMMAND);
    }

    private boolean disablePlayer(Player player, PhantomStatusChangeSource source) {
        if (!phantomManager.canDisablePhantoms(player)) {
            return false;
        }
        if (!phantomManager.disablePhantoms(player, source)) {
            return false;
        }
        String message = configManager.getMessage(player, "command.disabled");
        messageUtil.sendOnChange(player, message,
            configManager.getMessage(player, "command.disabled"),
            configManager.getMessage(player, "command.disabled"));
        return true;
    }
    
    private void handleServerCommand(Player player) {
        int totalPlayers = org.bukkit.Bukkit.getOnlinePlayers().size();
        int enabledCount = 0;
        int disabledCount = 0;
        
        for (Player onlinePlayer : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (phantomManager.hasPhantomsEnabled(onlinePlayer)) {
                enabledCount++;
            } else {
                disabledCount++;
            }
        }
        
        double enabledPercentage = totalPlayers > 0 ? (double) enabledCount / totalPlayers * 100 : 0;
        double disabledPercentage = totalPlayers > 0 ? (double) disabledCount / totalPlayers * 100 : 0;
        
        messageUtil.sendMessage(player, configManager.getMessage(player, "admin.server-status-header"));
        messageUtil.sendMessage(player, configManager.formatMessage(player, "admin.server-status-total", "%count%", String.valueOf(totalPlayers)));
        messageUtil.sendMessage(player, configManager.formatMessage(player, "admin.server-status-enabled", 
            "%count%", String.valueOf(enabledCount), 
            "%percentage%", String.format("%.1f", enabledPercentage)));
        messageUtil.sendMessage(player, configManager.formatMessage(player, "admin.server-status-disabled", 
            "%count%", String.valueOf(disabledCount), 
            "%percentage%", String.format("%.1f", disabledPercentage)));
        
        if (configManager.isDebugEnabled()) {
            plugin.getLogger().info("管理员 " + player.getName() + " 查看服务器幻翼状态: 总在线 " + totalPlayers + " 人, 启用 " + enabledCount + " 人, 禁用 " + disabledCount + " 人");
        }
    }

    /**
     * 通过玩家名解析离线玩家。
     * 返回 null 表示该玩家从未登录过服务器。
     */
    private OfflinePlayer resolveOfflinePlayer(String playerName) {
        OfflinePlayer offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer == null || !offlinePlayer.hasPlayedBefore()) {
            return null;
        }
        return offlinePlayer;
    }
}
