package com.ohtoai.opshop.placeholder;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.shop.ShopManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for OpShop.
 * Provides:
 *   %opshop_currency_<id>%        — balance of a specific currency
 *   %opshop_currency_<id>_name%   — display name of a currency
 *   %opshop_item_count%           — total number of shop items
 */
public class OpShopExpansion extends PlaceholderExpansion {

    private final OpShopPlugin plugin;
    private final CurrencyManager currencyManager;
    private final ShopManager shopManager;

    public OpShopExpansion(OpShopPlugin plugin, CurrencyManager currencyManager, ShopManager shopManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.shopManager = shopManager;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "opshop";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return plugin.getPluginMeta().getAuthors().isEmpty()
            ? "OhtoAi" : plugin.getPluginMeta().getAuthors().get(0);
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Survive PlaceholderAPI reloads
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // %opshop_item_count%
        if (params.equals("item_count")) {
            return String.valueOf(shopManager.getItemCount());
        }

        // %opshop_currency_<id>%
        if (params.startsWith("currency_")) {
            String rest = params.substring("currency_".length());

            // %opshop_currency_<id>_name%
            if (rest.endsWith("_name")) {
                String currencyId = rest.substring(0, rest.length() - 5);
                var provider = currencyManager.getProvider(currencyId);
                return provider.map(CurrencyProvider::getDisplayName).orElse(currencyId);
            }

            // %opshop_currency_<id>%
            var provider = currencyManager.getProvider(rest);
            if (provider.isPresent()) {
                double balance = provider.get().getBalance(player);
                return provider.get().format(balance);
            }
        }

        return null; // Unknown placeholder
    }
}
