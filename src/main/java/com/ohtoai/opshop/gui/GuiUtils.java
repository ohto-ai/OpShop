package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers for GUI construction.
 */
public final class GuiUtils {

    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.legacySection();

    private GuiUtils() {}

    /**
     * Convert a colorized string (& or § codes) to an Adventure Component.
     * This properly interprets legacy color codes as formatting.
     */
    public static Component toComponent(String colorizedText) {
        return LEGACY.deserialize(colorizedText);
    }

    /**
     * Create a named inventory with a GuiHolder that routes clicks.
     */
    public static Inventory createInventory(GuiHolder holder, int rows, String title) {
        String colored = Messages.colorize(title);
        int size = Math.max(1, Math.min(rows, 6)) * 9;
        return Bukkit.createInventory(holder, size, toComponent(colored));
    }

    /**
     * Fill an inventory with filler items.
     */
    public static void fill(Inventory inv, Material fillerMaterial) {
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, filler.clone());
            }
        }
    }

    /**
     * Fill the border of an inventory.
     */
    public static void fillBorder(Inventory inv, Material borderMaterial) {
        ItemStack border = new ItemStack(borderMaterial);
        ItemMeta meta = border.getItemMeta();
        meta.displayName(Component.empty());
        border.setItemMeta(meta);
        int rows = inv.getSize() / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                    inv.setItem(row * 9 + col, border.clone());
                }
            }
        }
    }

    /**
     * Build a simple named item.
     */
    public static ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(toComponent(Messages.colorize(name)));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream()
                .map(l -> toComponent(Messages.colorize(l)))
                .toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Add price lore to an existing ItemStack.
     */
    public static ItemStack addPriceLore(ItemStack item, String currencyName, double buyPrice, double sellPrice) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        if (buyPrice > 0) {
            lore.add(toComponent(Messages.colorize("&eBuy: &f" + formatPrice(buyPrice) + " " + currencyName)));
        }
        if (sellPrice > 0) {
            lore.add(toComponent(Messages.colorize("&eSell: &f" + formatPrice(sellPrice) + " " + currencyName)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }
}
