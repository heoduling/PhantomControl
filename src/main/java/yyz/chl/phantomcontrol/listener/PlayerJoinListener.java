package yyz.chl.phantomcontrol.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import yyz.chl.phantomcontrol.PhantomControl;

public class PlayerJoinListener implements Listener {
    private final PhantomControl plugin;
    public PlayerJoinListener(PhantomControl plugin) { this.plugin = plugin; }
    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) { plugin.loadOnlinePlayer(event.getPlayer()); }
}
