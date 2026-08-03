package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.ShopItem;
import com.ohtoai.opshop.shop.ShopManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Admin GUI for managing shop items.
 * <p>
 * Two modes of operation:
 * <ul>
 *   <li><b>List view</b> — paginated grid of all shop items with navigation,
 *       add-from-hand, reload, and close.</li>
 *   <li><b>Edit view</b> — full editor for a single item: display/product items,
 *       buy/sell toggles, global-defaults toggle, placeholders toggle, category
 *       cycling, per-currency prices (with Add Currency), slot positioning,
 *       save, revert-to-global, delete-with-confirmation, and back-to-list.</li>
 * </ul>
 */
public class ShopEditorGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int[] ITEM_SLOTS = new int[ITEMS_PER_PAGE];

    static {
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            ITEM_SLOTS[i] = (i / 9) * 9 + (i % 9);
        }
    }

    private final OpShopPlugin plugin;
    private final ShopManager shopManager;
    private final CurrencyManager currencyManager;

    /** Shortcut: lookup translated message with English fallback + optional replacements. */
    private String m(String key, String en, String... reps) { return plugin.getMessages().get(key, en, reps); }

    private Inventory inventory;
    private int page = 0;
    private Player currentViewer; // the player viewing the editor

    // Edit-mode state
    private boolean editMode = false;
    private String editingItemId;
    private ShopItem editingItem;
    private boolean deleteConfirmPending = false;

    // Guild editor mode (null = normal shop editor)
    private Integer guildEditorId;
    private String guildEditorName;

    // Pagination for custom currency row (0-indexed page of 5 currencies)
    private int customCurrencyPage = 0;

    // Anvil input state — waiting for player to confirm ID (add-item mode)
    private boolean anvilOpen = false;
    private ItemStack pendingCursorItem;
    private String pendingDefaultId;

    // Anvil price-input state — waiting for player to type price(s)
    private boolean anvilPriceMode = false;
    private int anvilPriceSlot = -1;          // slot that triggered
    private String anvilPriceCurrencyId;       // non-null = custom currency; null = vault/points
    private boolean anvilPriceBoth;            // true = "buy sell" format; false = single price
    private boolean anvilPriceIsSell;          // for single-price mode: true = editing sell, false = editing buy

    // -------------------------------------------------------------------------
    // Construction & navigation
    // -------------------------------------------------------------------------

    private static ShopEditorGui activeInstance;

    public ShopEditorGui(OpShopPlugin plugin, ShopManager shopManager, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.currencyManager = currencyManager;
        // Clear previous instance to allow GC before overwriting
        if (activeInstance != null && activeInstance != this) {
            activeInstance.clearState();
        }
        activeInstance = this;
    }

    /** Release references to allow garbage collection. */
    private void clearState() {
        this.currentViewer = null;
        this.editingItem = null;
        this.inventory = null;
    }

    public static ShopEditorGui getActiveInstance() { return activeInstance; }

    /** Open the main paginated item list. */
    public void open(Player player) {
        open(player, 0);
    }

    /** Open the editor at a specific page. */
    public void open(Player player, int page) {
        this.currentViewer = player;
        this.editMode = false;
        this.editingItem = null;
        this.editingItemId = null;
        this.deleteConfirmPending = false;
        this.guildEditorId = null;
        this.guildEditorName = null;
        this.page = page;
        build();
        player.openInventory(inventory);
    }

    /** Open the editor scoped to a guild. Only shows items for that guild. */
    public void openForGuild(Player player, int guildId, String guildName, String guildTag) {
        this.currentViewer = player;
        this.editMode = false;
        this.editingItem = null;
        this.editingItemId = null;
        this.deleteConfirmPending = false;
        this.guildEditorId = guildId;
        this.guildEditorName = guildName;
        com.ohtoai.opshop.item.PlaceholderReplacer.setGuildContext(
            player.getUniqueId(), guildName, guildTag);
        this.page = 0;
        build();
        player.openInventory(inventory);
    }

    /** Open the edit view for a specific item. */
    public void openEdit(Player player, ShopItem item) {
        this.currentViewer = player;
        this.editMode = true;
        this.editingItem = item;
        this.editingItemId = item.getId();
        this.deleteConfirmPending = false;
        build();
        player.openInventory(inventory);
    }

    // -------------------------------------------------------------------------
    // Build dispatcher
    // -------------------------------------------------------------------------

    private void build() {
        build(null);
    }

    private void build(Player reopenFor) {
        String title;
        if (editMode) {
            title = m("editor.edit-title", "&8Editor: {id}", "{id}", editingItemId);
        } else if (guildEditorId != null) {
            title = m("editor.guild-editor-title", "&8Guild Editor: {name}",
                "{name}", guildEditorName != null ? guildEditorName : "#" + guildEditorId);
        } else {
            title = m("editor.title", "&8OpShop Editor");
        }
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;

        if (editMode && editingItem != null) {
            buildEditView(filler);
        } else {
            buildListView(filler);
        }

        if (reopenFor != null) {
            reopenFor.openInventory(inventory);
        }
    }

    // ============================== LIST VIEW ================================

    /** Get items filtered by the current editor mode (normal vs guild). */
    private List<ShopItem> getFilteredItems() {
        if (guildEditorId != null) {
            return shopManager.getAllItems().stream()
                .filter(i -> i.isGuildRestricted() && i.getAllowedGuildIds().contains(guildEditorId))
                .toList();
        } else {
            return shopManager.getAllItems().stream()
                .filter(i -> !i.isGuildRestricted())
                .toList();
        }
    }

    private void buildListView(Material filler) {
        GuiUtils.fill(inventory, filler);

        List<ShopItem> allItems = getFilteredItems();
        int totalItems = allItems.size();
        int totalPages = totalItems == 0 ? 1
            : allItems.stream().mapToInt(ShopItem::getPage).max().orElse(0) + 1;
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        List<ShopItem> itemsOnPage = allItems.stream()
            .filter(i -> i.getPage() == page)
            .sorted(java.util.Comparator.comparingInt(ShopItem::getSlot))
            .toList();

        for (ShopItem item : itemsOnPage) {
            int slot = item.getSlot();
            if (slot >= 0 && slot < ITEMS_PER_PAGE) {
                inventory.setItem(slot, buildItemListIcon(item));
            }
        }

        // Row 5 — navigation and actions
        // Slot 45: Previous Page
        if (page > 0) {
            inventory.setItem(45, GuiUtils.makeItem(Material.ARROW,
                m("editor.prev-page", "&a← Previous Page"),
                List.of(m("editor.page-num", "&7Page {p}/{t}", "{p}", String.valueOf(page + 1), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(49, GuiUtils.makeItem(Material.PAPER,
            m("editor.page", "&ePage {page}/{total}", "{page}", String.valueOf(page + 1), "{total}", String.valueOf(totalPages)),
            List.of(m("editor.total-items", "&7{count} total item(s)", "{count}", String.valueOf(totalItems)))));
        inventory.setItem(50, GuiUtils.makeItem(Material.CLOCK,
            m("editor.reload", "&eReload from Database"),
            List.of(m("editor.reload-desc", "&7Reload all items from the database."))));
        if (page < totalPages - 1) {
            inventory.setItem(51, GuiUtils.makeItem(Material.ARROW,
                m("editor.next-page", "&aNext Page →"),
                List.of(m("editor.page-num", "&7Page {p}/{t}", "{p}", String.valueOf(page + 1), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(46, GuiUtils.makeItem(Material.LIME_WOOL,
            m("editor.insert-before", "&a+ Insert Page Before"),
            List.of(m("editor.insert-before-desc", "&7Insert an empty page before this one."),
                m("editor.insert-before-desc2", "&7All pages shift right."))));
        inventory.setItem(47, GuiUtils.makeItem(Material.LIME_WOOL,
            m("editor.insert-after", "&a+ Insert Page After"),
            List.of(m("editor.insert-after-desc", "&7Insert an empty page after this one."))));
        boolean pageEmpty = shopManager.getItemsOnPage(page).isEmpty();
        inventory.setItem(52, GuiUtils.makeItem(Material.RED_WOOL,
            pageEmpty ? m("editor.delete-page-empty", "&cDelete Page (empty)") : m("editor.delete-page", "&cDelete Page"),
            List.of(pageEmpty
                ? m("editor.delete-page-desc-empty", "&7Delete this empty page.")
                : m("editor.delete-page-desc", "&7Move all items to later pages."),
                m("editor.shift-confirm", "&7Shift+Click to confirm."))));
        if (guildEditorId != null) {
            inventory.setItem(53, GuiUtils.makeItem(Material.ARROW,
                m("editor.back-to-guild-list", "&6← Back to Guild List"),
                List.of(m("editor.back-to-guild-list-desc", "&7Return to guild selection."))));
        } else {
            inventory.setItem(53, GuiUtils.makeItem(Material.BARRIER,
                m("editor.close-editor", "&cClose"),
                List.of(m("editor.close-desc", "&7Close the editor."))));
        }
    }

    /** Delete the current page — move items forward, then shift later pages down. */
    private void deleteCurrentPage(Player player) {
        int deletedPage = page;
        List<ShopItem> pageItems = new ArrayList<>(shopManager.getItemsOnPage(deletedPage));
        // First: shift all pages > deletedPage down by 1 (creates space)
        // Then: move items from deletedPage into the gap at deletedPage (now the old deletedPage+1 content)
        // Actually simpler: move items to later pages FIRST at their final destination pages,
        // THEN shift all later pages down. Since shiftPagesFrom moves items with page >= fromPage,
        // the items we already placed at page+1, page+2 etc will ALSO be shifted down.
        // Fix: use a two-pass approach.
        // Pass 1: move all items temporarily to very high page numbers
        int tempPage = Math.max(shopManager.getMaxPage() + 10, 100);
        for (ShopItem item : pageItems) {
            item.setPage(tempPage);
            item.setSlot(0);
            try { shopManager.saveItem(item); } catch (java.sql.SQLException e) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + m("editor.db-error", "&cDB error moving item {id}", "{id}", item.getId())));
            }
        }
        // Pass 2: shift all pages after deleted down
        shiftPagesFrom(deletedPage + 1, -1);
        // Pass 3: bring temp items back to first available slots
        for (ShopItem item : pageItems) {
            int targetPage = deletedPage;
            while (true) {
                int slot = shopManager.findFirstEmptySlot(targetPage);
                if (slot >= 0) { item.setPage(targetPage); item.setSlot(slot); break; }
                targetPage++;
            }
            try { shopManager.saveItem(item); } catch (java.sql.SQLException e) {}
        }
        if (page > 0 && shopManager.getItemsOnPage(page).isEmpty()) page--;
        build();
        player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
            + m("editor.delete-page-success", "&aDeleted page &e{page}&a. Items moved forward.", "{page}", String.valueOf(deletedPage + 1))));
    }

    /** Shift all items on pages >= fromPage by delta pages. */
    private void shiftPagesFrom(int fromPage, int delta) {
        for (ShopItem item : shopManager.getAllItems()) {
            if (item.getPage() >= fromPage) {
                item.setPage(item.getPage() + delta);
                try { shopManager.saveItem(item); } catch (java.sql.SQLException e) {}
            }
        }
    }

    /** Build the display icon with lore for a single item in the list view. */
    private ItemStack buildItemListIcon(ShopItem item) {
        ItemStack icon = ItemSerializer.deserialize(item.getDisplayItem());
        if (icon == null || icon.getType() == Material.AIR) {
            icon = new ItemStack(Material.STONE);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(GuiUtils.toComponent(Messages.colorize("&e" + item.getId())));
            icon.setItemMeta(meta);
        }
        if (item.isApplyPlaceholders()) {
            icon = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(icon, currentViewer);
        }

        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>();

        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.item-id-label", "&7ID: &f{id}", "{id}", item.getId()))));
        String buyState = item.isBuyEnabled() ? m("editor.buy-enabled", "&aEnabled") : m("editor.buy-disabled", "&cDisabled");
        String sellState = item.isSellEnabled() ? m("editor.sell-enabled", "&aEnabled") : m("editor.sell-disabled", "&cDisabled");
        lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.buy-label", "&7Buy: {state}", "{state}", buyState))));
        lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.sell-label", "&7Sell: {state}", "{state}", sellState))));
        if (item.getVaultBuyPrice() > 0 || item.getVaultSellPrice() > 0)
            lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.vault-prices", "&7Vault Buy:{buy} Sell:{sell}",
                "{buy}", formatPrice(item.getVaultBuyPrice()), "{sell}", formatPrice(item.getVaultSellPrice())))));
        if (item.getPpBuyPrice() > 0 || item.getPpSellPrice() > 0)
            lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.points-prices", "&7Points Buy:{buy} Sell:{sell}",
                "{buy}", formatPrice(item.getPpBuyPrice()), "{sell}", formatPrice(item.getPpSellPrice())))));
        // Show all custom currency prices
        for (var entry : item.getCustomCurrencyPrices().entrySet()) {
            lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.custom-prices", "&7{currency} Buy:{buy} Sell:{sell}",
                "{currency}", entry.getKey(),
                "{buy}", formatPrice(entry.getValue().buyPrice()),
                "{sell}", formatPrice(entry.getValue().sellPrice())))));
        }
        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.click-to-edit", "&eClick to edit"))));

        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ============================== EDIT VIEW ================================

    private void buildEditView(Material filler) {
        GuiUtils.fill(inventory, filler);

        // ---- Item preview (slot 13) ----
        ItemStack display = ItemSerializer.deserialize(editingItem.getDisplayItem());
        if (display != null && display.getType() != Material.AIR) {
            if (editingItem.isApplyPlaceholders()) {
                display = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(display, currentViewer);
            }
            List<Component> previewLore = new ArrayList<>();
            previewLore.add(GuiUtils.toComponent(Messages.colorize(m("editor.drop-item-replace", "&7Drop an item here to replace"))));
            if (editingItem.isApplyPlaceholders()) {
                previewLore.add(Component.empty());
                previewLore.add(GuiUtils.toComponent(Messages.colorize(m("editor.placeholder-preview", "&e▸ Placeholder preview (as {player}):",
                "{player}", currentViewer != null ? currentViewer.getName() : "?"))));
                for (String ph : com.ohtoai.opshop.item.PlaceholderReplacer.getAvailablePlaceholders()) {
                    String resolved = com.ohtoai.opshop.item.PlaceholderReplacer.resolveSingle(ph, currentViewer);
                    previewLore.add(GuiUtils.toComponent(Messages.colorize("&7  " + resolved)));
                }
            }
            display.lore(previewLore);
            inventory.setItem(13, display);
        } else {
            inventory.setItem(13, GuiUtils.makeItem(Material.BARRIER,
                m("editor.drop-item-title", "&cDrop Item Here"),
                List.of(m("editor.drop-item-lore-1", "&7Drop an item from your inventory"),
                        m("editor.drop-item-lore-2", "&7onto this slot to set it."))));
        }

        // ---- Row 1: Vault (slot 10) | ITEM (13) | Points (slot 16) ----
        buildCurrencyIcon(10, "vault", Material.GOLD_INGOT,
            editingItem.getVaultBuyPrice(), editingItem.getVaultSellPrice());
        buildCurrencyIcon(16, "points", Material.DIAMOND,
            editingItem.getPpBuyPrice(), editingItem.getPpSellPrice());

        // ---- Row 2 (slots 19-25): ALL custom currencies with ← → pagination ----
        // Show all defined CUSTOM_ITEM currencies, not just item's enabled ones
        List<com.ohtoai.opshop.config.PluginConfig.CurrencyDef> allCustomDefs =
            currencyManager.getDefinitions().values().stream()
                .filter(d -> "CUSTOM_ITEM".equalsIgnoreCase(d.type()))
                .toList();

        int CUSTOM_PER_PAGE = 5;
        int[] CUSTOM_SLOTS = {20, 21, 22, 23, 24};
        int totalCustomPages = allCustomDefs.isEmpty() ? 1
            : (int) Math.ceil((double) allCustomDefs.size() / CUSTOM_PER_PAGE);
        if (customCurrencyPage >= totalCustomPages) customCurrencyPage = totalCustomPages - 1;
        if (customCurrencyPage < 0) customCurrencyPage = 0;

        // Left arrow (19)
        if (customCurrencyPage > 0) {
            inventory.setItem(19, GuiUtils.makeItem(Material.ARROW,
                m("editor.custom-prev", "&a← More"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(customCurrencyPage + 1), "{t}", String.valueOf(totalCustomPages)))));
        }
        // Right arrow (25)
        if (customCurrencyPage < totalCustomPages - 1) {
            inventory.setItem(25, GuiUtils.makeItem(Material.ARROW,
                m("editor.custom-next", "&a→ More"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(customCurrencyPage + 2), "{t}", String.valueOf(totalCustomPages)))));
        }

        // Show all custom currency defs (enabled ones with prices, disabled ones grayed out)
        int startIdx = customCurrencyPage * CUSTOM_PER_PAGE;
        for (int i = 0; i < CUSTOM_PER_PAGE; i++) {
            int idx = startIdx + i;
            if (idx >= allCustomDefs.size()) break;
            var def = allCustomDefs.get(idx);
            String cid = def.id();
            boolean enabled = editingItem.hasCustomCurrency(cid);
            var cp = editingItem.getCustomCurrencyPrices().get(cid);
            double cb = cp != null ? cp.buyPrice() : 0;
            double cs = cp != null ? cp.sellPrice() : 0;
            buildCustomDefIcon(CUSTOM_SLOTS[i], def, enabled, cb, cs);
        }

        // ---- Row 3 (27-35): empty ----

        // ---- Row 4 (36-44): Toggles + Get Item + Log ----
        String on = m("editor.state-on", "&aON"), off = m("editor.state-off", "&cOFF");
        String toggleHint = m("editor.toggle-hint", "&7Click to toggle");

        // 36: Buy
        String buyLabel = m("editor.buy-toggle", "&eBuy: {state}").replace("{state}", editingItem.isBuyEnabled() ? on : off);
        inventory.setItem(36, GuiUtils.makeItem(
            editingItem.isBuyEnabled() ? Material.LIME_DYE : Material.GRAY_DYE, buyLabel, List.of(toggleHint)));

        // 37: Sell
        if (editingItem.isApplyPlaceholders()) {
            inventory.setItem(37, GuiUtils.makeItem(Material.BARRIER,
                m("editor.sell-locked", "&cSell: &4🔒 LOCKED"),
                List.of(m("editor.sell-locked-desc", "&cDisabled while Placeholders is ON."))));
        } else if (editingItem.isGenerateUniqueId()) {
            inventory.setItem(37, GuiUtils.makeItem(Material.BARRIER,
                m("editor.generate-id-locked", "&cSell: &4🔒 Trace ID LOCKED"),
                List.of(m("editor.generate-id-locked-desc", "&cDisabled while Trace ID is ON."))));
        } else {
            String sellLabel = m("editor.sell-toggle", "&eSell: {state}").replace("{state}", editingItem.isSellEnabled() ? on : off);
            inventory.setItem(37, GuiUtils.makeItem(
                editingItem.isSellEnabled() ? Material.LIME_DYE : Material.GRAY_DYE, sellLabel, List.of(toggleHint)));
        }

        // 38: Placeholders
        String phLabel = m("editor.placeholders-toggle", "&ePlaceholders: {state}").replace("{state}", editingItem.isApplyPlaceholders() ? on : off);
        inventory.setItem(38, GuiUtils.makeItem(
            editingItem.isApplyPlaceholders() ? Material.LIME_DYE : Material.GRAY_DYE, phLabel, List.of(toggleHint)));

        // 39: Refund Limit
        if (editingItem.isApplyPlaceholders()) {
            inventory.setItem(39, GuiUtils.makeItem(Material.BARRIER,
                m("editor.refund-locked", "&cRefund Limit: &4🔒 LOCKED"),
                List.of(m("editor.refund-locked-desc", "&cDisabled while Placeholders is ON."))));
        } else if (editingItem.isGenerateUniqueId()) {
            inventory.setItem(39, GuiUtils.makeItem(Material.BARRIER,
                m("editor.refund-locked", "&cRefund Limit: &4🔒 LOCKED"),
                List.of(m("editor.refund-locked-desc", "&cDisabled while Trace ID is ON."))));
        } else {
            String refLabel = m("editor.refund-limit-toggle", "&eRefund Limit: {state}").replace("{state}", editingItem.isRefundLimit() ? on : off);
            inventory.setItem(39, GuiUtils.makeItem(
                editingItem.isRefundLimit() ? Material.LIME_DYE : Material.GRAY_DYE, refLabel,
                List.of(m("editor.refund-desc-1", "&7When ON: selling back restores purchase quota"), toggleHint)));
        }

        // 40: Trace ID
        String genIdLabel = m("editor.generate-id-toggle", "&eTrace ID: {state}")
            .replace("{state}", editingItem.isGenerateUniqueId() ? on : off);
        inventory.setItem(40, GuiUtils.makeItem(
            editingItem.isGenerateUniqueId() ? Material.LIME_DYE : Material.GRAY_DYE, genIdLabel,
            List.of(m("editor.generate-id-desc-1", "&7Each purchase gets a unique traceable ID."), toggleHint)));

        // 42: Get Item
        inventory.setItem(42, GuiUtils.makeItem(Material.CHEST,
            m("editor.get-item", "&6Get Item &7(Raw)"),
            List.of(m("editor.get-item-desc-1", "&7Click to get a copy (raw)."),
                m("editor.get-item-desc-3", "&7Shift+Click for placeholder-applied copy."))));
        // 43: Transaction Log
        inventory.setItem(43, GuiUtils.makeItem(Material.BOOK,
            m("editor.transaction-log", "&eTransaction Log"),
            List.of(m("editor.transaction-log-desc-1", "&7View purchase/sell history for this item."))));

        // ---- Row 5: Limits + actions ----
        String limitHints = m("editor.limit-controls", "&7L+1 R-1 ShiftL+10 ShiftR-10 Q=∞");
        inventory.setItem(47, GuiUtils.makeItem(
            editingItem.getTotalLimit() >= 0 ? Material.REDSTONE : Material.GUNPOWDER,
            m("editor.total-limit", "&eTotal Limit: {value}",
                "{value}", editingItem.getTotalLimit() >= 0 ? "&c" + editingItem.getTotalLimit() : m("editor.total-limit-unlimited", "&aUnlimited")),
            List.of(limitHints)));
        inventory.setItem(51, GuiUtils.makeItem(
            editingItem.getPlayerLimit() >= 0 ? Material.REDSTONE : Material.GUNPOWDER,
            m("editor.player-limit", "&ePlayer Limit: {value}",
                "{value}", editingItem.getPlayerLimit() >= 0 ? "&c" + editingItem.getPlayerLimit() : m("editor.player-limit-unlimited", "&aUnlimited")),
            List.of(limitHints)));

        inventory.setItem(48, GuiUtils.makeItem(Material.LIME_WOOL,
            m("editor.save", "&a✔ Save"), List.of(m("editor.save-desc", "&7Save to database"))));
        inventory.setItem(50, GuiUtils.makeItem(Material.RED_WOOL,
            deleteConfirmPending ? m("editor.delete-confirm", "&c&lCONFIRM DELETE") : m("editor.delete", "&c✖ Delete"),
            List.of(deleteConfirmPending ? m("editor.delete-confirm-desc", "&7Click again to confirm") : m("editor.delete-desc", "&7Click to delete"))));
        inventory.setItem(53, GuiUtils.makeItem(Material.ARROW, m("editor.back-to-list", "&6← Back to List"), List.of()));
    }

    // ---- Currency icon builder (single icon with price lore) ----

    /** Build a currency icon showing buy/sell prices. key=="vault"|"points" for fixed currencies, or customCurrencyId. */
    private void buildCurrencyIcon(int slot, String key, Material fallbackMat, double buy, double sell) {
        ItemStack icon;
        String displayName;

        if ("vault".equals(key)) {
            icon = new ItemStack(Material.GOLD_INGOT);
            displayName = m("editor.vault-currency", "&eVault Money");
        } else if ("points".equals(key)) {
            icon = new ItemStack(Material.DIAMOND);
            displayName = m("editor.points-currency", "&ePlayer Points");
        } else {
            // Custom currency: use provider's display item
            displayName = key;
            var provider = currencyManager.getProvider(key);
            if (provider.isPresent() && provider.get().getDisplayItem() != null) {
                icon = provider.get().getDisplayItem().clone();
            } else {
                var def = currencyManager.getDefinition(key);
                Material mat = fallbackMat != null ? fallbackMat : Material.NETHER_STAR;
                if (def != null && def.customMaterial() != null && !def.customMaterial().isEmpty()) {
                    Material m = Material.matchMaterial(def.customMaterial());
                    if (m != null) mat = m;
                }
                icon = new ItemStack(mat);
                if (def != null && def.customModelData() > 0) {
                    var meta = icon.getItemMeta();
                    meta.setCustomModelData(def.customModelData());
                    icon.setItemMeta(meta);
                }
                if (def != null) displayName = def.displayName();
            }
        }

        var meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(
            m("editor.currency-buy", "&aBuy: &f{price}", "{price}", formatPrice(buy)))));
        lore.add(GuiUtils.toComponent(Messages.colorize(
            m("editor.currency-sell", "&cSell: &f{price}", "{price}", formatPrice(sell)))));
        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(
            m("editor.currency-click-buy", "&7Left-click: edit &aBuy &7price"))));
        lore.add(GuiUtils.toComponent(Messages.colorize(
            m("editor.currency-click-sell", "&7Right-click: edit &cSell &7price"))));
        lore.add(GuiUtils.toComponent(Messages.colorize(
            m("editor.currency-click-both", "&7Drop(Q): edit &eBoth &7prices"))));
        meta.lore(lore);
        meta.displayName(GuiUtils.toComponent(Messages.colorize("&f" + displayName)));
        icon.setItemMeta(meta);
        inventory.setItem(slot, icon);
    }

    /** Build an icon for a custom currency definition — enabled=full color, disabled=grayed out. */
    private void buildCustomDefIcon(int slot, com.ohtoai.opshop.config.PluginConfig.CurrencyDef def,
                                    boolean enabled, double buy, double sell) {
        String cid = def.id();
        ItemStack icon;
        String displayName = def.displayName();

        // Try provider's display item first
        var provider = currencyManager.getProvider(cid);
        if (provider.isPresent() && provider.get().getDisplayItem() != null) {
            icon = provider.get().getDisplayItem().clone();
        } else {
            Material mat = Material.NETHER_STAR;
            if (def.customMaterial() != null && !def.customMaterial().isEmpty()) {
                Material m = Material.matchMaterial(def.customMaterial());
                if (m != null) mat = m;
            }
            icon = new ItemStack(mat);
            if (def.customModelData() > 0) {
                var meta = icon.getItemMeta();
                meta.setCustomModelData(def.customModelData());
                icon.setItemMeta(meta);
            }
        }

        var meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());

        if (enabled) {
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-buy", "&aBuy: &f{price}", "{price}", formatPrice(buy)))));
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-sell", "&cSell: &f{price}", "{price}", formatPrice(sell)))));
            lore.add(Component.empty());
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-click-buy", "&7Left-click: edit &aBuy &7price"))));
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-click-sell", "&7Right-click: edit &cSell &7price"))));
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-click-both", "&7Drop(Q): edit &eBoth &7prices"))));
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.currency-click-disable", "&7Ctrl+Q: disable"))));
            meta.displayName(GuiUtils.toComponent(Messages.colorize("&f" + displayName)));
        } else {
            // Disabled — grayed out
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.custom-disabled", "&7❌ Not enabled for this item"))));
            lore.add(Component.empty());
            lore.add(GuiUtils.toComponent(Messages.colorize(
                m("editor.custom-click-enable", "&eLeft-click: enable this currency"))));
            meta.displayName(GuiUtils.toComponent(Messages.colorize("&8" + displayName)));
        }

        meta.lore(lore);
        icon.setItemMeta(meta);
        inventory.setItem(slot, icon);
    }

    // ============================ CLICK HANDLING =============================

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        this.currentViewer = player;
        int slot = event.getSlot();

        if (editMode && editingItem != null) {
            handleEditClick(player, slot, event);
        } else {
            handleListClick(player, slot, event);
        }

        // Reopen to reflect changes since clicks are cancelled
        if (player.getOpenInventory().getTopInventory().getHolder() == this) {
            player.openInventory(inventory);
        }
    }

    // --- List view clicks ---

    private void handleListClick(Player player, int slot, InventoryClickEvent event) {
        int totalPages = shopManager.getItemCount() == 0 ? 1 : shopManager.getMaxPage() + 1;

        // If player has an item on cursor and clicked a non-grid slot, clear it to prevent loss
        boolean isGrid = slot >= 0 && slot < ITEMS_PER_PAGE;
        if (!isGrid && event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
            event.setCancelled(true);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&7Drop items on the grid area (slots 0-44) to add them."));
            return;
        }

        // Previous page
        if (slot == 45 && page > 0) {
            page--;
            build();
            return;
        }

        // Next page
        if (slot == 51 && page < totalPages - 1) {
            page++;
            build();
            return;
        }

        // Reload
        if (slot == 50) {
            plugin.getPluginConfig().load();
            shopManager.loadFromConfig(plugin.getPluginConfig());
            build();
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + m("editor.reload-success-msg", "&aItems reloaded from database.")));
            return;
        }

        // Insert page before
        if (slot == 46) {
            shiftPagesFrom(page, 1);
            page++; // follow the content that moved right
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.insert-before-success", "&aInserted blank page before page &e{page}", "{page}", String.valueOf(page + 1))));
            build();
            return;
        }

        // Insert page after
        if (slot == 47) {
            shiftPagesFrom(page + 1, 1);
            page++; // go to the new blank page
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.insert-after-success", "&aInserted blank page after page &e{page}", "{page}", String.valueOf(page + 1))));
            build();
            return;
        }

        // Delete page (shift+click to confirm)
        if (slot == 52) {
            if (event.isShiftClick()) {
                deleteCurrentPage(player);
            } else {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.shift-confirm", "&cShift+Click the Delete Page button to confirm.")));
            }
            return;
        }

        // Close / Back to guild list
        if (slot == 53) {
            if (guildEditorId != null) {
                player.closeInventory();
                new com.ohtoai.opshop.gui.GuildAdminListGui(plugin).open(player);
            } else {
                player.closeInventory();
            }
            return;
        }

        // Page info — no action
        if (slot == 49) return;

        // Click on an item in the grid
        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            // Check if this slot already has an item (use filtered list)
            List<ShopItem> pageItems = getFilteredItems().stream()
                .filter(i -> i.getPage() == page).toList();
            ShopItem existing = null;
            for (ShopItem item : pageItems) {
                if (item.getSlot() == slot) { existing = item; break; }
            }

            // Check for cursor item drop (add new item — only on empty slots)
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (existing != null) {
                    player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + m("editor.slot-occupied", "&cSlot already occupied! Drop on an empty slot to add.")));
                } else {
                    addItemFromDrop(player, cursor, slot);
                    player.setItemOnCursor(null);
                }
                return;
            }

            // Click on existing item
            if (existing != null) {
                if (event.getClick().name().contains("SHIFT") && event.getClick().name().contains("RIGHT")) {
                    deleteItemQuick(player, existing);
                } else {
                    enterEditMode(player, existing);
                }
                return;
            }
        }
    }

    /** Quick-add item from cursor drop — auto-generates ID from item name. */
    private void addItemFromDrop(Player player, ItemStack cursor, int slot) {
        // Generate ID from plain text of display name (strips all formatting)
        String baseId;
        if (cursor.hasItemMeta() && cursor.getItemMeta().hasDisplayName()) {
            baseId = plainText(cursor.getItemMeta().displayName());
            if (baseId.isEmpty()) baseId = cursor.getType().name().toLowerCase();
        } else {
            baseId = cursor.getType().name().toLowerCase();
        }
        String id = baseId;
        int n = 1;
        while (shopManager.getItem(id).isPresent()) id = baseId + "_" + (++n);

        ShopItem newItem = new ShopItem(id);
        newItem.setPage(page);
        newItem.setSlot(slot);
        newItem.setDisplayItem(ItemSerializer.serialize(cursor));
        newItem.setProductItem(ItemSerializer.serialize(cursor));
        newItem.setBuyEnabled(true);
        newItem.setSellEnabled(true);
        currencyManager.applyGlobalDefaults(newItem);
        if (guildEditorId != null) {
            newItem.getAllowedGuildIds().add(guildEditorId);
            newItem.setGuildRestrictionEnabled(true);
        }

        try {
            shopManager.saveItem(newItem);
            if (guildEditorId != null) {
                shopManager.saveItemGuilds(newItem.getId(), newItem.getAllowedGuildIds());
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.item-created-msg", "&aCreated: &e{id} &7on page {page} slot {slot}",
                    "{id}", id, "{page}", String.valueOf(page + 1), "{slot}", String.valueOf(slot))));
            build();
        } catch (java.sql.SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + m("editor.create-failed", "&cFailed to create item!")));
        }
    }

    /** Quick-delete an item with shift+right-click. */
    private void deleteItemQuick(Player player, ShopItem item) {
        try {
            shopManager.deleteItem(item.getId());
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1.0f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.item-deleted-msg", "&cDeleted: &e{id}", "{id}", item.getId())));
            build();
        } catch (java.sql.SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + m("editor.delete-failed", "&cFailed to delete!")));
        }
    }

    public boolean isAnvilOpen() { return anvilOpen || anvilPriceMode; }

    /** Handle anvil close for price input mode — parse the typed value and apply. */
    private void handlePriceAnvilClose(Player player, Inventory closedInv) {
        anvilPriceMode = false;
        int slot = anvilPriceSlot;
        String currencyId = anvilPriceCurrencyId;
        boolean both = anvilPriceBoth;
        boolean isSell = anvilPriceIsSell;  // save before reset

        // Reset state immediately
        anvilPriceSlot = -1;
        anvilPriceCurrencyId = null;
        anvilPriceBoth = false;
        anvilPriceIsSell = false;

        // Read the typed text from slot 2 (anvil result slot) BEFORE clearing
        ItemStack result = closedInv.getItem(2);
        String input = null;
        if (result != null && result.hasItemMeta() && result.getItemMeta().hasDisplayName()) {
            input = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(result.getItemMeta().displayName()).trim();
        }

        // Clear anvil to prevent the input paper from being returned to player
        closedInv.clear();

        // If player pressed ESC (no rename text typed), just reopen editor
        if (input == null || input.isEmpty()) {
            scheduleEditorReopen(player);
            return;
        }

        if (both) {
            // Parse "buy sell" format
            String[] parts = input.split("\\s+");
            double buy = 0, sellVal = 0;
            try {
                if (parts.length >= 1) buy = Double.parseDouble(parts[0]);
                if (parts.length >= 2) sellVal = Double.parseDouble(parts[1]);
                else sellVal = buy;
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&cInvalid number format! Use: buy sell (e.g. 100 50)"));
                scheduleEditorReopen(player);
                return;
            }
            buy = Math.max(0, buy);
            sellVal = Math.max(0, sellVal);
            if (currencyId != null) {
                editingItem.setCustomCurrencyPrice(currencyId, buy, sellVal);
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&aSet " + currencyId + ": Buy=" + formatPrice(buy) + " Sell=" + formatPrice(sellVal)));
            } else if (slot == 10) {
                editingItem.setVaultBuyPrice(buy);
                editingItem.setVaultSellPrice(sellVal);
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&aVault: Buy=" + formatPrice(buy) + " Sell=" + formatPrice(sellVal)));
            } else if (slot == 16) {
                editingItem.setPpBuyPrice(buy);
                editingItem.setPpSellPrice(sellVal);
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&aPoints: Buy=" + formatPrice(buy) + " Sell=" + formatPrice(sellVal)));
            }
        } else {
            double value;
            try {
                value = Double.parseDouble(input);
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&cInvalid number! Type a price like: 100.5"));
                scheduleEditorReopen(player);
                return;
            }
            value = Math.max(0, value);
            if (slot == 10) {
                if (isSell) editingItem.setVaultSellPrice(value);
                else editingItem.setVaultBuyPrice(value);
            } else if (slot == 16) {
                if (isSell) editingItem.setPpSellPrice(value);
                else editingItem.setPpBuyPrice(value);
            } else if (currencyId != null) {
                var cur = editingItem.getCustomCurrencyPrices().get(currencyId);
                double curBuy = cur != null ? cur.buyPrice() : 0;
                double curSell = cur != null ? cur.sellPrice() : 0;
                if (isSell) editingItem.setCustomCurrencyPrice(currencyId, curBuy, value);
                else editingItem.setCustomCurrencyPrice(currencyId, value, curSell);
            }
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aPrice set to: &f" + formatPrice(value)));
        }

        scheduleEditorReopen(player);
    }

    /** Schedule editor reopen on next tick — avoids InventoryCloseEvent re-entrancy bug. */
    private void scheduleEditorReopen(Player player) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            build();
            player.openInventory(inventory);
        });
    }

    /**
     * Public entry point for external GUIs to trigger price anvil input.
     * Called by CustomCurrencyPriceGui's "Set Both" button.
     */
    public void triggerPriceAnvil(Player player, String currencyId) {
        var prices = editingItem.getCustomCurrencyPrices().get(currencyId);
        double buy = prices != null ? prices.buyPrice() : 0;
        double sell = prices != null ? prices.sellPrice() : 0;
        String current = formatPrice(buy) + " " + formatPrice(sell);
        openPriceAnvil(player, -1, currencyId, true, current);
    }

    /** Open an anvil GUI for typing a price value directly. */
    void openPriceAnvil(Player player, int slot, String currencyId, boolean both, String currentValue) {
        openPriceAnvil(player, slot, currencyId, both, currentValue, false);
    }

    void openPriceAnvil(Player player, int slot, String currencyId, boolean both, String currentValue, boolean isSell) {
        this.anvilPriceMode = true;
        this.anvilPriceSlot = slot;
        this.anvilPriceCurrencyId = currencyId;
        this.anvilPriceBoth = both;
        this.anvilPriceIsSell = isSell;

        // Build the anvil input item with current price as placeholder text
        List<String> anvilLore = new ArrayList<>(both
            ? List.of("&7Format: buy sell", "&7e.g. 100 50")
            : List.of("&7Type new price"));
        anvilLore.add("&8---");
        anvilLore.add("&7Press &oESC &7to cancel");
        ItemStack inputItem = GuiUtils.makeItem(Material.PAPER, currentValue, anvilLore);
        org.bukkit.inventory.InventoryView anvilView = player.openAnvil(null, true);
        if (anvilView != null) {
            anvilView.setItem(0, inputItem);
        }
    }

    /** Handle anvil click — just mark that result was clicked, actual creation in handleAnvilClose. */
    public void handleAnvilClick(Player player, InventoryClickEvent event) {
        // The anvil result click — Minecraft moves the item to cursor.
        // We just let it happen and finalize on close.
    }

    /** Handle anvil close — dispatch to price-input or item-creation handler. */
    public void handleAnvilClose(Player player, Inventory closedInv) {
        if (anvilPriceMode) {
            handlePriceAnvilClose(player, closedInv);
            return;
        }
        if (!anvilOpen) return;
        anvilOpen = false;

        if (pendingCursorItem == null) {
            open(player);
            return;
        }

        // Read the name from slot 0 (the renamed item)
        ItemStack slot0 = closedInv.getItem(0);
        String id = pendingDefaultId;
        if (slot0 != null && slot0.hasItemMeta() && slot0.getItemMeta().hasDisplayName()) {
            id = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(slot0.getItemMeta().displayName()).trim();
        }
        id = id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (id.isEmpty()) id = pendingDefaultId;

        // If the name hasn't changed (player didn't take result), cancel
        if (id.equals(pendingDefaultId) && (slot0 == null || slot0.getType() == Material.PAPER)) {
            // Player probably pressed Escape — cancel
            pendingCursorItem = null;
            open(player);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.item-cancelled-msg", "&7Item creation cancelled.")));
            return;
        }

        if (shopManager.getItem(id).isPresent()) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.item-exists-msg", "&cItem '{id}' already exists!", "{id}", id)));
            pendingCursorItem = null;
            open(player);
            return;
        }

        // Create item
        ShopItem newItem = new ShopItem(id);
        newItem.setSlot(findNextUnusedSlot(page));
        newItem.setDisplayItem(ItemSerializer.serialize(pendingCursorItem));
        newItem.setProductItem(ItemSerializer.serialize(pendingCursorItem));
        newItem.setBuyEnabled(true);
        newItem.setSellEnabled(true);
        currencyManager.applyGlobalDefaults(newItem);
        if (guildEditorId != null) {
            newItem.getAllowedGuildIds().add(guildEditorId);
            newItem.setGuildRestrictionEnabled(true);
        }

        try {
            shopManager.saveItem(newItem);
            if (guildEditorId != null) {
                shopManager.saveItemGuilds(newItem.getId(), newItem.getAllowedGuildIds());
            }
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aCreated: &e" + id));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            pendingCursorItem = null;
            enterEditMode(player, newItem);
        } catch (java.sql.SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&cFailed to create item!"));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to create from anvil", e);
            pendingCursorItem = null;
            open(player);
        }
    }

    /** Switch into edit mode for the given item. */
    private void enterEditMode(Player player, ShopItem item) {
        this.editMode = true;
        this.editingItem = item;
        this.editingItemId = item.getId();
        this.deleteConfirmPending = false;
        build();
    }

    /**
     * Create a new ShopItem from the player's held item with an auto-generated
     * ID and open the edit view immediately.
     */
    private void addItemFromHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cHold an item in your main hand first!"));
            return;
        }

        // Generate a unique ID from the material name
        String baseId = held.getType().name().toLowerCase();
        String itemId = baseId;
        int counter = 1;
        while (shopManager.getItem(itemId).isPresent()) {
            itemId = baseId + "_" + (++counter);
        }

        // Find the next unused slot index
        int nextSlot = findNextUnusedSlot(page);

        ShopItem newItem = new ShopItem(itemId);
        newItem.setPage(page);
        newItem.setSlot(nextSlot);
        newItem.setDisplayItem(ItemSerializer.serialize(held));
        newItem.setProductItem(ItemSerializer.serialize(held));
        newItem.setBuyEnabled(true);
        newItem.setSellEnabled(true);
        currencyManager.applyGlobalDefaults(newItem);
        if (guildEditorId != null) {
            newItem.getAllowedGuildIds().add(guildEditorId);
            newItem.setGuildRestrictionEnabled(true);
        }

        try {
            shopManager.saveItem(newItem);
            if (guildEditorId != null) {
                shopManager.saveItemGuilds(newItem.getId(), newItem.getAllowedGuildIds());
            }
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&aCreated new item: &e" + itemId));
            enterEditMode(player, newItem);
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cFailed to create item!"));
            plugin.getLogger().log(Level.WARNING, "Failed to create shop item from hand", e);
        }
    }

    /** Add an item from the player's cursor (drag & drop into editor). */
    private void addItemFromCursor(Player player, ItemStack cursorItem) {
        // Generate a unique ID from the material name
        String baseId = cursorItem.getType().name().toLowerCase();
        String itemId = baseId;
        int counter = 1;
        while (shopManager.getItem(itemId).isPresent()) {
            itemId = baseId + "_" + (++counter);
        }

        int nextSlot = findNextUnusedSlot(page);

        ShopItem newItem = new ShopItem(itemId);
        newItem.setPage(page);
        newItem.setSlot(nextSlot);
        newItem.setDisplayItem(ItemSerializer.serialize(cursorItem));
        newItem.setProductItem(ItemSerializer.serialize(cursorItem));
        newItem.setBuyEnabled(true);
        newItem.setSellEnabled(true);
        currencyManager.applyGlobalDefaults(newItem);
        if (guildEditorId != null) {
            newItem.getAllowedGuildIds().add(guildEditorId);
            newItem.setGuildRestrictionEnabled(true);
        }

        try {
            shopManager.saveItem(newItem);
            if (guildEditorId != null) {
                shopManager.saveItemGuilds(newItem.getId(), newItem.getAllowedGuildIds());
            }
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&aCreated new item: &e" + itemId));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            enterEditMode(player, newItem);
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cFailed to create item!"));
            plugin.getLogger().log(Level.WARNING, "Failed to create shop item from cursor", e);
        }
    }

    // --- Edit view clicks ---

    private void handleEditClick(Player player, int slot, InventoryClickEvent event) {

        // ---- Navigation & actions ----
        if (slot == 53) {
            if (guildEditorId != null) backToGuildList(player); else exitEditMode();
            return;
        }
        if (slot == 48) { saveItem(player); return; }
        if (slot == 50) { deleteItem(player); return; }

        // Slot 13 — Drop item to replace
        if (slot == 13) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                editingItem.setDisplayItem(ItemSerializer.serialize(cursor));
                editingItem.setProductItem(ItemSerializer.serialize(cursor));
                player.setItemOnCursor(null);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&aItem updated: &e" + editingItemId));
                build();
            }
            return;
        }

        // ---- Currency icons (Row 1: 10=Vault, 16=Points; Row 2: 20-24=Custom) ----
        if (slot == 10) { handleCurrencyClick(player, slot, event, "vault"); return; }
        if (slot == 16) { handleCurrencyClick(player, slot, event, "points"); return; }
        if (slot >= 20 && slot <= 24) { handleCustomDefClick(player, slot, event); return; }

        // ---- Custom currency pagination (counts ALL defined CUSTOM_ITEM currencies) ----
        if (slot == 19 && customCurrencyPage > 0) { customCurrencyPage--; build(); return; }
        if (slot == 25) {
            long totalDefs = currencyManager.getDefinitions().values().stream()
                .filter(d -> "CUSTOM_ITEM".equalsIgnoreCase(d.type())).count();
            int totalPages = totalDefs == 0 ? 1 : (int) Math.ceil((double) totalDefs / 5);
            if (customCurrencyPage < totalPages - 1) { customCurrencyPage++; build(); }
            return;
        }

        // ---- Row 4: Toggles + Get Item + Log ----
        if (slot == 36) { editingItem.setBuyEnabled(!editingItem.isBuyEnabled()); build(); return; }
        if (slot == 37) {
            if (editingItem.isApplyPlaceholders()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.sell-locked-desc", "&cSell is locked while Placeholders is enabled.")));
            } else if (editingItem.isGenerateUniqueId()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.generate-id-locked-desc", "&cSell is locked while Trace ID is enabled.")));
            } else { editingItem.setSellEnabled(!editingItem.isSellEnabled()); build(); }
            return;
        }
        if (slot == 38) {
            boolean on = !editingItem.isApplyPlaceholders();
            editingItem.setApplyPlaceholders(on);
            if (on) editingItem.setSellEnabled(false);
            build(); return;
        }
        if (slot == 39) {
            if (editingItem.isApplyPlaceholders() || editingItem.isGenerateUniqueId()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.refund-locked-desc", "&cRefund Limit is locked.")));
            } else { editingItem.setRefundLimit(!editingItem.isRefundLimit()); build(); }
            return;
        }
        if (slot == 40) {
            boolean on = !editingItem.isGenerateUniqueId();
            editingItem.setGenerateUniqueId(on);
            if (on) editingItem.setSellEnabled(false);
            build(); return;
        }
        if (slot == 42) { giveItemToPlayer(player, event.isShiftClick()); return; }
        if (slot == 43) {
            player.closeInventory();
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin, editingItemId).open(player);
            return;
        }

        // ---- Row 5: Limits ----
        if (slot == 47) { adjustLimit(true, event); return; }
        if (slot == 51) { adjustLimit(false, event); return; }
    }

    /**
     * Handle click on a custom currency definition icon (slots 20-24).
     * If enabled: edit prices. If disabled: left-click enables it.
     */
    private void handleCustomDefClick(Player player, int slot, InventoryClickEvent event) {
        // Resolve which custom currency definition this slot maps to
        var allDefs = currencyManager.getDefinitions().values().stream()
            .filter(d -> "CUSTOM_ITEM".equalsIgnoreCase(d.type()))
            .toList();
        int idx = customCurrencyPage * 5 + (slot - 20);
        if (idx < 0 || idx >= allDefs.size()) return;
        var def = allDefs.get(idx);
        String cid = def.id();
        boolean enabled = editingItem.hasCustomCurrency(cid);

        if (!enabled) {
            // Disabled — left-click enables with default prices
            if (event.isLeftClick() && !event.isShiftClick() && !event.isRightClick()) {
                double defaultBuy = 1.0, defaultSell = 1.0;
                for (var entry : currencyManager.getGlobalDefaults()) {
                    if (cid.equals(entry.get("currency"))) {
                        defaultBuy = ((Number) entry.getOrDefault("buy-price", 1.0)).doubleValue();
                        defaultSell = ((Number) entry.getOrDefault("sell-price", 1.0)).doubleValue();
                        break;
                    }
                }
                editingItem.setCustomCurrencyPrice(cid, defaultBuy, defaultSell);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.currency-enabled-msg", "&aEnabled {currency}&r&a for this item.",
                        "{currency}", def.displayName())));
                build();
            }
            return;
        }

        // Enabled — same behavior as vault/points
        var cp = editingItem.getCustomCurrencyPrices().get(cid);
        double curBuy = cp != null ? cp.buyPrice() : 0;
        double curSell = cp != null ? cp.sellPrice() : 0;
        boolean left = event.isLeftClick();
        boolean right = event.isRightClick();
        boolean drop = event.getClick().name().contains("DROP");
        boolean ctrl = event.getClick().name().contains("CONTROL");

        // Ctrl+Q: disable (remove from item)
        if (drop && ctrl) {
            editingItem.removeCustomCurrencyPrice(cid);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.currency-removed-msg", "&cRemoved {currency}&r&c from this item.",
                    "{currency}", def.displayName())));
            build();
            return;
        }

        // Q/Drop: edit both
        if (drop) {
            player.closeInventory();
            String current = formatPrice(curBuy) + " " + formatPrice(curSell);
            openPriceAnvil(player, slot, cid, true, current);
            return;
        }

        // Right-click: edit sell
        if (right) {
            player.closeInventory();
            openPriceAnvil(player, slot, cid, false, formatPrice(curSell), true);
            return;
        }

        // Left-click: edit buy
        if (left) {
            player.closeInventory();
            openPriceAnvil(player, slot, cid, false, formatPrice(curBuy), false);
            return;
        }
    }

    /**
     * Handle click on a currency icon.
     * @param key "vault", "points", or null (custom — resolved from slot position).
     */
    private void handleCurrencyClick(Player player, int slot, InventoryClickEvent event, String key) {
        boolean left = event.isLeftClick();
        boolean right = event.isRightClick();
        boolean drop = event.getClick().name().contains("DROP");
        boolean ctrl = event.getClick().name().contains("CONTROL");

        // Resolve which currency and its current prices
        String currencyId;
        double curBuy, curSell;
        if ("vault".equals(key)) {
            currencyId = null;
            curBuy = editingItem.getVaultBuyPrice();
            curSell = editingItem.getVaultSellPrice();
        } else if ("points".equals(key)) {
            currencyId = null;
            curBuy = editingItem.getPpBuyPrice();
            curSell = editingItem.getPpSellPrice();
        } else {
            var customIds = new ArrayList<>(editingItem.getCustomCurrencyPrices().keySet());
            int idx = customCurrencyPage * 5 + (slot - 20);
            if (idx < 0 || idx >= customIds.size()) return;
            currencyId = customIds.get(idx);
            var cp = editingItem.getCustomCurrencyPrices().get(currencyId);
            curBuy = cp != null ? cp.buyPrice() : 0;
            curSell = cp != null ? cp.sellPrice() : 0;
        }

        // Ctrl+Q: disable (custom currency only — remove from item)
        if (drop && ctrl && currencyId != null) {
            editingItem.removeCustomCurrencyPrice(currencyId);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("editor.currency-removed-msg", "&cRemoved {currency}&r&c from this item.",
                    "{currency}", currencyId)));
            build();
            return;
        }

        // Q/Drop: edit both prices
        if (drop) {
            player.closeInventory();
            String current = formatPrice(curBuy) + " " + formatPrice(curSell);
            openPriceAnvil(player, slot, currencyId, true, current);
            return;
        }

        // Right-click: edit sell price
        if (right) {
            player.closeInventory();
            openPriceAnvil(player, slot, currencyId, false, formatPrice(curSell), true);
            return;
        }

        // Left-click: edit buy price
        if (left) {
            player.closeInventory();
            openPriceAnvil(player, slot, currencyId, false, formatPrice(curBuy), false);
            return;
        }
    }

    // -------------------------------------------------------------------------
    // Edit-view action implementations
    // -------------------------------------------------------------------------

    private void exitEditMode() {
        this.editMode = false;
        this.editingItem = null;
        this.editingItemId = null;
        this.deleteConfirmPending = false;
        this.page = 0;
        build();
    }

    /** Back to guild admin list from guild editor edit view (slot 53). */
    private void backToGuildList(Player player) {
        player.closeInventory();
        new com.ohtoai.opshop.gui.GuildAdminListGui(plugin).open(player);
    }

    private void saveItem(Player player) {
        try {
            shopManager.saveItem(editingItem);
            deleteConfirmPending = false;
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&aItem saved: &e" + editingItemId));
            build();
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cFailed to save item!"));
            plugin.getLogger().log(Level.WARNING, "Failed to save item: " + editingItemId, e);
        }
    }

    private void deleteItem(Player player) {
        if (!deleteConfirmPending) {
            deleteConfirmPending = true;
            build();
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cClick the red wool again to confirm deletion."));
            return;
        }

        try {
            shopManager.deleteItem(editingItemId);
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cItem deleted: &e" + editingItemId));
            exitEditMode();
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cFailed to delete item!"));
            plugin.getLogger().log(Level.WARNING, "Failed to delete item: " + editingItemId, e);
            deleteConfirmPending = false;
            build();
        }
    }

    /** Adjust total or player limit via click. */
    private void adjustLimit(boolean isTotal, InventoryClickEvent event) {
        int current = isTotal ? editingItem.getTotalLimit() : editingItem.getPlayerLimit();
        if (current < 0) current = 0; // treat -1 as 0 for adjustment start

        boolean left = event.isLeftClick();
        boolean shift = event.isShiftClick();
        boolean drop = event.getClick().name().contains("DROP");

        if (drop) {
            current = -1; // reset to unlimited
        } else if (shift && left) {
            current += 10;
        } else if (shift && !left) {
            current = Math.max(0, current - 10);
        } else if (left) {
            current += 1;
        } else {
            current = Math.max(-1, current - 1);
        }

        if (isTotal) editingItem.setTotalLimit(current);
        else editingItem.setPlayerLimit(current);
        build();
    }

    private void setDisplayFromHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cHold an item in your main hand first!"));
            return;
        }
        editingItem.setDisplayItem(ItemSerializer.serialize(held));
        deleteConfirmPending = false;
        player.sendMessage(Messages.colorize(
            plugin.getMessages().getPrefix() + "&aDisplay item set to: &e" + held.getType().name()));
        build();
    }

    private void setProductFromHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&cHold an item in your main hand first!"));
            return;
        }
        editingItem.setProductItem(ItemSerializer.serialize(held));
        deleteConfirmPending = false;
        player.sendMessage(Messages.colorize(
            plugin.getMessages().getPrefix() + "&aProduct item set to: &e" + held.getType().name()));
        build();
    }

    /** Give a copy of the editing item to the player. Shift = with placeholders applied. */
    private void giveItemToPlayer(Player player, boolean withPlaceholders) {
        if (editingItem == null) return;
        ItemStack item = ItemSerializer.deserialize(editingItem.getDisplayItem());
        if (item == null || item.getType() == Material.AIR) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + m("editor.item-data-empty", "&cItem data is empty!")));
            return;
        }
        if (withPlaceholders) {
            item = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(item, player);
        }
        var leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
            + "&aGave 1x &e" + editingItemId
            + (withPlaceholders ? " &7(with placeholders)" : " &7(raw)")));
    }

    /** Extract plain text from an Adventure Component, stripping all formatting. */
    private static String plainText(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(component);
    }

    private int findNextUnusedSlot(int page) {
        return shopManager.findFirstEmptySlot(page);
    }

    private static String formatPrice(double price) {
        if (price == Math.floor(price) && !Double.isInfinite(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }

    // -------------------------------------------------------------------------
    // GuiHolder contract
    // -------------------------------------------------------------------------

    @Override
    public void refresh() {
        build();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
