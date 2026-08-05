package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated GUI listing all registered custom currencies for a shop item.
 * <p>
 * Each currency row shows its display icon, enabled/disabled state, and current
 * buy/sell prices. Left-click opens {@link CustomCurrencyPriceGui} for detailed
 * price editing. Right-click toggles the currency on/off for the current item.
 * Changes are applied to the in-memory {@link ShopItem}; final save is handled
 * by {@link ShopEditorGui}.
 */
public class CustomCurrencyListGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;

    // Navigation slots (row 5)
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BACK = 48;

    private final OpShopPlugin plugin;
    private final CurrencyManager currencyManager;
    private final ShopItem shopItem;
    private final ShopEditorGui editorParent;

    private Inventory inventory;
    private int currentPage = 0;

    public CustomCurrencyListGui(OpShopPlugin plugin, CurrencyManager currencyManager,
                                 ShopItem shopItem, ShopEditorGui editorParent) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.shopItem = shopItem;
        this.editorParent = editorParent;
    }

    private String m(String key, String en, String... reps) {
        return plugin.getMessages().get(key, en, reps);
    }

    public void open(Player player) {
        build(player);
        player.openInventory(inventory);
    }

    private void build(Player player) {
        String title = m("editor.custom-currency-list",
            "&8Custom Currencies: {id}", "{id}", shopItem.getId());
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Get all CUSTOM_ITEM type currencies
        List<CurrencyDef> customCurrencies = currencyManager.getDefinitions().values().stream()
            .filter(d -> "CUSTOM_ITEM".equalsIgnoreCase(d.type()))
            .toList();

        int totalPages = customCurrencies.isEmpty() ? 1
            : (int) Math.ceil((double) customCurrencies.size() / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        // Populate currency slots
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, customCurrencies.size());
        for (int i = startIdx; i < endIdx; i++) {
            CurrencyDef def = customCurrencies.get(i);
            int slot = i - startIdx;
            inventory.setItem(slot, buildCurrencyIcon(def));
        }

        // -- Navigation bar --
        // Previous Page
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV, GuiUtils.makeItem(Material.ARROW,
                m("editor.prev-page", "&a← Previous Page"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 1), "{t}", String.valueOf(totalPages)))));
        }

        // Page info
        inventory.setItem(SLOT_INFO, GuiUtils.makeItem(Material.PAPER,
            m("editor.page", "&ePage {page}/{total}",
                "{page}", String.valueOf(currentPage + 1), "{total}", String.valueOf(totalPages)),
            List.of(
                m("editor.total-items", "&7{count} total currency(s)",
                    "{count}", String.valueOf(customCurrencies.size())),
                m("editor.custom-currency-usage-count",
                    "&7{count} enabled for this item",
                    "{count}", String.valueOf(shopItem.getCustomCurrencyPrices().size())))));

        // Next Page
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtils.makeItem(Material.ARROW,
                m("editor.next-page", "&aNext Page →"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 2), "{t}", String.valueOf(totalPages)))));
        }

        // Back to editor
        inventory.setItem(SLOT_BACK, GuiUtils.makeItem(Material.OAK_SIGN,
            m("editor.back-to-item", "&6← Back to Item"),
            List.of(m("editor.back-to-item-desc", "&7Return to item editor"))));

        player.openInventory(inventory);
    }

    /** Build the toggle/action icon for a single custom currency. */
    private ItemStack buildCurrencyIcon(CurrencyDef def) {
        String id = def.id();
        boolean enabled = shopItem.hasCustomCurrency(id);
        var prices = shopItem.getCustomCurrencyPrices().get(id);
        double buyPrice = prices != null ? prices.buyPrice() : 0;
        double sellPrice = prices != null ? prices.sellPrice() : 0;

        // Start from the provider's full display item (preserves enchantments, flags, glow, etc.)
        ItemStack item;
        var provider = currencyManager.getProvider(id);
        if (provider.isPresent() && provider.get().getDisplayItem() != null) {
            item = GuiUtils.prepareCurrencyIcon(provider.get().getDisplayItem());
        } else {
            // Fallback: build from currency definition
            Material mat = Material.NETHER_STAR;
            if (def.customMaterial() != null && !def.customMaterial().isEmpty()) {
                Material m = Material.matchMaterial(def.customMaterial());
                if (m != null) mat = m;
            }
            item = new ItemStack(mat);
            var meta = item.getItemMeta();
            if (def.customName() != null && !def.customName().isEmpty()) {
                meta.displayName(GuiUtils.toComponent(
                    com.ohtoai.opshop.config.Messages.colorize(def.customName())));
            }
            if (def.customModelData() > 0) meta.setCustomModelData(def.customModelData());
            if (def.customLore() != null && !def.customLore().isEmpty()) {
                meta.lore(def.customLore().stream()
                    .map(l -> GuiUtils.toComponent(com.ohtoai.opshop.config.Messages.colorize(l)))
                    .toList());
            }
            item.setItemMeta(meta);
        }

        // Build lore for the GUI — use locale-aware display name
        String displayName = currencyManager.getDisplayName(id);

        String stateLabel = enabled
            ? m("editor.custom-currency-enabled", "&a✅ Enabled")
            : m("editor.custom-currency-disabled", "&7❌ Disabled");

        List<String> lore = new ArrayList<>();
        lore.add(stateLabel);
        if (enabled) {
            lore.add(m("editor.custom-buy-price-lore", "&7Buy: &f{value}",
                "{value}", formatPrice(buyPrice)));
            lore.add(m("editor.custom-sell-price-lore", "&7Sell: &f{value}",
                "{value}", formatPrice(sellPrice)));
        }
        lore.add("");
        lore.add(m("editor.custom-currency-toggle-hint",
            "&eLeft-click: edit price &7| &eRight-click: toggle"));

        // Append GUI lore to existing lore (don't replace the currency's own lore)
        var meta = item.getItemMeta();
        List<net.kyori.adventure.text.Component> existingLore = meta.hasLore()
            ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        existingLore.add(net.kyori.adventure.text.Component.empty());
        for (String line : lore) {
            existingLore.add(GuiUtils.toComponent(
                com.ohtoai.opshop.config.Messages.colorize(line)));
        }
        meta.lore(existingLore);

        // Update display name with status prefix
        String prefix = enabled ? "&f" : "&8";
        meta.displayName(GuiUtils.toComponent(
            com.ohtoai.opshop.config.Messages.colorize(prefix + displayName)));

        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        // Get currencies for pagination
        List<CurrencyDef> customCurrencies = currencyManager.getDefinitions().values().stream()
            .filter(d -> "CUSTOM_ITEM".equalsIgnoreCase(d.type()))
            .toList();
        int totalPages = customCurrencies.isEmpty() ? 1
            : (int) Math.ceil((double) customCurrencies.size() / ITEMS_PER_PAGE);

        // Previous page
        if (slot == SLOT_PREV && currentPage > 0) {
            currentPage--;
            build(player);
            return;
        }

        // Next page
        if (slot == SLOT_NEXT && currentPage < totalPages - 1) {
            currentPage++;
            build(player);
            return;
        }

        // Back to editor
        if (slot == SLOT_BACK) {
            player.closeInventory();
            editorParent.refresh();
            var inv = editorParent.getInventory();
            if (inv != null) {
                player.openInventory(inv);
            }
            return;
        }

        // Page info or navigation filler — no action
        if (slot == SLOT_INFO || slot >= ITEMS_PER_PAGE) return;

        // Click on a currency
        int currencyIdx = currentPage * ITEMS_PER_PAGE + slot;
        if (currencyIdx >= 0 && currencyIdx < customCurrencies.size()) {
            CurrencyDef def = customCurrencies.get(currencyIdx);
            String currencyId = def.id();

            // Right-click: toggle enabled/disabled
            if (event.isRightClick()) {
                if (shopItem.hasCustomCurrency(currencyId)) {
                    shopItem.removeCustomCurrencyPrice(currencyId);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                    String curDisplay = currencyManager.getDisplayName(currencyId);
                    player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + m("editor.custom-currency-removed",
                            "&cRemoved {currency}&r&c from this item.",
                            "{currency}", curDisplay)));
                } else {
                    // Enable with default prices (from global defaults if available)
                    double defaultBuy = 1.0;
                    double defaultSell = 1.0;
                    for (var entry : currencyManager.getGlobalDefaults()) {
                        if (currencyId.equals(entry.get("currency"))) {
                            defaultBuy = ((Number) entry.getOrDefault("buy-price", 1.0)).doubleValue();
                            defaultSell = ((Number) entry.getOrDefault("sell-price", 1.0)).doubleValue();
                            break;
                        }
                    }
                    shopItem.setCustomCurrencyPrice(currencyId, defaultBuy, defaultSell);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
                    String curDisplay = currencyManager.getDisplayName(currencyId);
                    player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + m("editor.custom-currency-added",
                            "&aAdded {currency}&r&a to this item.",
                            "{currency}", curDisplay)));
                }
                build(player);
                return;
            }

            // Left-click: open price editor (only if enabled)
            if (event.isLeftClick()) {
                if (!shopItem.hasCustomCurrency(currencyId)) {
                    // Auto-enable on left-click
                    double defaultBuy = 1.0;
                    double defaultSell = 1.0;
                    for (var entry : currencyManager.getGlobalDefaults()) {
                        if (currencyId.equals(entry.get("currency"))) {
                            defaultBuy = ((Number) entry.getOrDefault("buy-price", 1.0)).doubleValue();
                            defaultSell = ((Number) entry.getOrDefault("sell-price", 1.0)).doubleValue();
                            break;
                        }
                    }
                    shopItem.setCustomCurrencyPrice(currencyId, defaultBuy, defaultSell);
                }
                player.closeInventory();
                new CustomCurrencyPriceGui(plugin, currencyManager, shopItem, currencyId, this)
                    .open(player);
                return;
            }
        }
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
