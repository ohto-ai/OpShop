package com.ohtoai.opshop.shop;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.PluginConfig;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.database.DatabaseManager;
import com.ohtoai.opshop.item.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ShopManager {

    private final OpShopPlugin plugin;
    private final CurrencyManager currencyManager;
    private final DatabaseManager databaseManager;
    private final Map<String, ShopItem> items = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public ShopManager(OpShopPlugin plugin, CurrencyManager currencyManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.databaseManager = databaseManager;
    }

    public void loadFromConfig(PluginConfig config) {
        loadFromDatabase();
    }

    private void loadFromDatabase() {
        items.clear();
        String sql = "SELECT * FROM shop_items";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ShopItem item = new ShopItem(rs.getString("id"));
                item.setPage(getColumnInt(rs, "page"));
                item.setSlot(rs.getInt("slot"));
                item.setProductItem(rs.getBytes("product_item"));
                item.setBuyEnabled(rs.getBoolean("buy_enabled"));
                item.setSellEnabled(rs.getBoolean("sell_enabled"));
                item.setStock(rs.getInt("stock"));
                item.setMaxStock(rs.getInt("max_stock"));
                item.setCooldownSeconds(rs.getLong("cooldown_seconds"));
                item.setPermissionRequired(rs.getString("permission_required"));
                item.setApplyPlaceholders(rs.getBoolean("apply_placeholders"));
                item.setTotalLimit(rs.getInt("total_limit"));
                item.setTotalSold(getColumnInt(rs, "total_sold"));
                item.setPlayerLimit(rs.getInt("player_limit"));
                item.setRefundLimit(getColumnBoolean(rs, "refund_limit"));
                // New pricing columns (may not exist in old DB, default to 0)
                item.setVaultBuyPrice(getColumnDouble(rs, "vault_buy_price"));
                item.setVaultSellPrice(getColumnDouble(rs, "vault_sell_price"));
                item.setPpBuyPrice(getColumnDouble(rs, "pp_buy_price"));
                item.setPpSellPrice(getColumnDouble(rs, "pp_sell_price"));
                item.setCustomCurrencyId(getColumnString(rs, "custom_currency_id"));
                item.setCustomBuyPrice(getColumnDouble(rs, "custom_buy_price"));
                item.setCustomSellPrice(getColumnDouble(rs, "custom_sell_price"));
                item.setCreatedAt(rs.getLong("created_at"));
                item.setUpdatedAt(rs.getLong("updated_at"));
                items.put(item.getId(), item);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load shop items", e);
        }
        plugin.getLogger().info("Loaded " + items.size() + " shop items.");
    }

    private int getColumnInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (SQLException e) { return 0; }
    }
    private double getColumnDouble(ResultSet rs, String col) {
        try { return rs.getDouble(col); } catch (SQLException e) { return 0; }
    }
    private String getColumnString(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (SQLException e) { return null; }
    }
    private boolean getColumnBoolean(ResultSet rs, String col) {
        try { return rs.getBoolean(col); } catch (SQLException e) { return false; }
    }

    public void saveItem(ShopItem item) throws SQLException {
        String sql = databaseManager.isMySQL() ? """
            INSERT INTO shop_items (id, page, slot, category, display_item, product_item,
                use_global_defaults, buy_enabled, sell_enabled, stock, max_stock,
                cooldown_seconds, permission_required, apply_placeholders,
                total_limit, total_sold, player_limit, refund_limit,
                vault_buy_price, vault_sell_price, pp_buy_price, pp_sell_price,
                custom_currency_id, custom_buy_price, custom_sell_price,
                created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
                page=VALUES(page), slot=VALUES(slot), display_item=VALUES(display_item), product_item=VALUES(product_item),
                use_global_defaults=VALUES(use_global_defaults), buy_enabled=VALUES(buy_enabled),
                sell_enabled=VALUES(sell_enabled), stock=VALUES(stock), max_stock=VALUES(max_stock),
                cooldown_seconds=VALUES(cooldown_seconds), permission_required=VALUES(permission_required),
                apply_placeholders=VALUES(apply_placeholders), total_limit=VALUES(total_limit),
                total_sold=VALUES(total_sold), player_limit=VALUES(player_limit), refund_limit=VALUES(refund_limit),
                vault_buy_price=VALUES(vault_buy_price), vault_sell_price=VALUES(vault_sell_price),
                pp_buy_price=VALUES(pp_buy_price), pp_sell_price=VALUES(pp_sell_price),
                custom_currency_id=VALUES(custom_currency_id), custom_buy_price=VALUES(custom_buy_price),
                custom_sell_price=VALUES(custom_sell_price), updated_at=VALUES(updated_at)
            """ : """
            INSERT OR REPLACE INTO shop_items (id, page, slot, category, display_item, product_item,
                use_global_defaults, buy_enabled, sell_enabled, stock, max_stock,
                cooldown_seconds, permission_required, apply_placeholders,
                total_limit, total_sold, player_limit, refund_limit,
                vault_buy_price, vault_sell_price, pp_buy_price, pp_sell_price,
                custom_currency_id, custom_buy_price, custom_sell_price,
                created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, item.getId());
            ps.setInt(i++, item.getPage());
            ps.setInt(i++, item.getSlot());
            ps.setString(i++, ""); // category deprecated
            ps.setBytes(i++, item.getDisplayItem());
            ps.setBytes(i++, item.getProductItem());
            ps.setBoolean(i++, true); // use_global_defaults deprecated
            ps.setBoolean(i++, item.isBuyEnabled());
            ps.setBoolean(i++, item.isSellEnabled());
            ps.setInt(i++, item.getStock());
            ps.setInt(i++, item.getMaxStock());
            ps.setLong(i++, item.getCooldownSeconds());
            ps.setString(i++, item.getPermissionRequired());
            ps.setBoolean(i++, item.isApplyPlaceholders());
            ps.setInt(i++, item.getTotalLimit());
            ps.setInt(i++, item.getTotalSold());
            ps.setInt(i++, item.getPlayerLimit());
            ps.setBoolean(i++, item.isRefundLimit());
            ps.setDouble(i++, item.getVaultBuyPrice());
            ps.setDouble(i++, item.getVaultSellPrice());
            ps.setDouble(i++, item.getPpBuyPrice());
            ps.setDouble(i++, item.getPpSellPrice());
            ps.setString(i++, item.getCustomCurrencyId());
            ps.setDouble(i++, item.getCustomBuyPrice());
            ps.setDouble(i++, item.getCustomSellPrice());
            ps.setLong(i++, item.getCreatedAt());
            ps.setLong(i++, item.getUpdatedAt());
            ps.executeUpdate();
        }
        items.put(item.getId(), item);
    }

    public void deleteItem(String id) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM shop_items WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        items.remove(id);
    }

    // --- Price lookup ---

    public List<PriceConfig> getBuyPrices(ShopItem item) {
        List<PriceConfig> list = new ArrayList<>();
        // Dynamically look up the first Vault-type provider instead of hardcoding "vault_main"
        if (item.getVaultBuyPrice() > 0) {
            var vaultProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.VAULT);
            if (vaultProvider != null)
                list.add(new PriceConfig(vaultProvider, item.getVaultBuyPrice(), item.getVaultSellPrice()));
        }
        // Dynamically look up the first PlayerPoints-type provider instead of hardcoding "pp_tokens"
        if (item.getPpBuyPrice() > 0) {
            var ppProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.PLAYER_POINTS);
            if (ppProvider != null)
                list.add(new PriceConfig(ppProvider, item.getPpBuyPrice(), item.getPpSellPrice()));
        }
        if (item.getCustomCurrencyId() != null && item.getCustomBuyPrice() > 0
            && currencyManager.getProvider(item.getCustomCurrencyId()).isPresent())
            list.add(new PriceConfig(item.getCustomCurrencyId(), item.getCustomBuyPrice(), item.getCustomSellPrice()));
        return list;
    }

    public List<PriceConfig> getSellPrices(ShopItem item) {
        List<PriceConfig> list = new ArrayList<>();
        if (item.getVaultSellPrice() > 0) {
            var vaultProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.VAULT);
            if (vaultProvider != null)
                list.add(new PriceConfig(vaultProvider, item.getVaultBuyPrice(), item.getVaultSellPrice()));
        }
        if (item.getPpSellPrice() > 0) {
            var ppProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.PLAYER_POINTS);
            if (ppProvider != null)
                list.add(new PriceConfig(ppProvider, item.getPpBuyPrice(), item.getPpSellPrice()));
        }
        if (item.getCustomCurrencyId() != null && item.getCustomSellPrice() > 0
            && currencyManager.getProvider(item.getCustomCurrencyId()).isPresent())
            list.add(new PriceConfig(item.getCustomCurrencyId(), item.getCustomBuyPrice(), item.getCustomSellPrice()));
        return list;
    }

    /** Find the first registered provider of the given currency type. */
    private String findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType type) {
        for (var provider : currencyManager.getAllProviders()) {
            if (provider.getType() == type) {
                return provider.getId();
            }
        }
        return null;
    }

    // --- Item lookup ---
    public Optional<ShopItem> getItem(String id) { return Optional.ofNullable(items.get(id)); }
    public List<ShopItem> getAllItems() {
        return items.values().stream()
            .sorted(Comparator.comparingInt(ShopItem::getPage).thenComparingInt(ShopItem::getSlot))
            .toList();
    }
    public int getItemCount() { return items.size(); }
    public int getMaxPage() {
        return items.values().stream().mapToInt(ShopItem::getPage).max().orElse(0);
    }
    /** For shop display: only count pages that have items. */
    public int getMaxNonEmptyPage() {
        return items.values().stream().mapToInt(ShopItem::getPage).max().orElse(0);
    }
    public int countOnPage(int page) {
        return (int) items.values().stream().filter(i -> i.getPage() == page).count();
    }
    public int findFirstEmptySlot(int page) {
        var used = items.values().stream().filter(i -> i.getPage() == page)
            .mapToInt(ShopItem::getSlot).boxed().collect(java.util.stream.Collectors.toSet());
        for (int s = 0; s < 45; s++) if (!used.contains(s)) return s;
        return -1; // page full
    }
    public List<ShopItem> getItemsOnPage(int page) {
        return items.values().stream().filter(i -> i.getPage() == page)
            .sorted(Comparator.comparingInt(ShopItem::getSlot)).toList();
    }

    // --- Cooldowns ---
    public boolean isOnCooldown(UUID playerUuid, ShopItem item) {
        if (item.getCooldownSeconds() <= 0) return false;
        Map<String, Long> pc = cooldowns.get(playerUuid);
        if (pc == null) return false;
        Long last = pc.get(item.getId());
        if (last == null) return false;
        return System.currentTimeMillis() - last < item.getCooldownSeconds() * 1000;
    }
    public long getCooldownRemaining(UUID playerUuid, ShopItem item) {
        Map<String, Long> pc = cooldowns.get(playerUuid);
        if (pc == null) return 0;
        Long last = pc.get(item.getId());
        if (last == null) return 0;
        return Math.max(0, item.getCooldownSeconds() * 1000 - (System.currentTimeMillis() - last)) / 1000;
    }
    public void setCooldown(UUID playerUuid, ShopItem item) {
        cooldowns.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
            .put(item.getId(), System.currentTimeMillis());
    }

    // --- Player purchase tracking ---
    public int getPlayerPurchaseCount(UUID puid, String itemId) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count FROM player_purchases WHERE player_uuid=? AND shop_item_id=?")) {
            ps.setString(1, puid.toString());
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt("count"); }
        }
        return 0;
    }
    /** Reset a player's purchase count and return the old count. */
    public int resetPlayerPurchase(UUID puid, String itemId) throws SQLException {
        int oldCount = getPlayerPurchaseCount(puid, itemId);
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM player_purchases WHERE player_uuid=? AND shop_item_id=?")) {
            ps.setString(1, puid.toString());
            ps.setString(2, itemId);
            ps.executeUpdate();
        }
        return oldCount;
    }

    public void decrementPlayerPurchase(UUID puid, String itemId, int amount) throws SQLException {
        String sql = "UPDATE player_purchases SET count = MAX(0, count - ?) WHERE player_uuid = ? AND shop_item_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, puid.toString());
            ps.setString(3, itemId);
            ps.executeUpdate();
        }
    }

    public void incrementPlayerPurchase(UUID puid, String itemId, int amount) throws SQLException {
        String sql = databaseManager.isMySQL() ? """
            INSERT INTO player_purchases (player_uuid, shop_item_id, count) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE count = count + VALUES(count)
            """ : """
            INSERT INTO player_purchases (player_uuid, shop_item_id, count) VALUES (?,?,?)
            ON CONFLICT(player_uuid, shop_item_id) DO UPDATE SET count = count + excluded.count
            """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, puid.toString());
            ps.setString(2, itemId);
            ps.setInt(3, amount);
            ps.executeUpdate();
        }
    }
}
