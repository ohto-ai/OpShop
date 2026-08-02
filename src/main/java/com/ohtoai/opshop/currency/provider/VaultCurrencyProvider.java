package com.ohtoai.opshop.currency.provider;

import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.currency.CurrencyType;
import com.ohtoai.opshop.integration.VaultHook;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class VaultCurrencyProvider implements CurrencyProvider {

    private final CurrencyDef def;
    private final VaultHook vaultHook;

    public VaultCurrencyProvider(CurrencyDef def, VaultHook vaultHook) {
        this.def = def;
        this.vaultHook = vaultHook;
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
        return CurrencyType.VAULT;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return vaultHook.getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return vaultHook.has(player, amount);
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        return vaultHook.withdraw(player, amount);
    }

    @Override
    public boolean deposit(Player player, double amount) {
        return vaultHook.deposit(player, amount);
    }

    @Override
    public String format(double amount) {
        return vaultHook.format(amount);
    }
}
