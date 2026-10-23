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
 *   %opshop_limit_<itemId>%              — remaining purchases for the player ("∞" = no limit)
 *   %opshop_limit_bought_<itemId>%       — purchases made by the player so far
 *   %opshop_limit_total_<itemId>%        — remaining global purchases ("∞" = no limit)
 *   %opshop_limit_total_bought_<itemId>% — global purchases so far
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

        // Purchase limit placeholders (check longest prefixes first)
        if (params.startsWith("limit_")) {
            String rest = params.substring("limit_".length());

            // %opshop_limit_total_bought_<itemId>%
            if (rest.startsWith("total_bought_")) {
                String itemId = rest.substring("total_bought_".length());
                return shopManager.getItem(itemId)
                    .map(i -> String.valueOf(i.getTotalSold())).orElse(null);
            }
            // %opshop_limit_total_<itemId>%
            if (rest.startsWith("total_")) {
                String itemId = rest.substring("total_".length());
                var item = shopManager.getItem(itemId);
                if (item.isEmpty()) return null;
                if (item.get().getTotalLimit() < 0) return "∞";
                return String.valueOf(Math.max(0, item.get().getTotalLimit() - item.get().getTotalSold()));
            }
            // %opshop_limit_bought_<itemId>%
            if (rest.startsWith("bought_")) {
                String itemId = rest.substring("bought_".length());
                if (player == null || shopManager.getItem(itemId).isEmpty()) return null;
                try {
                    return String.valueOf(shopManager.getPlayerPurchaseCount(player.getUniqueId(), itemId));
                } catch (java.sql.SQLException e) {
                    return null;
                }
            }
            // %opshop_limit_<itemId>% — remaining purchases for the player
            String itemId = rest;
            var item = shopManager.getItem(itemId);
            if (item.isEmpty() || player == null) return null;
            if (item.get().getPlayerLimit() < 0) return "∞";
            try {
                int bought = shopManager.getPlayerPurchaseCount(player.getUniqueId(), itemId);
                return String.valueOf(Math.max(0, item.get().getPlayerLimit() - bought));
            } catch (java.sql.SQLException e) {
                return null;
            }
        }

        return null; // Unknown placeholder
    }
}
