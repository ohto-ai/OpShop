package com.ohtoai.opshop.listener;

import com.ohtoai.opshop.OpShopPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Lightweight player listener for cache maintenance and join notifications.
 */
public class PlayerListener implements Listener {

    private final OpShopPlugin plugin;

    public PlayerListener(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getPluginConfig().isDebug()) {
            plugin.getLogger().info("Player joined: " + event.getPlayer().getName()
                + " — shop items: " + plugin.getShopManager().getItemCount());
        }
        // New-item notification — delayed so it doesn't clash with other join messages
        org.bukkit.entity.Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.getNewItemNotifier().sendJoinNotification(player);
            }
        }, 60L); // 3 seconds
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cooldown cache for offline players (memory efficiency)
        plugin.getShopManager().clearCooldowns(event.getPlayer().getUniqueId());
        // Clean up editor instance for this player
        com.ohtoai.opshop.gui.ShopEditorGui.removeActiveInstance(event.getPlayer().getUniqueId());
    }
}
