package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.PriceConfig;
import com.ohtoai.opshop.shop.ShopItem;
import com.ohtoai.opshop.shop.ShopManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

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

    // Anvil input state — waiting for player to confirm ID
    private boolean anvilOpen = false;
    private ItemStack pendingCursorItem;
    private String pendingDefaultId;

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
        this.page = page;
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
        String title = editMode
            ? m("editor.edit-title", "&8Editor: {id}", "{id}", editingItemId)
            : m("editor.title", "&8OpShop Editor");
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

    private void buildListView(Material filler) {
        GuiUtils.fill(inventory, filler);

        // Filter items by current page, show at their assigned slot position
        List<ShopItem> itemsOnPage = shopManager.getItemsOnPage(page);
        int totalPages = shopManager.getItemCount() == 0 ? 1 : shopManager.getMaxPage() + 1;

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
            List.of(m("editor.total-items", "&7{count} total item(s)", "{count}", String.valueOf(shopManager.getItemCount())))));
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
        inventory.setItem(53, GuiUtils.makeItem(Material.BARRIER,
            m("editor.close-editor", "&cClose"),
            List.of(m("editor.close-desc", "&7Close the editor."))));
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
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cDB error moving item " + item.getId()));
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
            + "&aDeleted page &e" + (deletedPage + 1) + "&a. Items moved forward."));
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
        if (item.getCustomCurrencyId() != null)
            lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.custom-prices", "&7{currency} Buy:{buy} Sell:{sell}",
                "{currency}", item.getCustomCurrencyId(),
                "{buy}", formatPrice(item.getCustomBuyPrice()), "{sell}", formatPrice(item.getCustomSellPrice())))));
        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(m("editor.click-to-edit", "&eClick to edit"))));

        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ============================== EDIT VIEW ================================

    private void buildEditView(Material filler) {
        GuiUtils.fill(inventory, filler);

        // ----- Item preview -----

        // Slot 13 — Item preview (drop item here to replace)
        ItemStack display = ItemSerializer.deserialize(editingItem.getDisplayItem());
        if (display != null && display.getType() != Material.AIR) {
            if (editingItem.isApplyPlaceholders()) {
                display = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(display, currentViewer);
            }
            // Add hint lore + placeholder tips
            List<Component> previewLore = new ArrayList<>();
            previewLore.add(GuiUtils.toComponent(Messages.colorize("&7Drop an item here to replace")));
            if (editingItem.isApplyPlaceholders()) {
                previewLore.add(Component.empty());
                previewLore.add(GuiUtils.toComponent(Messages.colorize("&e▸ Placeholder preview (as " + (currentViewer != null ? currentViewer.getName() : "?") + "):")));
                for (String ph : com.ohtoai.opshop.item.PlaceholderReplacer.getAvailablePlaceholders()) {
                    String resolved = com.ohtoai.opshop.item.PlaceholderReplacer.resolveSingle(ph, currentViewer);
                    previewLore.add(GuiUtils.toComponent(Messages.colorize("&7  " + resolved)));
                }
            }
            display.lore(previewLore);
            inventory.setItem(13, display);
        } else {
            inventory.setItem(13, GuiUtils.makeItem(Material.BARRIER,
                "&cDrop Item Here",
                List.of("&7Pick up an item from your inventory",
                    "&7and click here to set it.")));
        }

        // ----- Row 2 (slots 18-26): 3-Currency Pricing -----
        buildPriceSlot(19, Material.GOLD_INGOT, m("editor.vault-buy", "&eVault Buy"), editingItem.getVaultBuyPrice());
        buildPriceSlot(20, Material.GOLD_NUGGET, m("editor.vault-sell", "&eVault Sell"), editingItem.getVaultSellPrice());
        buildPriceSlot(22, Material.DIAMOND, m("editor.points-buy", "&ePoint Buy"), editingItem.getPpBuyPrice());
        buildPriceSlot(23, Material.PRISMARINE_CRYSTALS, m("editor.points-sell", "&ePoint Sell"), editingItem.getPpSellPrice());
        String customName = editingItem.getCustomCurrencyId() != null ? editingItem.getCustomCurrencyId() : "none";
        inventory.setItem(25, GuiUtils.makeItem(Material.NETHER_STAR,
            m("editor.custom-currency", "&eCustom: &f{id}", "{id}", customName),
            List.of(m("editor.custom-currency-desc", "&7Click to select custom currency."))));
        buildPriceSlot(33, Material.LIME_DYE, m("editor.custom-buy", "&eCustom Buy"), editingItem.getCustomBuyPrice());
        buildPriceSlot(34, Material.RED_DYE, m("editor.custom-sell", "&eCustom Sell"), editingItem.getCustomSellPrice());

        // ----- Row 3 (slots 27-35): toggles -----
        String on = m("editor.state-on", "&aON"), off = m("editor.state-off", "&cOFF");
        String buyLabel = m("editor.buy-toggle", "&eBuy: {state}").replace("{state}", editingItem.isBuyEnabled() ? on : off);
        String sellLabel = m("editor.sell-toggle", "&eSell: {state}").replace("{state}", editingItem.isSellEnabled() ? on : off);
        String phLabel = m("editor.placeholders-toggle", "&ePlaceholders: {state}").replace("{state}", editingItem.isApplyPlaceholders() ? on : off);
        String refLabel = m("editor.refund-limit-toggle", "&eRefund Limit: {state}").replace("{state}", editingItem.isRefundLimit() ? on : off);
        String toggleHint = m("editor.toggle-hint", "&7Click to toggle");

        inventory.setItem(28, GuiUtils.makeItem(
            editingItem.isBuyEnabled() ? Material.LIME_DYE : Material.GRAY_DYE, buyLabel, List.of(toggleHint)));
        // Sell toggle: locked when placeholders or generateUniqueId are ON
        if (editingItem.isApplyPlaceholders()) {
            inventory.setItem(29, GuiUtils.makeItem(Material.BARRIER,
                m("editor.sell-locked", "&cSell: &4🔒 LOCKED"),
                List.of(m("editor.sell-locked-desc", "&cDisabled while Placeholders is ON."),
                    m("editor.sell-locked-desc2", "&7Turn off Placeholders to enable Sell."))));
            inventory.setItem(31, GuiUtils.makeItem(Material.BARRIER,
                m("editor.refund-locked", "&cRefund Limit: &4🔒 LOCKED"),
                List.of(m("editor.refund-locked-desc", "&cDisabled while Placeholders is ON."),
                    m("editor.refund-locked-desc2", "&7Turn off Placeholders to enable."))));
        } else if (editingItem.isGenerateUniqueId()) {
            inventory.setItem(29, GuiUtils.makeItem(Material.BARRIER,
                m("editor.generate-id-locked", "&cSell: &4🔒 Trace ID LOCKED"),
                List.of(m("editor.generate-id-locked-desc", "&cDisabled while Trace ID is ON."),
                    m("editor.generate-id-locked-desc2", "&7Turn off Trace ID to enable Sell."))));
            inventory.setItem(31, GuiUtils.makeItem(Material.BARRIER,
                m("editor.refund-locked", "&cRefund Limit: &4🔒 LOCKED"),
                List.of(m("editor.refund-locked-desc", "&cDisabled while Trace ID is ON."),
                    m("editor.refund-locked-desc2", "&7Turn off Trace ID to enable."))));
        } else {
            inventory.setItem(29, GuiUtils.makeItem(
                editingItem.isSellEnabled() ? Material.LIME_DYE : Material.GRAY_DYE, sellLabel, List.of(toggleHint)));
            inventory.setItem(31, GuiUtils.makeItem(
                editingItem.isRefundLimit() ? Material.LIME_DYE : Material.GRAY_DYE, refLabel,
                List.of(m("editor.refund-desc-1", "&7When ON: selling this item back"),
                    m("editor.refund-desc-2", "&7restores purchase quota"),
                    m("editor.refund-desc-3", "&7(global total_sold & player limit)."),
                    toggleHint)));
        }
        inventory.setItem(30, GuiUtils.makeItem(
            editingItem.isApplyPlaceholders() ? Material.LIME_DYE : Material.GRAY_DYE, phLabel, List.of(toggleHint)));

        // Generate Unique ID toggle (slot 32)
        String genIdLabel = m("editor.generate-id-toggle", "&eTrace ID: {state}")
            .replace("{state}", editingItem.isGenerateUniqueId() ? on : off);
        inventory.setItem(32, GuiUtils.makeItem(
            editingItem.isGenerateUniqueId() ? Material.LIME_DYE : Material.GRAY_DYE, genIdLabel,
            List.of(
                m("editor.generate-id-desc-1", "&7When ON: each purchase gets a unique trace ID"),
                m("editor.generate-id-desc-2", "&7ID is written into item data for tracing."),
                m("editor.generate-id-desc-3", "&7Only single purchase (x1), bulk buy disabled."),
                toggleHint)));

        inventory.setItem(36, GuiUtils.makeItem(Material.CHEST,
            m("editor.get-item", "&6Get Item &7(Raw)"),
            List.of(m("editor.get-item-desc-1", "&7Click to receive a copy of this item"),
                m("editor.get-item-desc-2", "&7without placeholder replacement."),
                m("editor.get-item-desc-3", "&7Shift+Click to get with placeholders applied."))));
        inventory.setItem(37, GuiUtils.makeItem(Material.BOOK,
            m("editor.transaction-log", "&eTransaction Log"),
            List.of(m("editor.transaction-log-desc-1", "&7View purchase/sell history"),
                m("editor.transaction-log-desc-2", "&7for this item only."),
                m("editor.transaction-log-desc-3", "&7Click to open."))));

        // ----- Row 5 (slots 45-53): limits + actions -----
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

    private void buildPriceSlot(int slot, Material mat, String name, double value) {
        inventory.setItem(slot, GuiUtils.makeItem(mat, name,
            List.of(m("editor.price-hint", "&7Current: &f{value}", "{value}", formatPrice(value)),
                m("editor.price-controls-1", "&7L: +1 | R: -1"),
                m("editor.price-controls-2", "&7Shift+L: +10 | Shift+R: -10"),
                m("editor.price-controls-3", "&7Drop(Q): +100 | Ctrl+Q: -100"))));
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
                plugin.getMessages().getPrefix() + "&aItems reloaded from database."));
            return;
        }

        // Insert page before
        if (slot == 46) {
            shiftPagesFrom(page, 1);
            page++; // follow the content that moved right
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aInserted blank page before page &e" + (page + 1)));
            build();
            return;
        }

        // Insert page after
        if (slot == 47) {
            shiftPagesFrom(page + 1, 1);
            page++; // go to the new blank page
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aInserted blank page after page &e" + (page + 1)));
            build();
            return;
        }

        // Delete page (shift+click to confirm)
        if (slot == 52) {
            if (event.isShiftClick()) {
                deleteCurrentPage(player);
            } else {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + "&cShift+Click the Delete Page button to confirm."));
            }
            return;
        }

        // Close
        if (slot == 53) {
            player.closeInventory();
            return;
        }

        // Page info — no action
        if (slot == 49) return;

        // Click on an item in the grid
        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            // Check if this slot already has an item
            ShopItem existing = null;
            for (ShopItem item : shopManager.getItemsOnPage(page)) {
                if (item.getSlot() == slot) { existing = item; break; }
            }

            // Check for cursor item drop (add new item — only on empty slots)
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (existing != null) {
                    player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + "&cSlot already occupied! Drop on an empty slot to add."));
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

        try {
            shopManager.saveItem(newItem);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aCreated: &e" + id + " &7on page " + (page + 1) + " slot " + slot));
            build();
        } catch (java.sql.SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cFailed to create item!"));
        }
    }

    /** Quick-delete an item with shift+right-click. */
    private void deleteItemQuick(Player player, ShopItem item) {
        try {
            shopManager.deleteItem(item.getId());
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1.0f);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&cDeleted: &e" + item.getId()));
            build();
        } catch (java.sql.SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cFailed to delete!"));
        }
    }

    public boolean isAnvilOpen() { return anvilOpen; }

    /** Handle anvil click — just mark that result was clicked, actual creation in handleAnvilClose. */
    public void handleAnvilClick(Player player, InventoryClickEvent event) {
        // The anvil result click — Minecraft moves the item to cursor.
        // We just let it happen and finalize on close.
    }

    /** Handle anvil close — read slot 0's display name as the ID and create the item. */
    public void handleAnvilClose(Player player, Inventory closedInv) {
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
                + "&7Item creation cancelled."));
            return;
        }

        if (shopManager.getItem(id).isPresent()) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&cItem '" + id + "' already exists!"));
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

        try {
            shopManager.saveItem(newItem);
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

        try {
            shopManager.saveItem(newItem);
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

        try {
            shopManager.saveItem(newItem);
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

        // Slot 53 — Back to List
        if (slot == 53) {
            exitEditMode();
            return;
        }

        // Slot 48 — Save
        if (slot == 48) {
            saveItem(player);
            return;
        }

        // Slot 50 — Delete (with confirmation gate)
        if (slot == 50) {
            deleteItem(player);
            return;
        }

        // Slot 49 — Revert to Global
        if (slot == 49) {
            // global defaults removed — prices are always custom now
            deleteConfirmPending = false;
            player.sendMessage(Messages.colorize(
                plugin.getMessages().getPrefix() + "&aItem reverted to global defaults."));
            build();
            return;
        }

        // Slot 36 — Give raw item / placeholder-applied item
        if (slot == 36) {
            giveItemToPlayer(player, event.isShiftClick());
            return;
        }
        // Slot 37 — View transaction history for this item
        if (slot == 37) {
            player.closeInventory();
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin, editingItemId).open(player);
            return;
        }

        // Slot 47 — Total Limit (L+1 R-1 ShiftL+10 ShiftR-10 Q=unlimited)
        if (slot == 47) {
            adjustLimit(true, event);
            return;
        }

        // Slot 51 — Player Limit
        if (slot == 51) {
            adjustLimit(false, event);
            return;
        }

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

        // Slot 28 — Toggle Buy Enabled
        if (slot == 28) { editingItem.setBuyEnabled(!editingItem.isBuyEnabled()); build(); return; }
        // Slot 29 — Toggle Sell Enabled (locked when placeholders or generateUniqueId ON)
        if (slot == 29) {
            if (editingItem.isApplyPlaceholders()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.sell-locked-desc", "&cSell is locked while Placeholders is enabled.")));
            } else if (editingItem.isGenerateUniqueId()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.generate-id-locked-desc", "&cSell is locked while Trace ID is enabled.")));
            } else {
                editingItem.setSellEnabled(!editingItem.isSellEnabled()); build();
            }
            return;
        }
        // Slot 30 — Toggle Apply Placeholders (also disables sell when ON)
        if (slot == 30) {
            boolean on = !editingItem.isApplyPlaceholders();
            editingItem.setApplyPlaceholders(on);
            if (on) editingItem.setSellEnabled(false);
            build(); return;
        }
        // Slot 31 — Toggle Refund Limit (locked when placeholders ON)
        if (slot == 31) {
            if (editingItem.isApplyPlaceholders()) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("editor.refund-locked-desc", "&cRefund Limit is locked while Placeholders is enabled.")));
            } else {
                editingItem.setRefundLimit(!editingItem.isRefundLimit()); build();
            }
            return;
        }
        // Slot 32 — Toggle Generate Unique ID (also disables sell when ON)
        if (slot == 32) {
            boolean on = !editingItem.isGenerateUniqueId();
            editingItem.setGenerateUniqueId(on);
            if (on) editingItem.setSellEnabled(false);
            build(); return;
        }

        // Price slots: adjust by click
        if (slot == 19) { editingItem.setVaultBuyPrice(adjustPrice(editingItem.getVaultBuyPrice(), event)); build(); return; }
        if (slot == 20) { editingItem.setVaultSellPrice(adjustPrice(editingItem.getVaultSellPrice(), event)); build(); return; }
        if (slot == 22) { editingItem.setPpBuyPrice(adjustPrice(editingItem.getPpBuyPrice(), event)); build(); return; }
        if (slot == 23) { editingItem.setPpSellPrice(adjustPrice(editingItem.getPpSellPrice(), event)); build(); return; }
        if (slot == 33) { editingItem.setCustomBuyPrice(adjustPrice(editingItem.getCustomBuyPrice(), event)); build(); return; }
        if (slot == 34) { editingItem.setCustomSellPrice(adjustPrice(editingItem.getCustomSellPrice(), event)); build(); return; }

        // Slot 25 — Select custom currency (cycles: id1 → id2 → ... → none → id1)
        if (slot == 25) {
            List<String> customIds = currencyManager.getDefinitions().entrySet().stream()
                .filter(e -> "CUSTOM_ITEM".equalsIgnoreCase(e.getValue().type()))
                .map(Map.Entry::getKey).toList();
            String cur = editingItem.getCustomCurrencyId();
            String next;
            if (cur == null && !customIds.isEmpty()) {
                next = customIds.get(0);
            } else if (cur != null) {
                int idx = customIds.indexOf(cur);
                if (idx < 0 || idx >= customIds.size() - 1) next = null;
                else next = customIds.get(idx + 1);
            } else {
                next = null;
            }
            editingItem.setCustomCurrencyId(next);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + "&aCustom currency: &e" + (next != null ? next : "none")));
            build();
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
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cItem data is empty!"));
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
