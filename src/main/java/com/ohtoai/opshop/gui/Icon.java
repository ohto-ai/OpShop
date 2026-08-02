package com.ohtoai.opshop.gui;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Helper for building clickable GUI icons.
 */
public class Icon {

    private final ItemStack item;
    private Consumer<InventoryClickEvent> handler;

    public Icon(ItemStack item) {
        this.item = item.clone();
    }

    public Icon(Material material) {
        this.item = new ItemStack(material);
    }

    public Icon name(String name) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return this;
    }

    public Icon lore(List<String> loreLines) {
        ItemMeta meta = item.getItemMeta();
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return this;
    }

    public Icon amount(int amount) {
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return this;
    }

    public Icon glow(boolean glow) {
        if (glow) {
            ItemMeta meta = item.getItemMeta();
            // Paper 1.20.6: use addEnchant with ignoreLevelRestriction for glint effect
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return this;
    }

    public Icon onClick(Consumer<InventoryClickEvent> handler) {
        this.handler = handler;
        return this;
    }

    public Icon onLeftClick(Runnable action) {
        this.handler = event -> {
            if (event.isLeftClick()) action.run();
        };
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }

    public Consumer<InventoryClickEvent> getHandler() {
        return handler;
    }

    public static Icon filler(Material material) {
        return new Icon(material).name(" ");
    }

    public static Icon from(ItemStack item) {
        return new Icon(item);
    }
}
