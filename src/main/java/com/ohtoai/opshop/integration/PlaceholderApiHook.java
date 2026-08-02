package com.ohtoai.opshop.integration;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PlaceholderApiHook {

    public PlaceholderApiHook() {
        // PlaceholderAPI detected via Bukkit plugin check
    }

    public String setPlaceholders(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    public String setPlaceholders(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    public boolean isEnabled() {
        return true; // HookManager already verified the plugin is loaded
    }
}
