package yyz.chl.phantomcontrol.listener;

import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import yyz.chl.phantomcontrol.PhantomControl;
import yyz.chl.phantomcontrol.event.PhantomSpawnBlockedEvent;
import yyz.chl.phantomcontrol.manager.ConfigManager;
import yyz.chl.phantomcontrol.manager.PhantomManager;

/**
 * 在幻翼加入世界前按真实触发玩家拦截。
 * 不订阅共享的预生成监听表，避免开启普通自然刷怪的预生成事件分配。
 */
public class PhantomSpawnListener implements Listener {

    private final PhantomManager phantomManager;
    private final ConfigManager configManager;
    private final PhantomControl plugin;

    public PhantomSpawnListener(PhantomControl plugin, PhantomManager phantomManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.phantomManager = phantomManager;
        this.configManager = configManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !(event.getEntity() instanceof Phantom phantom)) return;
        var spawningEntityId = phantom.getSpawningEntity();
        if (spawningEntityId == null) return;
        Player target = plugin.getServer().getPlayer(spawningEntityId);
        // Natural PhantomSpawner invokes this synchronously on the triggering player's owner.
        // Do not read another region's live player state if a custom spawner supplies a foreign UUID.
        if (target == null || !plugin.getServer().isOwnedByCurrentRegion(target)) return;

        if (!phantomManager.isWorldAllowed(target.getWorld().getName())) return;

        // Membership can expire while the player remains online in the same world.
        if (phantomManager.canDisablePhantoms(target) && !phantomManager.hasPhantomsEnabled(target)) {
            event.setCancelled(true);
            plugin.getServer().getPluginManager().callEvent(
                new PhantomSpawnBlockedEvent(target, String.valueOf(event.getSpawnReason())));
            if (configManager.isDebugEnabled()) {
                plugin.getLogger().info("[幻翼拦截] 已拦截 " + target.getName() + " 的幻翼生成 ("
                        + event.getSpawnReason() + ")");
            }
        }
    }
}
