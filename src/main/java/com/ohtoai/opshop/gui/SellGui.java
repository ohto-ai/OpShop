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
 * Sell confirmation GUI -- quantity selector + currency buttons.
 *
 * After a successful sell the GUI stays open so the player can sell more.
 * The player's item count is recalculated and the GUI refreshes.
 * Multi-currency items show one clickable button per accepted currency
 * inline rather than opening a sub-GUI.
 */
public class SellGui implements GuiHolder {

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
    private int playerHas = 0;

    private static final int ROWS = 4;
    private static final int SLOT_ITEM = 13;
    private static final int[] QTY_SLOTS = {19, 20, 21};
    private static final int[] QTY_VALUES = {1, 8, 64};
    private static final int SLOT_BACK = 31;
    private static final int SLOT_CLOSE = 35;

    // Currency slots in row 3: 27-30, then 32-34 (skip 31=back, 35=close)
    private static final int[] CURRENCY_SLOTS = {27, 28, 29, 30, 32, 33, 34};

    public SellGui(OpShopPlugin plugin, ShopMainGui parent, ShopItem shopItem,
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

        // Count how many of this item the player has
        recalculatePlayerHas(player);

        if (playerHas <= 0) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("not-enough-items")));
            parent.returnToShop(player);
            return;
        }

        // Clamp quantity to what the player actually has
        if (quantity > playerHas) {
            quantity = playerHas;
        }

        buildInventory(player);
        player.openInventory(inventory);
    }

    private void buildInventory(Player player) {
        String title = "&8Sell: " + shopItem.getId();
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Show the item being sold (centered in rows 0-1)
        ItemStack displayItem = ItemSerializer.deserialize(shopItem.getDisplayItem());
        if (displayItem != null) {
            if (shopItem.isApplyPlaceholders()) {
                displayItem = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(displayItem, player);
            }
            inventory.setItem(SLOT_ITEM, displayItem);
        }

        // Quantity selectors (row 2): x1, x8, x64 (limited by playerHas)
        for (int i = 0; i < QTY_SLOTS.length; i++) {
            int qty = QTY_VALUES[i];
            if (qty > playerHas) {
                // Show unavailable qty as a barrier
                inventory.setItem(QTY_SLOTS[i], GuiUtils.makeItem(Material.BARRIER,
                    "&cx" + qty,
                    List.of(m("gui.sell.not-enough-qty", "&7You only have &f{count} &7items", "{count}", String.valueOf(playerHas)))));
                continue;
            }
            boolean selected = quantity == qty;
            Material mat = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(QTY_SLOTS[i], GuiUtils.makeItem(mat,
                (selected ? "&a" : "&7") + "x" + qty,
                List.of(m("gui.purchase.quantity", "&7Click to select quantity: {qty}", "{qty}", String.valueOf(qty)))));
        }
        List<PriceConfig> sellPrices = shopManager.getSellPrices(shopItem);
        if (sellPrices.isEmpty()) {
            inventory.setItem(CURRENCY_SLOTS[0], GuiUtils.makeItem(Material.RED_WOOL,
                m("gui.sell.cannot-sell", "&cCannot Sell"),
                List.of(m("gui.sell.no-prices-1", "&7No sell prices configured"),
                    m("gui.sell.no-prices-2", "&7Contact an administrator"))));
        } else {
            int limit = Math.min(sellPrices.size(), CURRENCY_SLOTS.length);
            for (int i = 0; i < limit; i++) {
                PriceConfig pc = sellPrices.get(i);
                double total = pc.getSellPrice() * quantity;
                var providerOpt = currencyManager.getProvider(pc.getCurrencyId());
                if (providerOpt.isEmpty()) continue;

                CurrencyProvider provider = providerOpt.get();
                String currencyName = provider.getDisplayName();

                List<String> lore = new ArrayList<>();
                lore.add(m("gui.sell.you-have", "&7You have: &f{count} &7items", "{count}", String.valueOf(playerHas)));
                lore.add(m("gui.sell.selling", "&7Selling: &f{count}", "{count}", String.valueOf(quantity)));
                lore.add("");
                lore.add(m("gui.sell.earn-per", "&7Earn per item: &f{price} {currency}", "{price}", formatPrice(pc.getSellPrice()), "{currency}", currencyName));
                lore.add(m("gui.sell.total-earn", "&7Total earn: &f{price} {currency}", "{price}", formatPrice(total), "{currency}", currencyName));
                lore.add("");
                lore.add(m("gui.sell.click-to-sell", "&a&lClick to sell!"));
                inventory.setItem(CURRENCY_SLOTS[i], GuiUtils.makeItem(Material.GOLD_BLOCK,
                    m("gui.sell.sell-for", "&6&lSell for {currency}", "{currency}", currencyName), lore));
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
                int qty = QTY_VALUES[i];
                if (qty <= playerHas && quantity != qty) {
                    quantity = qty;
                    buildInventory(player);
                }
                return;
            }
        }

        // Currency button click -- execute sell with the chosen currency
        List<PriceConfig> sellPrices = shopManager.getSellPrices(shopItem);
        int limit = Math.min(sellPrices.size(), CURRENCY_SLOTS.length);
        for (int i = 0; i < limit; i++) {
            if (CURRENCY_SLOTS[i] == slot) {
                executeSell(player, sellPrices.get(i).getCurrencyId());
                return;
            }
        }
    }

    public void executeSell(Player player, String currencyId) {
        TransactionProcessor.Result result = transactionProcessor.sell(
            player, shopItem.getId(), currencyId, quantity);

        switch (result) {
            case SUCCESS -> {
                // TransactionProcessor already sends the success message
                // and plays the sound. Recalculate player items and refresh.
                recalculatePlayerHas(player);
                if (playerHas <= 0) {
                    // Player sold everything -- return to shop
                    player.closeInventory();
                    parent.returnToShop(player);
                } else {
                    // Clamp quantity to remaining items and refresh
                    if (quantity > playerHas) {
                        quantity = playerHas;
                    }
                    buildInventory(player);
                }
            }
            case INSUFFICIENT_ITEMS -> {
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("not-enough-items"));
                // Recalculate -- the player may have dropped or lost items
                recalculatePlayerHas(player);
                if (playerHas <= 0) {
                    player.closeInventory();
                    parent.returnToShop(player);
                } else {
                    if (quantity > playerHas) quantity = playerHas;
                    buildInventory(player);
                }
            }
            case CURRENCY_UNAVAILABLE ->
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("currency-disabled"));
            default -> player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cTransaction failed."));
        }
    }

    /**
     * Recalculate how many packs of the product item the player has.
     * The stored product's amount defines the pack size (e.g. 32 diamonds per pack).
     */
    private static final org.bukkit.NamespacedKey SELL_KEY = new org.bukkit.NamespacedKey("opshop", "item_id");

    private boolean matchesForSell(ItemStack stack, ItemStack template) {
        if (stack == null || stack.isEmpty()) return false;
        if (shopItem.isApplyPlaceholders()) {
            // PDC must match AND item must pass isSimilar (prevents shulker exploit)
            if (stack.hasItemMeta()) {
                String tag = stack.getItemMeta().getPersistentDataContainer()
                    .get(SELL_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (shopItem.getId().equals(tag) && stack.isSimilar(template)) return true;
            }
            return false;
        }
        return stack.isSimilar(template);
    }

    private void recalculatePlayerHas(Player player) {
        ItemStack product = ItemSerializer.deserialize(shopItem.getProductItem());
        playerHas = 0;
        if (product != null) {
            int packSize = product.getAmount();
            int totalItems = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (matchesForSell(item, product)) totalItems += item.getAmount();
            }
            ItemStack oh = player.getInventory().getItemInOffHand();
            if (matchesForSell(oh, product)) totalItems += oh.getAmount();
            playerHas = totalItems / packSize;
        }
    }

    @Override
    public void refresh() {
        if (player != null) {
            recalculatePlayerHas(player);
            if (playerHas <= 0) {
                player.closeInventory();
                parent.returnToShop(player);
            } else {
                if (quantity > playerHas) quantity = playerHas;
                buildInventory(player);
            }
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
}
