package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.database.TransactionRecord;
import com.ohtoai.opshop.item.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Simple GUI showing trace record details.
 * 3-row inventory showing item snapshot and transaction info.
 */
public class TraceViewGui implements GuiHolder {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final OpShopPlugin plugin;
    private final TransactionRecord record;
    private Inventory inventory;

    private static final int ROWS = 3;
    private static final int SLOT_ITEM = 4;      // item snapshot
    private static final int SLOT_INFO = 13;      // details
    private static final int SLOT_CLOSE = 22;     // close button

    private String m(String k, String e, String... r) { return plugin.getMessages().get(k, e, r); }

    public TraceViewGui(OpShopPlugin plugin, TransactionRecord record) {
        this.plugin = plugin;
        this.record = record;
    }

    public void open(Player player) {
        buildInventory();
        player.openInventory(inventory);
    }

    private void buildInventory() {
        this.inventory = GuiUtils.createInventory(this, ROWS,
            m("trace.view.title", "&8Trace: #" + record.getId()));

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        // Item snapshot
        ItemStack snapshot = ItemSerializer.deserialize(record.getItemSnapshot());
        if (snapshot != null && snapshot.getType() != Material.AIR) {
            inventory.setItem(SLOT_ITEM, snapshot);
        }

        // Info paper
        String curName = plugin.getCurrencyManager().getProvider(record.getCurrencyId())
            .map(CurrencyProvider::getDisplayName).orElse(record.getCurrencyId());
        List<String> lore = List.of(
            Messages.colorize(m("trace.view.player", "&7Buyer: &f{player}",
                "{player}", record.getPlayerName())),
            Messages.colorize(m("trace.view.item", "&7Item: &f{item}",
                "{item}", record.getShopItemId())),
            Messages.colorize(m("trace.view.type", "&7Type: &f{type}",
                "{type}", record.getType().name())),
            Messages.colorize(m("trace.view.quantity", "&7Quantity: &f{count}x",
                "{count}", String.valueOf(record.getQuantity()))),
            Messages.colorize(m("trace.view.price-unit", "&7Price/unit: &f{price} {currency}",
                "{price}", formatPrice(record.getPricePerUnit()), "{currency}", curName)),
            Messages.colorize(m("trace.view.total", "&7Total: &f{price} {currency}",
                "{price}", formatPrice(record.getTotalPrice()), "{currency}", curName)),
            Messages.colorize(m("trace.view.time", "&7Time: &f{time}",
                "{time}", DATE_FORMAT.format(new Date(record.getTimestamp())))),
            Messages.colorize(m("trace.view.id", "&7Trace ID: &f{id}",
                "{id}", String.valueOf(record.getId())))
        );
        ItemStack info = GuiUtils.makeItem(Material.PAPER,
            m("trace.view.info-title", "&eTrace #" + record.getId()), lore);
        inventory.setItem(SLOT_INFO, info);

        // Close button
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of(m("gui.close-desc", "&7Close"))));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.getSlot() == SLOT_CLOSE) {
            event.getWhoClicked().closeInventory();
        }
    }

    @Override
    public void refresh() { buildInventory(); }

    @Override
    public Inventory getInventory() { return inventory; }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
