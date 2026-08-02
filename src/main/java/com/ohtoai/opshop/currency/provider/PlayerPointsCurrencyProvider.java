package com.ohtoai.opshop.currency.provider;

import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.currency.CurrencyType;
import com.ohtoai.opshop.integration.PlayerPointsHook;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PlayerPointsCurrencyProvider implements CurrencyProvider {

    private final CurrencyDef def;
    private final PlayerPointsHook ppHook;

    public PlayerPointsCurrencyProvider(CurrencyDef def, PlayerPointsHook ppHook) {
        this.def = def;
        this.ppHook = ppHook;
    }

    @Override
    public String getId() {
        return def.id();
    }

    @Override
    public String getDisplayName() {
        return def.displayName();
    }

    @Override
    public String getDisplaySingular() {
        return def.displaySingular();
    }

    @Override
    public CurrencyType getType() {
        return CurrencyType.PLAYER_POINTS;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return ppHook.getPoints(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return ppHook.has(player, (int) Math.ceil(amount));
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        return ppHook.take(player, (int) Math.ceil(amount));
    }

    @Override
    public boolean deposit(Player player, double amount) {
        return ppHook.give(player, (int) Math.ceil(amount));
    }

    @Override
    public String format(double amount) {
        int pts = (int) Math.ceil(amount);
        return pts + " " + (pts == 1 ? getDisplaySingular() : getDisplayName());
    }
}
