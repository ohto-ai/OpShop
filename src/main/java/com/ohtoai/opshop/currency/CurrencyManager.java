package com.ohtoai.opshop.currency;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.PluginConfig;
import com.ohtoai.opshop.config.PluginConfig.CurrencyDef;
import com.ohtoai.opshop.currency.provider.CustomItemCurrencyProvider;
import com.ohtoai.opshop.currency.provider.PlayerPointsCurrencyProvider;
import com.ohtoai.opshop.currency.provider.VaultCurrencyProvider;
import com.ohtoai.opshop.database.DatabaseManager;
import com.ohtoai.opshop.integration.HookManager;
import com.ohtoai.opshop.item.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class CurrencyManager {

    private final OpShopPlugin plugin;
    private final HookManager hookManager;
    private final Map<String, CurrencyProvider> providers = new LinkedHashMap<>();
    private final Map<String, CurrencyDef> definitions = new LinkedHashMap<>();
    private final List<Map<String, Object>> globalDefaults = new ArrayList<>();

    public CurrencyManager(OpShopPlugin plugin, HookManager hookManager) {
        this.plugin = plugin;
        this.hookManager = hookManager;
    }

    public void loadFromConfig(PluginConfig config) {
        providers.clear();
        definitions.clear();
        globalDefaults.clear();

        globalDefaults.addAll(config.getGlobalDefaultPrices());

        // Load from config.yml — only VAULT and PLAYER_POINTS (custom items come from DB)
        for (CurrencyDef def : config.getCurrencyDefs().values()) {
            if (!"CUSTOM_ITEM".equalsIgnoreCase(def.type())) {
                definitions.put(def.id(), def);
            }
        }

        // Load custom currencies from DB
        loadFromDatabase();

        // Initialize providers
        for (CurrencyDef def : definitions.values()) {
            try {
                CurrencyProvider provider = createProvider(def);
                if (provider != null) {
                    providers.put(def.id(), provider);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to init currency: " + def.id(), e);
            }
        }

        plugin.getLogger().info("Loaded " + providers.size() + " currencies ("
            + definitions.size() + " defined)");
    }

    private void loadFromDatabase() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) return;

        String sql = "SELECT * FROM currencies";
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("id");
                String type = rs.getString("type");
                String name = rs.getString("name");
                byte[] itemData = rs.getBytes("item_data");

                // DB definitions override config.yml definitions
                if (itemData != null && itemData.length > 0) {
                    ItemStack item = ItemSerializer.deserialize(itemData);
                    if (item != null) {
                        ItemMeta meta = item.getItemMeta();
                        String displayName = meta.hasDisplayName()
                            ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().serialize(meta.displayName())
                            : name;
                        List<String> lore = meta.hasLore()
                            ? meta.lore().stream()
                                .map(c -> net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .legacySection().serialize(c))
                                .toList()
                            : List.of();

                        CurrencyDef def = new CurrencyDef(id, type, name, name,
                            item.getType().name(), displayName, lore,
                            meta.hasCustomModelData() ? meta.getCustomModelData() : 0);
                        definitions.put(id, def);
                    }
                } else {
                    definitions.putIfAbsent(id, new CurrencyDef(id, type, name, name));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load currencies from DB", e);
        }
    }

    /**
     * Add a custom-item currency from a held item. Persists to database.
     * The display name is derived from the item's own NBT.
     */
    public boolean addCustomCurrency(String id, ItemStack heldItem) {
        if (definitions.containsKey(id)) {
            return false; // duplicate
        }

        // Store the original item as-is — no extra PDC tag injected
        ItemStack currencyItem = heldItem.clone();
        currencyItem.setAmount(1);
        ItemMeta meta = currencyItem.getItemMeta();

        byte[] itemData = ItemSerializer.serialize(currencyItem);
        String materialName = currencyItem.getType().name();

        // Derive display name from item's own NBT
        String displayName = meta.hasDisplayName()
            ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(meta.displayName())
            : materialName;

        CurrencyDef def = new CurrencyDef(id, "CUSTOM_ITEM", displayName, displayName,
            materialName,
            meta.hasDisplayName()
                ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(meta.displayName())
                : "",
            meta.hasLore()
                ? meta.lore().stream()
                    .map(c -> net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().serialize(c))
                    .toList()
                : List.of(),
            meta.hasCustomModelData() ? meta.getCustomModelData() : 0);

        definitions.put(id, def);

        // Persist to DB
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null) {
            String sql = db.isMySQL()
                ? "INSERT INTO currencies (id, type, name, item_data, created_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE type=VALUES(type), name=VALUES(name), item_data=VALUES(item_data)"
                : "INSERT OR REPLACE INTO currencies (id, type, name, item_data, created_at) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, "CUSTOM_ITEM");
                ps.setString(3, displayName);
                ps.setBytes(4, itemData);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save currency to DB", e);
            }
        }

        // Create provider
        CustomItemCurrencyProvider provider = new CustomItemCurrencyProvider(def, plugin);
        providers.put(id, provider);

        // Also add to global defaults automatically
        Map<String, Object> entry = new HashMap<>();
        entry.put("currency", id);
        entry.put("buy-price", 1.0);
        entry.put("sell-price", 1.0);
        globalDefaults.add(entry);

        plugin.getLogger().info("Added custom currency: " + id + " (" + displayName + ")");
        return true;
    }

    /**
     * Delete a custom-item currency.
     */
    public boolean deleteCurrency(String id) {
        CurrencyDef def = definitions.get(id);
        if (def == null) return false;
        if (!"CUSTOM_ITEM".equalsIgnoreCase(def.type())) {
            return false; // Can only delete custom-item currencies
        }

        definitions.remove(id);
        providers.remove(id);

        // Remove from global defaults
        globalDefaults.removeIf(e -> id.equals(e.get("currency")));

        // Remove from DB
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null) {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM currencies WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete currency from DB", e);
            }
        }

        plugin.getLogger().info("Deleted custom currency: " + id);
        return true;
    }

    private CurrencyProvider createProvider(CurrencyDef def) {
        return switch (def.type().toUpperCase()) {
            case "VAULT" -> hookManager.isVaultEnabled()
                ? new VaultCurrencyProvider(def, hookManager.getVaultHook()) : null;
            case "PLAYER_POINTS" -> hookManager.isPlayerPointsEnabled()
                ? new PlayerPointsCurrencyProvider(def, hookManager.getPlayerPointsHook()) : null;
            case "CUSTOM_ITEM" -> new CustomItemCurrencyProvider(def, plugin);
            default -> null;
        };
    }

    // --- Accessors ---

    public Optional<CurrencyProvider> getProvider(String currencyId) {
        return Optional.ofNullable(providers.get(currencyId));
    }

    public Collection<CurrencyProvider> getAllProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    public Set<String> getCurrencyIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    public CurrencyDef getDefinition(String currencyId) {
        return definitions.get(currencyId);
    }

    public Map<String, CurrencyDef> getDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public List<Map<String, Object>> getGlobalDefaults() {
        return globalDefaults;
    }

    public List<String> getAvailableCurrencyIds() {
        return new ArrayList<>(providers.keySet());
    }

    /**
     * Update global default for a currency. Adds if not present, updates if exists.
     */
    public void setGlobalDefault(String currencyId, double buyPrice, double sellPrice) {
        for (Map<String, Object> entry : globalDefaults) {
            if (currencyId.equals(entry.get("currency"))) {
                entry.put("buy-price", buyPrice);
                entry.put("sell-price", sellPrice);
                return;
            }
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("currency", currencyId);
        entry.put("buy-price", buyPrice);
        entry.put("sell-price", sellPrice);
        globalDefaults.add(entry);
    }

    public void removeGlobalDefault(String currencyId) {
        globalDefaults.removeIf(e -> currencyId.equals(e.get("currency")));
    }

    /**
     * Apply global default prices to a newly created ShopItem.
     * Only VAULT and PLAYER_POINTS get auto-applied; custom currency
     * stays "none" until the admin explicitly selects one in the editor.
     */
    public void applyGlobalDefaults(com.ohtoai.opshop.shop.ShopItem item) {
        for (Map<String, Object> entry : globalDefaults) {
            String currencyId = (String) entry.get("currency");
            double buyPrice = ((Number) entry.get("buy-price")).doubleValue();
            double sellPrice = ((Number) entry.get("sell-price")).doubleValue();

            CurrencyDef def = definitions.get(currencyId);
            if (def == null) continue;

            switch (def.type().toUpperCase()) {
                case "VAULT" -> {
                    item.setVaultBuyPrice(buyPrice);
                    item.setVaultSellPrice(sellPrice);
                }
                case "PLAYER_POINTS" -> {
                    item.setPpBuyPrice(buyPrice);
                    item.setPpSellPrice(sellPrice);
                }
                // CUSTOM_ITEM: intentionally skipped — admin must manually select it
                default -> {}
            }
        }
    }
}
