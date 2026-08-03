package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * GUI for editing buy/sell prices of a single custom currency on a shop item.
 * <p>
 * 3-row layout: currency icon (center), buy price (left), sell price (right).
 * Click controls mirror the main editor: L+1 / R-1 / ShiftL+10 / ShiftR-10 / Q+100 / CtrlQ-100.
 * Changes are applied directly to the in-memory {@link ShopItem}; final save is
 * handled by {@link ShopEditorGui}.
 */
public class CustomCurrencyPriceGui implements GuiHolder {

    private static final int ROWS = 3;
    private static final int SLOT_ICON = 13;
    private static final int SLOT_BUY = 11;
    private static final int SLOT_SELL = 15;
    private static final int SLOT_SET_BOTH = 21;
    private static final int SLOT_BACK = 22;

    private final OpShopPlugin plugin;
    private final CurrencyManager currencyManager;
    private final ShopItem shopItem;
    private final String currencyId;
    private final CustomCurrencyListGui parent;
    private Inventory inventory;

    public CustomCurrencyPriceGui(OpShopPlugin plugin, CurrencyManager currencyManager,
                                  ShopItem shopItem, String currencyId,
                                  CustomCurrencyListGui parent) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.shopItem = shopItem;
        this.currencyId = currencyId;
        this.parent = parent;
    }

    private String m(String key, String en, String... reps) {
        return plugin.getMessages().get(key, en, reps);
    }

    public void open(Player player) {
        build(player);
        player.openInventory(inventory);
    }

    private void build(Player player) {
        CurrencyDef def = currencyManager.getDefinition(currencyId);
        String displayName = def != null ? def.displayName() : currencyId;
        String title = m("editor.custom-currency-price-editor",
            "&8Price: {currency}",
            "{currency}", displayName);
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Currency icon (center)
        ItemStack icon = buildCurrencyIcon(def);
        inventory.setItem(SLOT_ICON, icon);

        // Buy price slot
        var prices = shopItem.getCustomCurrencyPrices().get(currencyId);
        double buyPrice = prices != null ? prices.buyPrice() : 0;
        double sellPrice = prices != null ? prices.sellPrice() : 0;

        inventory.setItem(SLOT_BUY, GuiUtils.makeItem(Material.LIME_DYE,
            m("editor.custom-buy-price", "&eBuy Price: &f{value}", "{value}", formatPrice(buyPrice)),
            List.of(
                m("editor.price-hint", "&7Current: &f{value}", "{value}", formatPrice(buyPrice)),
                m("editor.price-controls-1", "&7L: +1 | R: -1"),
                m("editor.price-controls-2", "&7Shift+L: +10 | Shift+R: -10"),
                m("editor.price-controls-3", "&7Drop(Q): +100 | Ctrl+Q: -100"))));

        // Sell price slot
        inventory.setItem(SLOT_SELL, GuiUtils.makeItem(Material.RED_DYE,
            m("editor.custom-sell-price", "&eSell Price: &f{value}", "{value}", formatPrice(sellPrice)),
            List.of(
                m("editor.price-hint", "&7Current: &f{value}", "{value}", formatPrice(sellPrice)),
                m("editor.price-controls-1", "&7L: +1 | R: -1"),
                m("editor.price-controls-2", "&7Shift+L: +10 | Shift+R: -10"),
                m("editor.price-controls-3", "&7Drop(Q): +100 | Ctrl+Q: -100"))));

        // Set Both button — opens anvil to directly type "buy sell"
        inventory.setItem(SLOT_SET_BOTH, GuiUtils.makeItem(Material.NAME_TAG,
            m("editor.set-both", "&e✎ Set Both Prices"),
            List.of(m("editor.set-both-desc", "&7Open anvil to type &fbuy sell"),
                m("editor.set-both-desc2", "&7e.g. &f100 50"))));

        // Back button
        inventory.setItem(SLOT_BACK, GuiUtils.makeItem(Material.ARROW,
            m("gui.back", "&6← Back"),
            List.of(m("gui.back-item-list", "&7Return to currency list"))));
    }

    /** Build a display icon for the currency, preserving all original item properties. */
    private ItemStack buildCurrencyIcon(CurrencyDef def) {
        // Try to get the full original item from the provider first
        var provider = currencyManager.getProvider(currencyId);
        if (provider.isPresent() && provider.get().getDisplayItem() != null) {
            return provider.get().getDisplayItem().clone();
        }

        // Fallback: build from currency definition
        if (def != null && def.customMaterial() != null && !def.customMaterial().isEmpty()) {
            Material mat = Material.matchMaterial(def.customMaterial());
            if (mat != null) {
                ItemStack item = new ItemStack(mat);
                var meta = item.getItemMeta();
                if (def.customModelData() > 0) meta.setCustomModelData(def.customModelData());
                String name = def.customName() != null && !def.customName().isEmpty()
                    ? def.customName() : def.displayName();
                meta.displayName(GuiUtils.toComponent(
                    com.ohtoai.opshop.config.Messages.colorize("&f" + name)));
                if (def.customLore() != null && !def.customLore().isEmpty()) {
                    meta.lore(def.customLore().stream()
                        .map(l -> GuiUtils.toComponent(com.ohtoai.opshop.config.Messages.colorize(l)))
                        .toList());
                }
                item.setItemMeta(meta);
                return item;
            }
        }
        return GuiUtils.makeItem(Material.NETHER_STAR,
            "&f" + currencyId, List.of(m("editor.custom-currency-fallback", "&7Custom currency")));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        // Set Both — delegate to ShopEditorGui's anvil
        if (slot == SLOT_SET_BOTH) {
            player.closeInventory();
            var editor = ShopEditorGui.getActiveInstance();
            if (editor != null) {
                editor.triggerPriceAnvil(player, currencyId);
            }
            return;
        }

        // Back
        if (slot == SLOT_BACK) {
            player.closeInventory();
            parent.open(player);
            return;
        }

        // Buy price adjustment
        if (slot == SLOT_BUY) {
            var prices = shopItem.getCustomCurrencyPrices().get(currencyId);
            double current = prices != null ? prices.buyPrice() : 0;
            double sellPrice = prices != null ? prices.sellPrice() : 0;
            double newPrice = adjustPrice(current, event);
            shopItem.setCustomCurrencyPrice(currencyId, newPrice, sellPrice);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            build(player);
            player.openInventory(inventory);
            return;
        }

        // Sell price adjustment
        if (slot == SLOT_SELL) {
            var prices = shopItem.getCustomCurrencyPrices().get(currencyId);
            double buyPrice = prices != null ? prices.buyPrice() : 0;
            double current = prices != null ? prices.sellPrice() : 0;
            double newPrice = adjustPrice(current, event);
            shopItem.setCustomCurrencyPrice(currencyId, buyPrice, newPrice);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            build(player);
            player.openInventory(inventory);
            return;
        }
    }

    private double adjustPrice(double current, InventoryClickEvent event) {
        boolean left = event.isLeftClick();
        boolean shift = event.isShiftClick();
        boolean drop = event.getClick().name().contains("DROP");
        boolean ctrl = event.getClick().name().contains("CONTROL");
        if (drop && ctrl) return Math.max(0, current - 100);
        if (drop) return current + 100;
        if (shift && left) return current + 10;
        if (shift && !left) return Math.max(0, current - 10);
        if (left) return current + 1;
        return Math.max(0, current - 1);
    }

    @Override
    public void refresh() {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price) && !Double.isInfinite(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }
}
