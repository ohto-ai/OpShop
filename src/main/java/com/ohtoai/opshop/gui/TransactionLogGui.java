package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.database.TransactionRecord;
import com.ohtoai.opshop.database.TransactionRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated GUI showing transaction history with player skulls.
 */
public class TransactionLogGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int MAX_LOAD = 1000;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final OpShopPlugin plugin;
    private Inventory inventory;
    private int page;
    private final List<TransactionRecord> records = new ArrayList<>();
    private final int totalRecords;
    private final boolean wasTruncated;
    private final String filterItemId;
    private final UUID filterPlayerUuid;
    private final String filterPlayerName;
    private final Runnable onBack; // null = no back button
    private String m(String k, String e, String... r) { return plugin.getMessages().get(k, e, r); }

    public TransactionLogGui(OpShopPlugin plugin) {
        this(plugin, null, null, null, null);
    }

    public TransactionLogGui(OpShopPlugin plugin, String filterItemId) {
        this(plugin, filterItemId, null, null, null);
    }

    public TransactionLogGui(OpShopPlugin plugin, String filterItemId, Runnable onBack) {
        this(plugin, filterItemId, null, null, onBack);
    }

    public TransactionLogGui(OpShopPlugin plugin, UUID playerUuid, String playerName) {
        this(plugin, null, playerUuid, playerName, null);
    }

    private TransactionLogGui(OpShopPlugin plugin, String filterItemId, UUID filterPlayerUuid, String filterPlayerName, Runnable onBack) {
        this.plugin = plugin;
        this.filterItemId = filterItemId;
        this.filterPlayerUuid = filterPlayerUuid;
        this.filterPlayerName = filterPlayerName;
        this.onBack = onBack;
        // Load records (filtered or all)
        int count = 0;
        boolean truncated = false;
        try {
            TransactionRepository repo = plugin.getDatabaseManager().getTransactionRepository();
            if (filterItemId != null) {
                count = (int) repo.countByItem(filterItemId);
                this.records.addAll(repo.queryByItem(filterItemId, 0, Math.min(count, MAX_LOAD)));
                truncated = count > MAX_LOAD;
            } else if (filterPlayerUuid != null) {
                count = (int) repo.countByPlayer(filterPlayerUuid);
                this.records.addAll(repo.queryByPlayer(filterPlayerUuid, 0, Math.min(count, MAX_LOAD)));
                truncated = count > MAX_LOAD;
            } else {
                count = (int) repo.count();
                this.records.addAll(repo.queryRecent(0, Math.min(count, MAX_LOAD)));
                truncated = count > MAX_LOAD;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load transaction logs: " + e.getMessage());
        }
        this.totalRecords = count;
        this.wasTruncated = truncated;
    }

    public void open(Player player) {
        this.page = 0;
        buildInventory();
        player.openInventory(inventory);
    }

    private void buildInventory() {
        String title = filterItemId != null
            ? m("gui.log.title-item", "&8Log: {id}", "{id}", filterItemId)
            : filterPlayerName != null
            ? m("gui.log.title-player", "&8Log: {player}", "{player}", filterPlayerName)
            : m("gui.log.title", "&8Transaction Log");
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / ITEMS_PER_PAGE));
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, records.size());

        for (int i = start; i < end; i++) {
            TransactionRecord rec = records.get(i);
            int slot = i - start;

            // Player skull
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            OfflinePlayer off = Bukkit.getOfflinePlayer(rec.getPlayerUuid());
            meta.setOwningPlayer(off);
            meta.displayName(GuiUtils.toComponent(Messages.colorize("&f" + rec.getPlayerName())));

            String typeLabel = rec.getType().name().equals("BUY")
                ? m("gui.log.type-purchase", "&aPurchase") : m("gui.log.type-sale", "&6Sale");
            List<String> lore = new ArrayList<>();
            lore.add(m("gui.log.type-label", "&7Type: &f{type}", "{type}", typeLabel));
            lore.add(m("gui.log.item-label", "&7Item: &f{id}", "{id}", rec.getShopItemId()));
            lore.add(m("gui.log.quantity-label", "&7Quantity: &f{count}x", "{count}", String.valueOf(rec.getQuantity())));
            lore.add(m("gui.log.currency-label", "&7Currency: &f{id}", "{id}", rec.getCurrencyId()));
            lore.add(m("gui.log.price-unit", "&7Price/unit: &f{price}", "{price}", formatPrice(rec.getPricePerUnit())));
            lore.add(m("gui.log.total-label", "&7Total: &f{price}", "{price}", formatPrice(rec.getTotalPrice())));
            lore.add(m("gui.log.time-label", "&7Time: &f{time}", "{time}", DATE_FMT.format(new Date(rec.getTimestamp()))));
            meta.lore(lore.stream().map(l -> GuiUtils.toComponent(Messages.colorize(l))).collect(Collectors.toList()));
            skull.setItemMeta(meta);

            inventory.setItem(slot, skull);
        }

        if (page > 0) inventory.setItem(45, GuiUtils.makeItem(Material.ARROW,
            m("gui.log.prev-page", "&a← Previous"), List.of()));
        inventory.setItem(49, GuiUtils.makeItem(Material.PAPER,
            m("gui.log.page", "&ePage {p}/{t}", "{p}", String.valueOf(page + 1), "{t}", String.valueOf(totalPages)),
            List.of(m("gui.log.total", "&7{count} total transactions", "{count}", String.valueOf(totalRecords)))));
        if (page < totalPages - 1) inventory.setItem(53, GuiUtils.makeItem(Material.ARROW,
            m("gui.log.next-page", "&aNext →"), List.of()));
        if (onBack != null) {
            inventory.setItem(48, GuiUtils.makeItem(Material.ARROW,
                m("gui.back", "&6← Back"),
                List.of(m("gui.back-desc", "&7Return to item editor"))));
        }
        inventory.setItem(52, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of()));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        Player player = (Player) event.getWhoClicked();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / ITEMS_PER_PAGE));

        if (slot == 45 && page > 0) { page--; buildInventory(); }
        else if (slot == 53 && page < totalPages - 1) { page++; buildInventory(); }
        else if (slot == 48 && onBack != null) { player.closeInventory(); onBack.run(); return; }
        else if (slot == 52) { player.closeInventory(); return; }
        if (player.getOpenInventory().getTopInventory().getHolder() == this) {
            player.openInventory(inventory);
        }
    }

    @Override public void refresh() { buildInventory(); }
    @Override public Inventory getInventory() { return inventory; }

    private String formatPrice(double p) {
        if (p == Math.floor(p)) return String.valueOf((long) p);
        return String.format("%.2f", p);
    }
}
