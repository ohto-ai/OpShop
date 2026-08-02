package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.currency.CurrencyType;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.PriceConfig;
import com.ohtoai.opshop.shop.ShopItem;
import com.ohtoai.opshop.shop.ShopManager;
import com.ohtoai.opshop.shop.TransactionProcessor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Item detail GUI with 6 buy/sell buttons (x1, x8, xN on each side).
 * Buy buttons on left, sell buttons on right, currency selection in middle rows.
 * Stays open after transactions.
 */
public class ItemDetailGui implements GuiHolder {

    private final OpShopPlugin plugin;
    private final ShopMainGui parent;
    private final ShopItem shopItem;
    private final TransactionProcessor transactionProcessor;
    private final ShopManager shopManager;
    private final CurrencyManager currencyManager;
    private String m(String k, String e, String... r) { return plugin.getMessages().get(k, e, r); }

    private Inventory inventory;
    private String selectedCurrency; // For multi-currency items, which currency is selected
    private Player currentPlayer;
    private int maxStackSize = 64;

    private static final int ROWS = 5;

    // Layout:
    // Row 0: Buy1(slot 2) | Sell1(slot 6)
    // Row 1: Buy8(slot 11) | Sell8(slot 15)
    // Row 2: Buy64(slot 20) | Sell64(slot 24)
    // Row 3: currencies(27-35)
    // Row 4: nav

    private static final int SLOT_ITEM = 13;  // item preview row 1 center

    private static final int SLOT_BUY_1  = 2;
    private static final int SLOT_BUY_8  = 11;
    private static final int SLOT_BUY_64 = 20;

    private static final int SLOT_SELL_1  = 6;
    private static final int SLOT_SELL_8  = 15;
    private static final int SLOT_SELL_64 = 24;

    private static final int[] CURRENCY_SLOTS = {29, 31, 33};

    private static final int SLOT_BACK  = 39;
    private static final int SLOT_CLOSE = 41;

    public ItemDetailGui(OpShopPlugin plugin, ShopMainGui parent, ShopItem shopItem,
                         TransactionProcessor transactionProcessor, ShopManager shopManager,
                         CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.parent = parent;
        this.shopItem = shopItem;
        this.transactionProcessor = transactionProcessor;
        this.shopManager = shopManager;
        this.currencyManager = currencyManager;

        // Fixed x64 for max stack
        this.maxStackSize = 64;
    }

    public void open(Player player) {
        this.currentPlayer = player;
        // Auto-select first available currency (buy first, then sell)
        List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
        List<PriceConfig> sellPrices = shopManager.getSellPrices(shopItem);
        if (!buyPrices.isEmpty()) {
            this.selectedCurrency = buyPrices.get(0).getCurrencyId();
        } else if (!sellPrices.isEmpty()) {
            this.selectedCurrency = sellPrices.get(0).getCurrencyId();
        } else {
            this.selectedCurrency = null;
        }
        buildInventory();
        player.openInventory(inventory);
    }

    private void buildInventory() {
        buildInventory(false);
    }

    private void buildInventory(boolean reopen) {
        String title = "&8" + shopItem.getId();
        if (title.length() > 32) title = "&8Item Details";
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Item display
        ItemStack display = ItemSerializer.deserialize(shopItem.getDisplayItem());
        if (display != null) {
            if (shopItem.isApplyPlaceholders()) {
                display = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(display, currentPlayer);
            }
            inventory.setItem(SLOT_ITEM, display);
        }

        List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
        List<PriceConfig> sellPrices = shopManager.getSellPrices(shopItem);

        // --- Buy buttons ---
        if (selectedCurrency != null && shopItem.isBuyEnabled()) {
            PriceConfig pc = buyPrices.stream()
                .filter(p -> p.getCurrencyId().equals(selectedCurrency)).findFirst().orElse(null);
            if (pc != null && pc.getBuyPrice() > 0) {
                if (shopItem.isGenerateUniqueId()) {
                    // Trace ID mode: only x1 purchase, no bulk buy
                    buildBuyButton(SLOT_BUY_1, 1, pc.getBuyPrice());
                } else {
                    buildBuyButton(SLOT_BUY_64, 64, pc.getBuyPrice());
                    buildBuyButton(SLOT_BUY_8, 8, pc.getBuyPrice());
                    buildBuyButton(SLOT_BUY_1, 1, pc.getBuyPrice());
                }
            }
        }

        // --- Sell buttons --- (hidden for placeholder items and trace ID items)
        if (selectedCurrency != null && shopItem.isSellEnabled() && !shopItem.isApplyPlaceholders()
            && !shopItem.isGenerateUniqueId()) {
            PriceConfig pc = sellPrices.stream()
                .filter(p -> p.getCurrencyId().equals(selectedCurrency)).findFirst().orElse(null);
            if (pc != null && pc.getSellPrice() > 0) {
                buildSellButton(SLOT_SELL_64, 64, pc.getSellPrice());
                buildSellButton(SLOT_SELL_8, 8, pc.getSellPrice());
                buildSellButton(SLOT_SELL_1, 1, pc.getSellPrice());
            }
        }

        // --- Currency buttons (row 3) ---
        List<PriceConfig> allPrices = new ArrayList<>();
        allPrices.addAll(buyPrices);
        for (PriceConfig pc : sellPrices) {
            if (allPrices.stream().noneMatch(p -> p.getCurrencyId().equals(pc.getCurrencyId()))) {
                allPrices.add(pc);
            }
        }

        for (int i = 0; i < allPrices.size() && i < CURRENCY_SLOTS.length; i++) {
            PriceConfig pc = allPrices.get(i);
            var providerOpt = currencyManager.getProvider(pc.getCurrencyId());
            if (providerOpt.isEmpty()) continue;

            CurrencyProvider provider = providerOpt.get();
            double balance = provider.getBalance(currentPlayer);
            boolean isSelected = pc.getCurrencyId().equals(selectedCurrency);

            List<String> lore = new ArrayList<>();
            lore.add(m("gui.purchase.balance", "&7Balance: &f{balance}", "{balance}", provider.format(balance)));
            if (pc.canBuy()) lore.add(m("gui.buy-each", "&aBuy: {price} each", "{price}", formatPrice(pc.getBuyPrice())));
            if (pc.canSell()) lore.add(m("gui.sell-each", "&6Sell: {price} each", "{price}", formatPrice(pc.getSellPrice())));
            lore.add("");
            if (isSelected) lore.add(m("gui.selected", "&a&l▶ SELECTED"));

            // Currency icon: Vault=Gold Ingot, PlayerPoints=Paper, Custom=actual item
            ItemStack icon;
            if (provider.getType() == CurrencyType.CUSTOM_ITEM) {
                var ccp = (com.ohtoai.opshop.currency.provider.CustomItemCurrencyProvider) provider;
                icon = ccp.createCurrencyItem(1);
                var m = icon.getItemMeta();
                m.lore(lore.stream().map(l -> GuiUtils.toComponent(Messages.colorize(l))).toList());
                if (isSelected) {
                    m.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                    m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                }
                icon.setItemMeta(m);
            } else {
                Material mat = switch (provider.getType()) {
                    case VAULT -> Material.GOLD_INGOT;
                    case PLAYER_POINTS -> Material.PAPER;
                    default -> isSelected ? Material.LIME_WOOL : Material.LIGHT_GRAY_WOOL;
                };
                icon = GuiUtils.makeItem(mat, (isSelected ? "&a&l" : "&7") + provider.getDisplayName(), lore);
                if (isSelected) {
                    var m = icon.getItemMeta();
                    m.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                    m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                    icon.setItemMeta(m);
                }
            }
            inventory.setItem(CURRENCY_SLOTS[i], icon);
        }

        // --- Navigation (row 4) ---
        inventory.setItem(SLOT_BACK, GuiUtils.makeItem(Material.ARROW,
            m("gui.back-to-shop", "&6← Back to Shop"), List.of(m("gui.back-item-list", "&7Return to item list"))));
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of(m("gui.close-desc", "&7Close the shop"))));

        // Reopen inventory for the player to reflect changes on client
        if (reopen && currentPlayer != null) {
            currentPlayer.openInventory(inventory);
        }
    }

    private void buildBuyButton(int slot, int qty, double unitPrice) {
        double total = unitPrice * qty;
        var providerOpt = currencyManager.getProvider(selectedCurrency);
        String curName = providerOpt.map(CurrencyProvider::getDisplayName).orElse(selectedCurrency);

        List<String> lore = new ArrayList<>();
        lore.add(m("gui.buy-x-desc", "&7Buy &f{qty}x &7for &f{price} {currency}",
            "{qty}", String.valueOf(qty), "{price}", formatPrice(total), "{currency}", curName));
        lore.add("");
        lore.add(m("gui.purchase.click-to-purchase", "&eClick to buy!"));

        inventory.setItem(slot, GuiUtils.makeItem(Material.LIME_WOOL,
            m("gui.buy-x", "&aBuy x{qty}", "{qty}", String.valueOf(qty)), lore));
    }

    private void buildSellButton(int slot, int qty, double unitPrice) {
        double total = unitPrice * qty;
        ItemStack product = ItemSerializer.deserialize(shopItem.getProductItem());
        int playerHas = countPlayerItems(product);
        List<String> lore = new ArrayList<>();
        lore.add(m("gui.sell-x-desc", "&7Sell &f{qty}x &7for &f{price} {currency}",
            "{qty}", String.valueOf(qty), "{price}", formatPrice(total),
            "{currency}", currencyManager.getProvider(selectedCurrency).map(CurrencyProvider::getDisplayName).orElse(selectedCurrency)));
        lore.add(m("gui.sell.you-have", "&7You have: &f{count}", "{count}", String.valueOf(playerHas)));
        lore.add("");
        lore.add(m("gui.sell.click-to-sell", "&eClick to sell!"));
        inventory.setItem(slot, GuiUtils.makeItem(Material.GOLD_BLOCK,
            m("gui.sell-x", "&6Sell x{qty}", "{qty}", String.valueOf(qty)), lore));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        if (slot == SLOT_BACK) {
            player.closeInventory();
            parent.returnToShop(player);
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Currency selection
        List<PriceConfig> allPrices = new ArrayList<>();
        allPrices.addAll(shopManager.getBuyPrices(shopItem));
        for (PriceConfig pc : shopManager.getSellPrices(shopItem)) {
            if (allPrices.stream().noneMatch(p -> p.getCurrencyId().equals(pc.getCurrencyId()))) {
                allPrices.add(pc);
            }
        }
        for (int i = 0; i < allPrices.size() && i < CURRENCY_SLOTS.length; i++) {
            if (CURRENCY_SLOTS[i] == slot) {
                selectedCurrency = allPrices.get(i).getCurrencyId();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                buildInventory(true);
                return;
            }
        }

        // Buy buttons
        if (slot == SLOT_BUY_1) executeBuy(player, 1);
        else if (slot == SLOT_BUY_8) executeBuy(player, 8);
        else if (slot == SLOT_BUY_64) executeBuy(player, 64);

        // Sell buttons
        else if (slot == SLOT_SELL_1) executeSell(player, 1);
        else if (slot == SLOT_SELL_8) executeSell(player, 8);
        else if (slot == SLOT_SELL_64) executeSell(player, 64);
    }

    private void executeBuy(Player player, int qty) {
        if (selectedCurrency == null) return;
        TransactionProcessor.Result result = transactionProcessor.buy(
            player, shopItem.getId(), selectedCurrency, qty);
        sendFeedback(player, result, true);
        buildInventory(true);
    }

    private void executeSell(Player player, int qty) {
        if (selectedCurrency == null) return;
        TransactionProcessor.Result result = transactionProcessor.sell(
            player, shopItem.getId(), selectedCurrency, qty);
        sendFeedback(player, result, false);
        buildInventory(true);
    }

    private void sendFeedback(Player player, TransactionProcessor.Result result, boolean isBuy) {
        String prefix = plugin.getMessages().getPrefix();
        switch (result) {
            // SUCCESS: TransactionProcessor already handles message + sound
            case SUCCESS -> {}
            case INSUFFICIENT_FUNDS -> {
                var provider = currencyManager.getProvider(selectedCurrency);
                double balance = provider.map(p -> p.getBalance(player)).orElse(0.0);
                List<PriceConfig> buyPrices = shopManager.getBuyPrices(shopItem);
                PriceConfig pc = buyPrices.stream()
                    .filter(p -> p.getCurrencyId().equals(selectedCurrency)).findFirst().orElse(null);
                double needed = pc != null ? pc.getBuyPrice() : 0;
                String msg = plugin.getMessages().format("not-enough-currency",
                    "{currency}", provider.map(CurrencyProvider::getDisplayName).orElse(selectedCurrency),
                    "{amount}", formatPrice(needed),
                    "{balance}", formatPrice(balance));
                player.sendMessage(prefix + msg);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            }
            case INSUFFICIENT_ITEMS ->
                player.sendMessage(prefix + plugin.getMessages().get("not-enough-items"));
            case INVENTORY_FULL ->
                player.sendMessage(prefix + plugin.getMessages().get("inventory-full"));
            case OUT_OF_STOCK ->
                player.sendMessage(prefix + plugin.getMessages().get("out-of-stock"));
            case TOTAL_LIMIT_REACHED ->
                player.sendMessage(prefix + Messages.colorize("&cThis item has reached its total purchase limit!"));
            case PLAYER_LIMIT_REACHED ->
                player.sendMessage(prefix + Messages.colorize("&cYou have reached your purchase limit for this item!"));
            case COOLDOWN_ACTIVE ->
                player.sendMessage(prefix + plugin.getMessages().get("cooldown-active"));
            case CURRENCY_UNAVAILABLE ->
                player.sendMessage(prefix + plugin.getMessages().get("currency-disabled"));
            default -> {}
        }
    }

    @Override
    public void refresh() { buildInventory(); }

    @Override
    public Inventory getInventory() { return inventory; }

    private static final org.bukkit.NamespacedKey SELL_KEY = new org.bukkit.NamespacedKey("opshop", "item_id");

    private boolean matchesForSell(ItemStack stack, ItemStack template) {
        if (stack == null || stack.isEmpty()) return false;
        if (shopItem.isApplyPlaceholders()) {
            if (stack.hasItemMeta()) {
                String tag = stack.getItemMeta().getPersistentDataContainer()
                    .get(SELL_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (shopItem.getId().equals(tag) && stack.isSimilar(template)) return true;
            }
            return false;
        }
        return stack.isSimilar(template);
    }

    private int countPlayerItems(ItemStack template) {
        if (template == null || currentPlayer == null) return 0;
        int packSize = template.getAmount();
        int totalItems = 0;
        for (ItemStack item : currentPlayer.getInventory().getContents()) {
            if (matchesForSell(item, template)) totalItems += item.getAmount();
        }
        ItemStack oh = currentPlayer.getInventory().getItemInOffHand();
        if (matchesForSell(oh, template)) totalItems += oh.getAmount();
        return totalItems / packSize;
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
