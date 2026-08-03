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
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Main shop GUI — paginated grid of items.
 * Clicking an item opens ItemDetailGui for combined buy/sell.
 * No buy/sell mode toggle; both operations happen in ItemDetailGui.
 */
public class ShopMainGui implements GuiHolder {

    private final OpShopPlugin plugin;
    private final ShopManager shopManager;
    private final CurrencyManager currencyManager;
    private final TransactionProcessor transactionProcessor;
    private String m(String k, String e, String... r) { return plugin.getMessages().get(k, e, r); }

    private Inventory inventory;
    private int currentPage = 0;

    // Per-player guild filter context (set by /oshop guild command)
    private final Map<UUID, Integer> playerGuildId = new HashMap<>();
    private final Map<UUID, String> playerGuildName = new HashMap<>();
    private Integer guildIdFor(Player p) { return playerGuildId.get(p.getUniqueId()); }
    private String guildNameFor(Player p) { return playerGuildName.get(p.getUniqueId()); }

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;

    // Navigation bar (row 5)
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;

    public ShopMainGui(OpShopPlugin plugin, ShopManager shopManager,
                        CurrencyManager currencyManager, TransactionProcessor transactionProcessor) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.currencyManager = currencyManager;
        this.transactionProcessor = transactionProcessor;
    }

    /**
     * Open the main shop view showing all items.
     */
    public void open(Player player) {
        this.playerGuildId.remove(player.getUniqueId());
        this.playerGuildName.remove(player.getUniqueId());
        com.ohtoai.opshop.item.PlaceholderReplacer.clearGuildContext(player.getUniqueId());
        this.currentPage = 0;
        buildAndShow(player);
    }

    /**
     * Open the shop filtered for a specific guild's perspective.
     * @param guildTag the guild's short tag (may be null)
     */
    public void openForGuild(Player player, int guildId, String guildName, String guildTag) {
        this.playerGuildId.put(player.getUniqueId(), guildId);
        this.playerGuildName.put(player.getUniqueId(), guildName);
        com.ohtoai.opshop.item.PlaceholderReplacer.setGuildContext(
            player.getUniqueId(), guildName, guildTag);
        this.currentPage = 0;
        buildAndShow(player);
    }

    private void buildAndShow(Player player) {
        String gName = guildNameFor(player);
        String title = gName != null
            ? plugin.getMessages().get("gui.guild-title", "&8{name} Shop",
                "{name}", gName)
            : plugin.getPluginConfig().getGuiTitle();
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material fillerMaterial = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (fillerMaterial == null) fillerMaterial = Material.BLACK_STAINED_GLASS_PANE;

        buildItemView(fillerMaterial, player);

        player.openInventory(inventory);
    }

    private void buildItemView(Material fillerMaterial, Player player) {
        GuiUtils.fill(inventory, fillerMaterial);

        // Filter items: normal shop excludes guild items; guild view only shows this guild's items
        List<ShopItem> allItems = shopManager.getAllItems();
        Integer gId = guildIdFor(player);
        if (gId != null) {
            allItems = allItems.stream()
                .filter(i -> i.isGuildRestricted() && i.getAllowedGuildIds().contains(gId))
                .toList();
        } else {
            allItems = allItems.stream()
                .filter(i -> !i.isGuildRestricted())
                .toList();
        }
        // Calculate pagination based on the filtered list
        int totalItemCount = allItems.size();
        int totalPages = totalItemCount == 0 ? 1
            : allItems.stream().mapToInt(ShopItem::getPage).max().orElse(0) + 1;
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        List<ShopItem> itemsOnPage = allItems.stream()
            .filter(i -> i.getPage() == currentPage)
            .sorted(Comparator.comparingInt(ShopItem::getSlot))
            .toList();

        for (int i = 0; i < itemsOnPage.size() && i < ITEMS_PER_PAGE; i++) {
            ShopItem item = itemsOnPage.get(i);
            int displaySlot = item.getSlot(); // use item's slot as grid position

            ItemStack displayItem = ItemSerializer.deserialize(item.getDisplayItem());
            if (displayItem == null) {
                displayItem = new ItemStack(Material.STONE);
            }
            // Show demo placeholders in preview
            if (item.isApplyPlaceholders()) {
                displayItem = com.ohtoai.opshop.item.PlaceholderReplacer.applyPreview(displayItem, player);
            }

            displayItem = addPriceInfo(displayItem, item, player);
            inventory.setItem(displaySlot, displayItem);
        }

        // Navigation bar (row 5)

        // Previous page
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV, GuiUtils.makeItem(Material.ARROW,
                m("gui.prev-page", "&a← Previous Page"),
                List.of(m("gui.page-num", "&7Page {p}/{t}", "{p}", String.valueOf(currentPage), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_INFO, GuiUtils.makeItem(Material.PAPER,
            m("gui.page-info", "&ePage {current} / {total}", "{current}", String.valueOf(currentPage + 1), "{total}", String.valueOf(totalPages)),
            List.of(m("gui.items-total", "&7{count} items total",
                "{count}", String.valueOf(guildIdFor(player) != null ? totalItemCount : shopManager.getItemCount())))));
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtils.makeItem(Material.ARROW,
                m("gui.next-page", "&a→ Next Page"),
                List.of(m("gui.page-num", "&7Page {p}/{t}", "{p}", String.valueOf(currentPage + 2), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of(m("gui.close-desc", "&7Click to close the shop"))));
    }

    /**
     * Add buy/sell price preview lore to a display item.
     */
    private ItemStack addPriceInfo(ItemStack displayItem, ShopItem item, Player player) {
        ItemMeta meta = displayItem.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());

        List<PriceConfig> buyPrices = shopManager.getBuyPrices(item);
        List<PriceConfig> sellPrices = shopManager.getSellPrices(item);

        if (!buyPrices.isEmpty()) {
            lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.buy-prices", "&eBuy prices:"))));
            for (PriceConfig pc : buyPrices) {
                var providerOpt = currencyManager.getProvider(pc.getCurrencyId());
                String currencyDisplay = providerOpt.map(CurrencyProvider::getDisplayName).orElse(pc.getCurrencyId());
                lore.add(GuiUtils.toComponent(Messages.colorize("&7  &a" + formatPrice(pc.getBuyPrice()) + " " + currencyDisplay)));
            }
        }
        if (!sellPrices.isEmpty() && !item.isApplyPlaceholders()) {
            lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.sell-prices", "&eSell prices:"))));
            for (PriceConfig pc : sellPrices) {
                var providerOpt = currencyManager.getProvider(pc.getCurrencyId());
                String currencyDisplay = providerOpt.map(CurrencyProvider::getDisplayName).orElse(pc.getCurrencyId());
                lore.add(GuiUtils.toComponent(Messages.colorize("&7  &6" + formatPrice(pc.getSellPrice()) + " " + currencyDisplay)));
            }
        }
        if (buyPrices.isEmpty() && sellPrices.isEmpty()) {
            lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.not-available", "&cNot available for trade"))));
        }
        // Guild restriction info
        if (item.isGuildRestricted()) {
            var hook = plugin.getHookManager();
            lore.add(Component.empty());
            // Check if current guild filter matches this item
            Integer gId = guildIdFor(player);
            boolean guildCanBuy = gId != null
                && item.getAllowedGuildIds().contains(gId);
            if (gId != null && !guildCanBuy) {
                lore.add(GuiUtils.toComponent(Messages.colorize(
                    m("gui.guild-locked", "&c&l🔒 Locked for this guild"))));
            }
            lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.guild-restricted", "&d&lGuild Restricted"))));
            if (hook.isGuildEnabled()) {
                int shown = 0;
                for (var gi : hook.getGuildBridge().getAllGuilds()) {
                    if (shown >= 3) break;
                    if (item.getAllowedGuildIds().contains(gi.id())) {
                        lore.add(GuiUtils.toComponent(Messages.colorize(
                            "&7  &f" + (gi.name() != null ? gi.name() : "#" + gi.id())
                                + " &7[" + (gi.tag() != null ? gi.tag() : "") + "]")));
                        shown++;
                    }
                }
                int remaining = item.getAllowedGuildIds().size() - shown;
                if (remaining > 0) {
                    lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.guild-more",
                        "&7  ... +{count} more", "{count}", String.valueOf(remaining)))));
                }
            }
        }
        lore.add(Component.empty());
        lore.add(GuiUtils.toComponent(Messages.colorize(m("gui.click-to-view", "&eClick to view details"))));

        meta.lore(lore);
        displayItem.setItemMeta(meta);
        return displayItem;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        if (slot == SLOT_PREV) {
            if (currentPage > 0) {
                currentPage--;
                buildAndShow(player);
            }
            return;
        }

        if (slot == SLOT_NEXT) {
            int totalPages = shopManager.getItemCount() == 0 ? 1 : shopManager.getMaxNonEmptyPage() + 1;
            if (currentPage < totalPages - 1) {
                currentPage++;
                buildAndShow(player);
            }
            return;
        }

        if (slot == SLOT_INFO) {
            // Page info item — no action
            return;
        }

        // Item slots (rows 0-4, slots 0-44)
        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            handleItemClick(player, slot);
        }
    }

    private void handleItemClick(Player player, int slot) {
        // Find item at this slot on the current page
        List<ShopItem> pageItems = shopManager.getItemsOnPage(currentPage);
        ShopItem item = pageItems.stream()
            .filter(i -> i.getSlot() == slot).findFirst().orElse(null);
        if (item == null) return;

        if (!item.isBuyEnabled() && !item.isSellEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefix()
                + m("gui.item-not-available", "&cThis item is not available for trade."));
            return;
        }

        // Close and open ItemDetailGui
        // Note: guild membership is checked authoritatively by TransactionProcessor at purchase time
        player.closeInventory();
        new ItemDetailGui(plugin, this, item, transactionProcessor, shopManager, currencyManager).open(player);
    }

    @Override
    public void refresh() {
        // Subclasses can trigger rebuilds if needed
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Re-open the shop for the given player, preserving the current category.
     * Called by sub-GUIs (ItemDetailGui) when returning to the shop.
     */
    public void returnToShop(Player player) {
        // Rebuild at current page — preserve guild context
        buildAndShow(player);
    }

    public Integer getCurrentGuildId(Player player) { return guildIdFor(player); }
    public String getCurrentGuildName(Player player) { return guildNameFor(player); }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
