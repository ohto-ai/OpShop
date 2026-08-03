package com.ohtoai.opshop.shop;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.database.DatabaseManager;
import com.ohtoai.opshop.database.TransactionRecord;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.item.PlaceholderReplacer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Orchestrates buy and sell transactions: validation, currency transfer,
 * item delivery, placeholder replacement, and transaction logging.
 */
public class TransactionProcessor {

    private final OpShopPlugin plugin;
    private final CurrencyManager currencyManager;
    private final ShopManager shopManager;
    private final DatabaseManager databaseManager;
    private final LegacyComponentSerializer legacy =
        LegacyComponentSerializer.legacySection();

    public TransactionProcessor(OpShopPlugin plugin, CurrencyManager currencyManager,
                                ShopManager shopManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.shopManager = shopManager;
        this.databaseManager = databaseManager;
    }

    /**
     * Result of a transaction attempt.
     */
    public enum Result {
        SUCCESS,
        ITEM_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_ITEMS,
        INVENTORY_FULL,
        NO_PERMISSION,
        GUILD_LOCKED,
        TOTAL_LIMIT_REACHED,
        PLAYER_LIMIT_REACHED,
        COOLDOWN_ACTIVE,
        OUT_OF_STOCK,
        CURRENCY_UNAVAILABLE,
        TRANSACTION_FAILED
    }

    /**
     * Execute a buy transaction.
     *
     * @param player    the buyer
     * @param itemId    the shop item ID
     * @param currencyId the currency to use
     * @param quantity  how many to buy
     * @return the result
     */
    public Result buy(Player player, String itemId, String currencyId, int quantity) {
        Optional<ShopItem> opt = shopManager.getItem(itemId);
        if (opt.isEmpty()) return Result.ITEM_NOT_FOUND;

        ShopItem item = opt.get();

        // Permission check
        if (item.getPermissionRequired() != null && !item.getPermissionRequired().isEmpty()) {
            if (!player.hasPermission(item.getPermissionRequired())) {
                return Result.NO_PERMISSION;
            }
        }

        // Guild whitelist check
        if (item.isGuildRestricted()) {
            var hook = plugin.getHookManager();
            if (!hook.isGuildEnabled()) {
                plugin.getLogger().warning("Guild check failed for " + item.getId()
                    + " — GuildPlugin not available.");
                return Result.GUILD_LOCKED;
            }
            var playerGuild = hook.getGuildBridge().getPlayerGuild(player.getUniqueId());
            if (playerGuild == null) {
                plugin.getLogger().info(player.getName() + " tried to buy guild-restricted item "
                    + item.getId() + " but is not in any guild.");
                return Result.GUILD_LOCKED;
            }
            if (!item.getAllowedGuildIds().contains(playerGuild.id())) {
                plugin.getLogger().info(player.getName() + " tried to buy guild-restricted item "
                    + item.getId() + " (guild " + playerGuild.name()
                    + " #" + playerGuild.id() + " not in whitelist).");
                return Result.GUILD_LOCKED;
            }
        }

        // Buy enabled check
        if (!item.isBuyEnabled()) return Result.TRANSACTION_FAILED;

        // Stock check
        if (item.getStock() >= 0 && item.getStock() < quantity) {
            return Result.OUT_OF_STOCK;
        }

        // Cooldown check
        if (shopManager.isOnCooldown(player.getUniqueId(), item)) {
            return Result.COOLDOWN_ACTIVE;
        }

        // Total purchase limit check
        if (item.getTotalLimit() >= 0 && item.getTotalSold() + quantity > item.getTotalLimit()) {
            return Result.TOTAL_LIMIT_REACHED;
        }

        // Per-player purchase limit check
        if (item.getPlayerLimit() >= 0) {
            try {
                int playerBought = shopManager.getPlayerPurchaseCount(player.getUniqueId(), item.getId());
                if (playerBought + quantity > item.getPlayerLimit()) {
                    return Result.PLAYER_LIMIT_REACHED;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to check player limit", e);
                return Result.TRANSACTION_FAILED;
            }
        }

        // Get price for the chosen currency
        List<PriceConfig> buyPrices = shopManager.getBuyPrices(item);
        PriceConfig selectedPrice = buyPrices.stream()
            .filter(p -> p.getCurrencyId().equals(currencyId))
            .findFirst().orElse(null);

        if (selectedPrice == null) {
            return Result.CURRENCY_UNAVAILABLE;
        }

        double totalCost = selectedPrice.getBuyPrice() * quantity;

        // Get the currency provider
        Optional<CurrencyProvider> providerOpt = currencyManager.getProvider(currencyId);
        if (providerOpt.isEmpty()) return Result.CURRENCY_UNAVAILABLE;

        CurrencyProvider provider = providerOpt.get();

        // Check balance
        if (!provider.has(player, totalCost)) {
            return Result.INSUFFICIENT_FUNDS;
        }

        // Check inventory space
        if (!hasInventorySpace(player, item, quantity)) {
            return Result.INVENTORY_FULL;
        }

        // Withdraw currency
        if (!provider.withdraw(player, totalCost)) {
            return Result.TRANSACTION_FAILED;
        }

        // Build the product item
        byte[] productData = item.getProductItem();
        ItemStack product = ItemSerializer.deserialize(productData);
        if (product == null) {
            // Refund
            provider.deposit(player, totalCost);
            return Result.TRANSACTION_FAILED;
        }

        // For trace-ID items: insert transaction FIRST to get the ID, then write it to PDC
        // This must happen BEFORE PlaceholderReplacer so %generated_id% can be resolved
        long traceId = -1;
        if (item.isGenerateUniqueId()) {
            traceId = insertTransaction(player, item, TransactionRecord.TransactionType.BUY,
                currencyId, selectedPrice.getBuyPrice(), quantity, totalCost, product);
            if (traceId < 0) {
                // Failed to insert transaction — refund and abort
                provider.deposit(player, totalCost);
                return Result.TRANSACTION_FAILED;
            }
            // Write trace ID to item PDC immediately
            var pm = product.getItemMeta();
            pm.getPersistentDataContainer().set(
                TRACE_ID_KEY, org.bukkit.persistence.PersistentDataType.LONG, traceId);
            pm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            product.setItemMeta(pm);
        }

        // Apply placeholders (now trace ID PDC is available for %generated_id%)
        if (item.isApplyPlaceholders()) {
            product = PlaceholderReplacer.apply(product, player);
            // Inject PDC tag so this customized item can be sold back
            var pm = product.getItemMeta();
            pm.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("opshop", "item_id"),
                org.bukkit.persistence.PersistentDataType.STRING, item.getId());
            pm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            product.setItemMeta(pm);
        }

        // Give items — pack size is defined by stored product amount
        int totalToGive = product.getAmount() * quantity;
        product.setAmount(1); // Normalize to single unit for the give loop

        int remaining = totalToGive;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, product.getMaxStackSize());
            ItemStack toGive = product.clone();
            toGive.setAmount(stackSize);
            // Re-apply trace ID PDC in case clone/placeholder replaced meta
            if (traceId >= 0 && !toGive.getItemMeta().getPersistentDataContainer()
                    .has(TRACE_ID_KEY, org.bukkit.persistence.PersistentDataType.LONG)) {
                var pm = toGive.getItemMeta();
                pm.getPersistentDataContainer().set(
                    TRACE_ID_KEY, org.bukkit.persistence.PersistentDataType.LONG, traceId);
                pm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                toGive.setItemMeta(pm);
            }
            var leftover = player.getInventory().addItem(toGive);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            remaining -= stackSize;
        }

        // Update stock
        if (item.getStock() >= 0) {
            item.setStock(item.getStock() - quantity);
            try {
                shopManager.saveItem(item);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to update stock", e);
            }
        }

        // Set cooldown
        shopManager.setCooldown(player.getUniqueId(), item);

        // Increment purchase counters
        if (item.getTotalLimit() >= 0) {
            item.setTotalSold(item.getTotalSold() + quantity);
            try { shopManager.saveItem(item); } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to update total sold", e); }
        }
        // Always track per-player purchases (needed for resetlimit regardless of playerLimit)
        try { shopManager.incrementPlayerPurchase(player.getUniqueId(), item.getId(), quantity); } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to update player count", e); }

        // Log transaction (for non-trace-ID items; trace-ID items were already logged above)
        if (!item.isGenerateUniqueId()) {
            logTransaction(player, item, TransactionRecord.TransactionType.BUY,
                currencyId, selectedPrice.getBuyPrice(), quantity, totalCost, product);
        }

        // Success feedback
        String productName = getProductName(product);
        String priceFormatted = provider.format(totalCost);
        String msg = plugin.getMessages().format("purchase-success",
            "{amount}", String.valueOf(quantity),
            "{item}", productName,
            "{price}", priceFormatted,
            "{currency}", currencyManager.getDisplayName(currencyId));
        if (traceId >= 0) {
            msg += plugin.getMessages().get("editor.trace-id-suffix",
                " &7[Trace ID: &f{id}&7]",
                "{id}", String.valueOf(traceId));
        }
        player.sendMessage(plugin.getMessages().get("prefix") + msg);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);

        return Result.SUCCESS;
    }

    /**
     * Execute a sell transaction.
     */
    public Result sell(Player player, String itemId, String currencyId, int quantity) {
        Optional<ShopItem> opt = shopManager.getItem(itemId);
        if (opt.isEmpty()) return Result.ITEM_NOT_FOUND;

        ShopItem item = opt.get();

        if (!item.isSellEnabled()) return Result.TRANSACTION_FAILED;
        // Placeholder items cannot be sold back (identification mismatch after PAPI round-trip)
        if (item.isApplyPlaceholders()) return Result.TRANSACTION_FAILED;

        // Get sell price for the chosen currency
        List<PriceConfig> sellPrices = shopManager.getSellPrices(item);
        PriceConfig selectedPrice = sellPrices.stream()
            .filter(p -> p.getCurrencyId().equals(currencyId))
            .findFirst().orElse(null);

        if (selectedPrice == null) {
            return Result.CURRENCY_UNAVAILABLE;
        }

        // Check if player has the item
        byte[] productData = item.getProductItem();
        ItemStack product = ItemSerializer.deserialize(productData);
        if (product == null) return Result.TRANSACTION_FAILED;

        // Build the sell template and matching logic.
        // For placeholder items: match by PDC tag (avoids legacy-serialization style corruption).
        // For non-placeholder items: match by isSimilar().
        boolean usePdc = item.isApplyPlaceholders();
        ItemStack sellTemplate = product.clone();
        int packSize = sellTemplate.getAmount();

        int playerHas = countPlayerItems(player, sellTemplate, usePdc ? item.getId() : null);
        int playerPacks = playerHas / packSize;
        if (playerPacks < quantity) {
            return Result.INSUFFICIENT_ITEMS;
        }

        // Remove items — each "quantity" unit represents one pack
        int toRemove = packSize * quantity;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !matchesForSell(stack, sellTemplate, usePdc ? item.getId() : null)) continue;
            int inStack = stack.getAmount();
            int take = Math.min(inStack, toRemove);
            stack.setAmount(inStack - take);
            contents[i] = stack.getAmount() <= 0 ? null : stack;
            toRemove -= take;
        }
        player.getInventory().setContents(contents);
        if (toRemove > 0) {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (matchesForSell(offhand, sellTemplate, usePdc ? item.getId() : null)) {
                int take = Math.min(offhand.getAmount(), toRemove);
                offhand.setAmount(offhand.getAmount() - take);
                player.getInventory().setItemInOffHand(offhand.getAmount() <= 0 ? null : offhand);
            }
        }
        player.updateInventory();

        // Deposit currency
        double totalEarned = selectedPrice.getSellPrice() * quantity;
        Optional<CurrencyProvider> providerOpt = currencyManager.getProvider(currencyId);
        if (providerOpt.isEmpty()) {
            // Refund items — packSize * quantity to restore the full item count
            giveItemToPlayer(player, product, packSize * quantity);
            return Result.CURRENCY_UNAVAILABLE;
        }
        providerOpt.get().deposit(player, totalEarned);

        // Refund purchase limits if enabled
        if (item.isRefundLimit()) {
            if (item.getTotalLimit() >= 0) {
                int newSold = Math.max(0, item.getTotalSold() - quantity);
                item.setTotalSold(newSold);
                try { shopManager.saveItem(item); } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to refund total limit", e); }
            }
            if (item.getPlayerLimit() >= 0) {
                try {
                    int current = shopManager.getPlayerPurchaseCount(player.getUniqueId(), item.getId());
                    int refund = Math.min(current, quantity);
                    if (refund > 0) {
                        shopManager.decrementPlayerPurchase(player.getUniqueId(), item.getId(), refund);
                    }
                } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to refund player limit", e); }
            }
        }

        // Log transaction
        logTransaction(player, item, TransactionRecord.TransactionType.SELL,
            currencyId, selectedPrice.getSellPrice(), quantity, totalEarned, product);

        // Success feedback
        String productName = getProductName(product);
        String priceFormatted = providerOpt.get().format(totalEarned);
        String msg = plugin.getMessages().format("sell-success",
            "{amount}", String.valueOf(quantity),
            "{item}", productName,
            "{price}", priceFormatted,
            "{currency}", currencyManager.getDisplayName(currencyId));
        player.sendMessage(plugin.getMessages().get("prefix") + msg);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f);

        return Result.SUCCESS;
    }

    // --- Helpers ---

    private boolean hasInventorySpace(Player player, ShopItem item, int quantity) {
        ItemStack product = ItemSerializer.deserialize(item.getProductItem());
        if (product == null) return false;
        int totalItems = product.getAmount() * quantity;
        int totalStacks = (int) Math.ceil((double) totalItems / product.getMaxStackSize());

        int emptySlots = 0;
        int matchingSlots = 0;
        for (ItemStack content : player.getInventory().getStorageContents()) {
            if (content == null || content.isEmpty()) {
                emptySlots++;
            } else if (content.isSimilar(product)) {
                int space = product.getMaxStackSize() - content.getAmount();
                matchingSlots += space;
            }
        }
        return (emptySlots + (matchingSlots / product.getMaxStackSize())) >= totalStacks;
    }

    private static final org.bukkit.NamespacedKey SELL_MATCH_KEY =
        new org.bukkit.NamespacedKey("opshop", "item_id");
    public static final org.bukkit.NamespacedKey TRACE_ID_KEY =
        new org.bukkit.NamespacedKey("opshop", "trace_id");

    /** Match for sell: pdcId != null → check PDC tag + isSimilar; pdcId == null → use isSimilar(). */
    private static boolean matchesForSell(ItemStack stack, ItemStack template, String pdcId) {
        if (stack == null || stack.isEmpty()) return false;
        if (pdcId != null) {
            // PDC must match AND item must be isSimilar to the stored template
            // (prevents selling emptied shulker boxes etc.)
            if (stack.hasItemMeta()) {
                String tag = stack.getItemMeta().getPersistentDataContainer()
                    .get(SELL_MATCH_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (pdcId.equals(tag) && stack.isSimilar(template)) return true;
            }
            return false;
        }
        return stack.isSimilar(template);
    }

    private int countPlayerItems(Player player, ItemStack template, String pdcId) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (matchesForSell(item, template, pdcId)) count += item.getAmount();
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matchesForSell(offhand, template, pdcId)) count += offhand.getAmount();
        return count;
    }

    private void giveItemToPlayer(Player player, ItemStack item, int quantity) {
        int remaining = quantity;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, item.getMaxStackSize());
            ItemStack toGive = item.clone();
            toGive.setAmount(stackSize);
            var leftover = player.getInventory().addItem(toGive);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= stackSize;
        }
    }

    private String getProductName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return legacy.serialize(item.getItemMeta().displayName());
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }

    private void logTransaction(Player player, ShopItem item,
                                TransactionRecord.TransactionType type,
                                String currencyId, double pricePerUnit,
                                int quantity, double totalPrice, ItemStack product) {
        try {
            TransactionRecord rec = new TransactionRecord(
                player.getUniqueId(),
                player.getName(),
                item.getId(),
                type,
                currencyId,
                pricePerUnit,
                quantity,
                totalPrice,
                ItemSerializer.serialize(product),
                System.currentTimeMillis()
            );
            databaseManager.getTransactionRepository().insert(rec);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to log transaction", e);
        }
    }

    /**
     * Insert a transaction and return the generated ID.
     * Used for trace-ID items where the ID must be written to the item before giving it.
     */
    private long insertTransaction(Player player, ShopItem item,
                                   TransactionRecord.TransactionType type,
                                   String currencyId, double pricePerUnit,
                                   int quantity, double totalPrice, ItemStack product) {
        try {
            TransactionRecord rec = new TransactionRecord(
                player.getUniqueId(),
                player.getName(),
                item.getId(),
                type,
                currencyId,
                pricePerUnit,
                quantity,
                totalPrice,
                ItemSerializer.serialize(product),
                System.currentTimeMillis()
            );
            return databaseManager.getTransactionRepository().insert(rec);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to insert transaction for trace ID", e);
            return -1;
        }
    }
}
