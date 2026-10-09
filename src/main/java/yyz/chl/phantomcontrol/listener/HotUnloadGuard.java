package yyz.chl.phantomcontrol.listener;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.*;
import org.bukkit.plugin.Plugin;
import yyz.chl.phantomcontrol.PhantomControl;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pre-disable drain for the supplied PlugManX command/API contract. No dependency is bundled. */
public final class HotUnloadGuard implements Listener {
    private final PhantomControl plugin;
    private final yyz.chl.phantomcontrol.util.PluginLifecycle lifecycle;
    private final AtomicBoolean requested = new AtomicBoolean();
    private Map<Plugin, Object> gentleRegistry;
    private Object gentleHook;

    public HotUnloadGuard(PhantomControl plugin) { this.plugin = plugin; this.lifecycle = plugin.getLifecycle(); }

    @SuppressWarnings("unchecked")
    public void registerGentleUnload() {
        Plugin manager = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (manager == null || !manager.isEnabled() || gentleHook != null) return;
        try {
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> api = Class.forName("bukkit.com.rylinaux.plugman.api.PlugManAPI", true, loader);
            Class<?> hook = Class.forName("bukkit.com.rylinaux.plugman.api.GentleUnload", true, loader);
            // Beta.2 exposes only a COPY via getGentleUnloads(), and has no unregister API.
            Field registry = api.getDeclaredField("gentleUnloads"); registry.setAccessible(true);
            gentleRegistry = (Map<Plugin, Object>) registry.get(null);
            gentleHook = Proxy.newProxyInstance(loader, new Class<?>[]{hook}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "askingForGentleUnload" -> plugin.isPreparedForHotUnload();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PhantomControl unload guard";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            if (!(Boolean) api.getMethod("pleaseAddMeToGentleUnload", Plugin.class, hook)
                    .invoke(null, plugin, gentleHook)) throw new IllegalStateException("卸载保护已被其他调用者注册");
        } catch (ReflectiveOperationException | RuntimeException error) {
            gentleHook = null; gentleRegistry = null;
            plugin.getLogger().log(java.util.logging.Level.WARNING, "未能注册 PlugManX API 卸载保护；仅支持受控命令入口", error);
        }
    }

    public void unregisterGentleUnload() {
        if (gentleRegistry != null && gentleHook != null) gentleRegistry.remove(plugin, gentleHook);
        gentleRegistry = null; gentleHook = null;
        // Beta.2 also inspects OTHER plugins' commands when unloading a plugin, so its cache
        // may already contain our command class even though we remove commands before handoff.
        Plugin manager = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (manager == null) return;
        try {
            Class<?> accessor = Class.forName("core.com.rylinaux.plugman.util.reflection.FieldAccessor", true, manager.getClass().getClassLoader());
            Field cacheField = accessor.getDeclaredField("fieldCache"); cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
            Set<String> ownedClasses = new HashSet<>();
            cache.values().removeIf(value -> {
                if (value instanceof Field field && field.getDeclaringClass().getClassLoader() == plugin.getClass().getClassLoader()) {
                    ownedClasses.add(field.getDeclaringClass().getName()); return true;
                }
                return false;
            });
            Field namesField = accessor.getDeclaredField("firstFieldNameCache"); namesField.setAccessible(true);
            ((Map<?, ?>) namesField.get(null)).keySet().removeIf(key -> ownedClasses.stream().anyMatch(name -> key.toString().startsWith(name + ":")));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法清理 PlugManX 中本插件的命令反射缓存", error);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerCommand(PlayerCommandPreprocessEvent event) {
        if (intercept(event.getPlayer(), event.getMessage().substring(1))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consoleCommand(ServerCommandEvent event) {
        if (intercept(event.getSender(), event.getCommand())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void remoteCommand(RemoteServerCommandEvent event) {
        if (intercept(event.getSender(), event.getCommand())) event.setCancelled(true);
    }
    @EventHandler public void pluginEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) plugin.registerPlaceholderAPI();
        if (event.getPlugin().getName().equals("PlugManX")) registerGentleUnload();
    }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) plugin.unregisterPlaceholderAPI();
        if (event.getPlugin().getName().equals("PlugManX")) unregisterGentleUnload();
    }

    boolean intercept(CommandSender sender, String commandLine) {
        String[] args = commandLine.trim().split("\\s+");
        if (args.length < 3) return false;
        var command = Bukkit.getServer().getCommandMap().getCommand(args[0].toLowerCase(Locale.ROOT));
        if (!(command instanceof PluginIdentifiableCommand owned)
                || !owned.getPlugin().getName().equals("PlugManX")) return false;
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!Set.of("unload", "reload", "disable", "restart").contains(action)) return false;
        if (!sender.hasPermission("plugman.help") || !sender.hasPermission("plugman." + action)) return false;
        if (args[2].equalsIgnoreCase("all") || args[2].equals("*")) {
            sender.sendMessage("§cPhantomControl 热操作请单独指定 PhantomControl，避免批量操作绕过清理流程。");
            return true;
        }
        if (args.length != 3 || !args[2].equalsIgnoreCase(plugin.getName())) return false;
        if (!requested.compareAndSet(false, true)) {
            sender.sendMessage("§ePhantomControl 正在清理，请等待本次操作完成。"); return true;
        }
        sender.sendMessage("§ePhantomControl 正在保存并清理菜单，完成后自动继续 " + action + "。");
        String replay = args[0] + " " + action + " " + plugin.getName();
        plugin.prepareForHotUnload().whenComplete((ignored, error) -> {
            if (error != null) {
                requested.set(false);
                reply(sender, "§cPhantomControl 清理失败，未继续卸载；请查看控制台错误。", null);
                return;
            }
            reply(sender, "§aPhantomControl 清理完成，正在继续 " + action + "。", () -> {
                handOff(replay);
            });
        });
        return true;
    }

    private void handOff(String commandLine) {
        try {
            Plugin manager = Bukkit.getPluginManager().getPlugin("PlugManX");
            if (manager == null || !manager.isEnabled()) throw new IllegalStateException("PlugManX 已停用");
            // PluginClassLoader records the loading stack in pluginState. Loading on this plugin's
            // callback stack retains the old loader through that Throwable, even after all hooks clear.
            // Hand off a JDK-owned Runnable to PlugManX's next global tick; no old-plugin stack frame.
            var lookup = MethodHandles.publicLookup();
            MethodHandle dispatch = lookup.findStatic(Bukkit.class, "dispatchCommand",
                    MethodType.methodType(boolean.class, CommandSender.class, String.class));
            dispatch = MethodHandles.insertArguments(dispatch, 0, Bukkit.getConsoleSender(), commandLine)
                    .asType(MethodType.methodType(void.class));
            MethodHandle reset = lookup.findVirtual(AtomicBoolean.class, "set", MethodType.methodType(void.class, boolean.class))
                    .bindTo(requested);
            reset = MethodHandles.dropArguments(MethodHandles.insertArguments(reset, 0, false), 0, Throwable.class);
            Runnable replay = MethodHandleProxies.asInterfaceInstance(Runnable.class, MethodHandles.tryFinally(dispatch, reset));
            Bukkit.getGlobalRegionScheduler().execute(manager, replay);
        } catch (ReflectiveOperationException | RuntimeException error) {
            requested.set(false);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "无法将已清理的热操作交给 PlugManX", error);
        }
    }

    private void reply(CommandSender sender, String text, Runnable after) {
        if (!plugin.isEnabled()) return;
        if (sender instanceof Player player) {
            lifecycle.entity(player, () -> { player.sendMessage(text); return null; }, true)
                    .whenComplete((ignored, error) -> { if (after != null && plugin.isEnabled()) after.run(); });
        } else {
            lifecycle.global(() -> {
                sender.sendMessage(text); if (after != null) after.run();
            }, true);
        }
    }
}
