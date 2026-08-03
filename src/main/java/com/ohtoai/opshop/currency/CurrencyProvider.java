package com.ohtoai.opshop.currency;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface CurrencyProvider {

    /** Unique id matching config currency key, e.g. "vault_main" */
    String getId();

    /** Human-readable name, e.g. "Gold Coins" */
    String getDisplayName();

    /** Singular form, e.g. "Gold Coin" */
    String getDisplaySingular();

    CurrencyType getType();

    /** Get player's balance of this currency */
    double getBalance(OfflinePlayer player);

    /** Check if player has enough */
    boolean has(OfflinePlayer player, double amount);

    /** Take currency from player. Returns false if insufficient. */
    boolean withdraw(Player player, double amount);

    /** Give currency to player */
    boolean deposit(Player player, double amount);

    /** Format amount for display, e.g. "50 Gold Coins" */
    String format(double amount);

    /**
     * Get a display ItemStack representing this currency.
     * For custom-item currencies, returns the full original item (with enchantments,
     * flags, glow, etc. preserved). Returns null for non-item currencies (Vault, etc.).
     */
    default ItemStack getDisplayItem() { return null; }
}
