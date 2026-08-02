package com.ohtoai.opshop.currency.provider;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.currency.CurrencyType;
import com.ohtoai.opshop.item.ItemSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class CustomItemCurrencyProvider implements CurrencyProvider {

    // Only used to strip legacy PDC tag from old items; no longer injected.
    private static final NamespacedKey CURRENCY_KEY = new NamespacedKey("opshop", "currency_id");

    private final CurrencyDef def;
    private final OpShopPlugin plugin;
    private ItemStack templateItem;
    private byte[] templateSerialized; // cached normalized bytes for matching

    public CustomItemCurrencyProvider(CurrencyDef def, OpShopPlugin plugin) {
        this.def = def;
        this.plugin = plugin;
        loadTemplate();
    }

    private void loadTemplate() {
        // Try loading from DB (preserves all original NBT)
        var db = plugin.getDatabaseManager();
        if (db != null) {
            try (var conn = db.getConnection();
                 var ps = conn.prepareStatement("SELECT item_data FROM currencies WHERE id = ?")) {
                ps.setString(1, def.id());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] data = rs.getBytes("item_data");
                        if (data != null && data.length > 0) {
                            this.templateItem = ItemSerializer.deserialize(data);
                            if (this.templateItem != null) {
                                // Strip legacy PDC tag — no longer injected into currency items
                                ItemMeta meta = templateItem.getItemMeta();
                                meta.getPersistentDataContainer().remove(CURRENCY_KEY);
                                templateItem.setItemMeta(meta);
                                templateItem.setAmount(1);
                                this.templateSerialized = ItemSerializer.serialize(templateItem);
                                return;
                            }
                        }
                    }
                }
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Failed to load currency item from DB: " + e.getMessage());
            }
        }

        // Fallback: build from config strings
        Material mat = Material.matchMaterial(def.customMaterial());
        if (mat == null) mat = Material.STONE;
        this.templateItem = new ItemStack(mat);
        ItemMeta meta = templateItem.getItemMeta();
        if (def.customName() != null && !def.customName().isEmpty()) {
            try {
                meta.displayName(MiniMessage.miniMessage().deserialize(
                    Messages.colorize(def.customName()).replace('§', '&')));
            } catch (Exception e) {
                meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(Messages.colorize(def.customName())));
            }
        }
        if (def.customLore() != null && !def.customLore().isEmpty()) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : def.customLore()) {
                try {
                    lore.add(MiniMessage.miniMessage().deserialize(
                        Messages.colorize(line).replace('§', '&')));
                } catch (Exception e) {
                    lore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(Messages.colorize(line)));
                }
            }
            meta.lore(lore);
        }
        if (def.customModelData() > 0) meta.setCustomModelData(def.customModelData());
        templateItem.setItemMeta(meta);
        templateItem.setAmount(1);
        this.templateSerialized = ItemSerializer.serialize(templateItem);
    }

    /**
     * Creates a fresh stack of currency items identical to the original registered item.
     */
    public ItemStack createCurrencyItem(int amount) {
        ItemStack item = templateItem.clone();
        item.setAmount(Math.min(amount, item.getMaxStackSize()));
        return item;
    }

    /**
     * Checks whether an ItemStack matches this custom currency.
     * Uses serialized byte comparison after normalizing amount and stripping legacy PDC.
     */
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != templateItem.getType()) return false;
        if (templateSerialized == null) return false;

        // Normalize the checked item: amount=1, strip legacy PDC
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        if (normalized.hasItemMeta()) {
            ItemMeta meta = normalized.getItemMeta();
            meta.getPersistentDataContainer().remove(CURRENCY_KEY);
            normalized.setItemMeta(meta);
        }

        return Arrays.equals(templateSerialized, ItemSerializer.serialize(normalized));
    }

    @Override
    public String getId() {
        return def.id();
    }

    @Override
    public String getDisplayName() {
        return def.displayName();
    }

    @Override
    public String getDisplaySingular() {
        return def.displaySingular();
    }

    @Override
    public CurrencyType getType() {
        return CurrencyType.CUSTOM_ITEM;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (!(player instanceof Player p) || !p.isOnline()) return 0;
        return countMatchingItems(p);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (!(player instanceof Player p) || !p.isOnline()) return false;
        return countMatchingItems(p) >= amount;
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        int needed = (int) Math.ceil(amount);
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && removed < needed; i++) {
            ItemStack item = contents[i];
            if (item == null || !matches(item)) continue;
            int inStack = item.getAmount();
            int toTake = Math.min(inStack, needed - removed);
            item.setAmount(inStack - toTake);
            contents[i] = item.getAmount() <= 0 ? null : item;
            removed += toTake;
        }
        if (removed < needed) return false;
        player.getInventory().setContents(contents);
        player.updateInventory();
        return true;
    }

    @Override
    public boolean deposit(Player player, double amount) {
        int count = (int) Math.ceil(amount);
        if (count <= 0) return true;

        int maxStack = templateItem.getMaxStackSize();
        List<ItemStack> toGive = new ArrayList<>();

        int remaining = count;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStack);
            ItemStack stack = createCurrencyItem(stackSize);
            toGive.add(stack);
            remaining -= stackSize;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive.toArray(new ItemStack[0]));
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        return true;
    }

    @Override
    public String format(double amount) {
        int count = (int) Math.ceil(amount);
        return count + " " + (count == 1 ? getDisplaySingular() : getDisplayName());
    }

    private int countMatchingItems(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && matches(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }
}
