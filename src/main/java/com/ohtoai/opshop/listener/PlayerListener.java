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
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cooldown cache for offline players (optional, kept for memory efficiency)
        // Cooldowns are stored in memory only; they reset on server restart.
        // Could persist cooldowns to database if needed long-term.
    }
}
