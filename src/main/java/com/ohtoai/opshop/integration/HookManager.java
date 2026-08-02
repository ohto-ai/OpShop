package com.ohtoai.opshop.integration;

import com.ohtoai.opshop.OpShopPlugin;
import org.bukkit.Bukkit;

public class HookManager {

    private final OpShopPlugin plugin;

    private VaultHook vaultHook;
    private PlayerPointsHook playerPointsHook;
    private PlaceholderApiHook placeholderApiHook;

    private boolean vaultEnabled;
    private boolean playerPointsEnabled;
    private boolean placeholderApiEnabled;

    public HookManager(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void detectAll() {
        // Vault
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            this.vaultHook = new VaultHook();
            this.vaultEnabled = vaultHook.isReady();
            if (vaultEnabled) {
                plugin.getLogger().info("Vault economy integration enabled.");
            } else {
                plugin.getLogger().warning("Vault found but no economy provider registered.");
            }
        } else {
            plugin.getLogger().info("Vault not found — Vault currencies will be disabled.");
        }

        // PlayerPoints
        if (Bukkit.getPluginManager().getPlugin("PlayerPoints") != null) {
            this.playerPointsHook = new PlayerPointsHook();
            this.playerPointsEnabled = playerPointsHook.isReady();
            if (playerPointsEnabled) {
                plugin.getLogger().info("PlayerPoints integration enabled.");
            }
        } else {
            plugin.getLogger().info("PlayerPoints not found — PlayerPoints currencies will be disabled.");
        }

        // PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderApiHook = new PlaceholderApiHook();
            this.placeholderApiEnabled = true;
            plugin.getLogger().info("PlaceholderAPI integration enabled.");
        } else {
            plugin.getLogger().info("PlaceholderAPI not found — placeholder support limited.");
        }
    }

    public void shutdown() {
        // Cleanup if needed
    }

    public boolean isVaultEnabled() { return vaultEnabled; }
    public boolean isPlayerPointsEnabled() { return playerPointsEnabled; }
    public boolean isPlaceholderApiEnabled() { return placeholderApiEnabled; }

    public VaultHook getVaultHook() { return vaultHook; }
    public PlayerPointsHook getPlayerPointsHook() { return playerPointsHook; }
    public PlaceholderApiHook getPlaceholderApiHook() { return placeholderApiHook; }
}
