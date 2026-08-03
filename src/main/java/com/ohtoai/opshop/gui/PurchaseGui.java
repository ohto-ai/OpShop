package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.PriceConfig;
import com.ohtoai.opshop.shop.ShopItem;
import com.ohtoai.opshop.shop.ShopManager;
import com.ohtoai.opshop.shop.TransactionProcessor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Purchase confirmation GUI -- quantity selector + currency buttons.
 *
 * After a purchase the GUI stays open so the player can buy again.
 * Multi-currency items show one clickable button per accepted currency
 * inline rather than opening a sub-GUI.
 */
public class PurchaseGui implements GuiHolder {

    private final OpShopPlugin plugin;
    private final ShopMainGui parent;
    private final ShopItem shopItem;
    private final TransactionProcessor transactionProcessor;
    private final ShopManager shopManager;
    private final CurrencyManager currencyManager;
    private String m(String k, String e, String... r) { return plugin.getMessages().get(k, e, r); }
    private Player player;

    private Inventory inventory;
    private int quantity = 1;

    private static final int ROWS = 4;
    private static final int SLOT_ITEM = 13;
    private static final int[] QTY_SLOTS = {19, 20, 21};
    private static final int[] QTY_VALUES = {1, 8, 64};
    private static final int SLOT_BACK = 31;
    private static final int SLOT_CLOSE = 35;

    // Currency slots in row 3: 27-30, then 32-34 (skip 31=back, 35=close)
    private static final int[] CURRENCY_SLOTS = {27, 28, 29, 30, 32, 33, 34};

    public PurchaseGui(OpShopPlugin plugin, ShopMainGui parent, ShopItem shopItem,
                       TransactionProcessor transactionProcessor, ShopManager shopManager,
                       CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.parent = parent;
        this.shopItem = shopItem;
        this.transactionProcessor = transactionProcessor;
        this.shopManager = shopManager;
        this.currencyManager = currencyManager;
    }

    public void open(Player player) {
        this.player = player;
        buildInventory(player);
        player.openInventory(inventory);
    }

    private void buildInventory(Player player) {
        String title = m("gui.purchase.title", "&8Buy: {id}", "{id}", shopItem.getId());
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Show the item being purchased (centered in rows 0-1)
        ItemStack displayItem = ItemSerializer.deserialize(shopItem.getDisplayItem());
        if (displayItem != null) {
            if (shopItem.isApplyPlaceholders()) {
                displayItem = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(displayItem, player);
            }
            inventory.setItem(SLOT_ITEM, displayItem);
        }

        // Quantity selectors (row 2): x1, x8, x64
        for (int i = 0; i < QTY_SLOTS.length; i++) {
            int qty = QTY_VALUES[i];
            boolean selected = quantity == qty;
            Material mat = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(QTY_SLOTS[i], GuiUtils.makeItem(mat,
                (selected ? "&a" : "&7") + "x" + qty,
                List.of(m("gui.purchase.quantity", "&7Click to select quantity: {qty}", "{qty}", String.valueOf(qty)))));
        }
        List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
        if (buyPrices.isEmpty()) {
            inventory.setItem(CURRENCY_SLOTS[0], GuiUtils.makeItem(Material.RED_WOOL,
                m("gui.purchase.cannot-purchase", "&cCannot Purchase"),
                List.of(m("gui.purchase.no-methods-1", "&7No payment methods available"),
                    m("gui.purchase.no-methods-2", "&7Contact an administrator"))));
        } else {
            int limit = Math.min(buyPrices.size(), CURRENCY_SLOTS.length);
            for (int i = 0; i < limit; i++) {
                PriceConfig pc = buyPrices.get(i);
                double total = pc.getBuyPrice() * quantity;
                var providerOpt = currencyManager.getProvider(pc.getCurrencyId());
                if (providerOpt.isEmpty()) continue;

                CurrencyProvider provider = providerOpt.get();
                String currencyName = provider.getDisplayName();
                double balance = provider.getBalance(player);
                boolean canAfford = balance >= total;

                List<String> lore = new ArrayList<>();
                lore.add(m("gui.purchase.price-per", "&7Price per item: &f{price} {currency}", "{price}", formatPrice(pc.getBuyPrice()), "{currency}", currencyName));
                lore.add(m("gui.purchase.total", "&7Total: &f{price} {currency}", "{price}", formatPrice(total), "{currency}", currencyName));
                lore.add("");
                lore.add(m("gui.purchase.balance", "&7Your balance: &f{balance}", "{balance}", provider.format(balance)));
                lore.add("");
                if (canAfford) {
                    lore.add(m("gui.purchase.click-to-purchase", "&a&lClick to purchase!"));
                } else {
                    lore.add(m("gui.purchase.not-enough", "&cNot enough {currency}!", "{currency}", currencyName));
                    lore.add(m("gui.purchase.need-more", "&7You need {amount} &7more", "{amount}", provider.format(total - balance)));
                }
                inventory.setItem(CURRENCY_SLOTS[i], GuiUtils.makeItem(
                    canAfford ? Material.LIME_WOOL : Material.RED_WOOL,
                    (canAfford ? "&a&l" : "&c&l") + m("gui.purchase.buy-with", "Buy with {currency}", "{currency}", currencyName),
                    lore));
            }
        }
        inventory.setItem(SLOT_BACK, GuiUtils.makeItem(Material.ARROW,
            m("gui.back-to-shop", "&6← Back to Shop"), List.of(m("gui.back-desc", "&7Return to shop menu"))));
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of(m("gui.close-desc", "&7Close the shop"))));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (player == null) return;
        int slot = event.getSlot();

        // Back -- close and return to shop main menu
        if (slot == SLOT_BACK) {
            player.closeInventory();
            parent.returnToShop(player);
            return;
        }

        // Close
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Quantity selection
        for (int i = 0; i < QTY_SLOTS.length; i++) {
            if (QTY_SLOTS[i] == slot) {
                int newQty = QTY_VALUES[i];
                if (quantity != newQty) {
                    quantity = newQty;
                    buildInventory(player);
                }
                return;
            }
        }

        // Currency button click -- execute purchase with the chosen currency
        List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
        int limit = Math.min(buyPrices.size(), CURRENCY_SLOTS.length);
        for (int i = 0; i < limit; i++) {
            if (CURRENCY_SLOTS[i] == slot) {
                executePurchase(player, buyPrices.get(i).getCurrencyId());
                return;
            }
        }
    }

    public void executePurchase(Player player, String currencyId) {
        TransactionProcessor.Result result = transactionProcessor.buy(
            player, shopItem.getId(), currencyId, quantity);

        switch (result) {
            case SUCCESS -> {
                // TransactionProcessor already sends the success message
                // and plays the sound. Refresh the GUI with updated balances.
                buildInventory(player);
            }
            case INSUFFICIENT_FUNDS -> {
                var provider = currencyManager.getProvider(currencyId);
                double balance = provider.map(p -> p.getBalance(player)).orElse(0.0);
                List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
                PriceConfig pc = buyPrices.stream()
                    .filter(p -> p.getCurrencyId().equals(currencyId)).findFirst().orElse(null);
                double needed = pc != null ? pc.getBuyPrice() * quantity : 0;
                String msg = plugin.getMessages().format("not-enough-currency",
                    "{currency}", provider.map(CurrencyProvider::getDisplayName).orElse(currencyId),
                    "{amount}", formatPrice(needed),
                    "{balance}", formatPrice(balance));
                player.sendMessage(plugin.getMessages().getPrefix() + msg);
                // Keep GUI open, refresh to show latest balances
                buildInventory(player);
            }
            case INVENTORY_FULL ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("inventory-full"));
            case NO_PERMISSION ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("no-permission"));
            case GUILD_LOCKED -> {
                player.sendMessage(plugin.getMessages().getPrefix()
                    + Messages.colorize(plugin.getMessages().get("guild-locked",
                        "&c&lThis item is only available to specific guild members!")));
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            }
            case COOLDOWN_ACTIVE -> {
                long remaining = shopManager.getCooldownRemaining(player.getUniqueId(), shopItem);
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().format("cooldown-active",
                    "{time}", formatCooldown(remaining)));
            }
            case TOTAL_LIMIT_REACHED ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("total-limit-reached"));
            case PLAYER_LIMIT_REACHED ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-limit-reached"));
            case OUT_OF_STOCK ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("out-of-stock"));
            case CURRENCY_UNAVAILABLE ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("currency-disabled"));
            default -> player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + m("editor.transaction-failed", "&cTransaction failed.")));
        }
    }

    @Override
    public void refresh() {
        if (player != null) {
            buildInventory(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setQuantity(int qty) {
        this.quantity = qty;
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }

    private String formatCooldown(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes < 60) return minutes + "m " + secs + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }
}
