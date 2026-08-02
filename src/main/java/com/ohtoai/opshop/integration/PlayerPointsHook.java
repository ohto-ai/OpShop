package com.ohtoai.opshop.integration;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

public class PlayerPointsHook {

    private PlayerPointsAPI api;

    public PlayerPointsHook() {
        PlayerPoints pp = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp != null) {
            this.api = pp.getAPI();
        }
    }

    public boolean isReady() {
        return api != null;
    }

    public int getPoints(OfflinePlayer player) {
        if (api == null) return 0;
        return api.look(player.getUniqueId());
    }

    public boolean has(OfflinePlayer player, int amount) {
        if (api == null) return false;
        return api.look(player.getUniqueId()) >= amount;
    }

    public boolean take(OfflinePlayer player, int amount) {
        if (api == null) return false;
        return api.take(player.getUniqueId(), amount);
    }

    public boolean give(OfflinePlayer player, int amount) {
        if (api == null) return false;
        return api.give(player.getUniqueId(), amount);
    }
}
