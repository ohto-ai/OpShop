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
    /** Per-page titles (0-indexed page → raw title with & color codes). */
    private final Map<Integer, String> pageTitles = new ConcurrentHashMap<>();

    public ShopManager(OpShopPlugin plugin, CurrencyManager currencyManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
        this.databaseManager = databaseManager;
    }

    public void loadFromConfig(PluginConfig config) {
        loadFromDatabase();
    }

    /** Reload all items from database (used by cross-server sync). */
    public void reloadFromDatabase() {
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
                item.setGenerateUniqueId(getColumnBoolean(rs, "generate_unique_id"));
                item.setGuildRestrictionEnabled(getColumnBoolean(rs, "guild_restriction_enabled"));
                item.setBroadcastOnTrade(getColumnBoolean(rs, "broadcast_on_trade"));
                item.setAnnouncedAt(getColumnLong(rs, "announced_at"));
                // Price columns (may not exist in old DB, default to 0) — stored as integer cents
                item.setVaultBuyPrice(getColumnCents(rs, "vault_buy_price"));
                item.setVaultSellPrice(getColumnCents(rs, "vault_sell_price"));
                item.setPpBuyPrice(getColumnCents(rs, "pp_buy_price"));
                item.setPpSellPrice(getColumnCents(rs, "pp_sell_price"));
                item.setCustomCurrencyId(getColumnString(rs, "custom_currency_id"));
                item.setCustomBuyPrice(getColumnCents(rs, "custom_buy_price"));
                item.setCustomSellPrice(getColumnCents(rs, "custom_sell_price"));
                item.setCreatedAt(rs.getLong("created_at"));
                item.setUpdatedAt(rs.getLong("updated_at"));
                items.put(item.getId(), item);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load shop items", e);
        }
        // Load guild whitelists from shop_item_guilds
        loadGuildWhitelists();
        // Load custom currency prices from shop_item_prices
        loadCustomCurrencyPrices();
        // Load per-page titles
        loadPageTitles();
        // Migrate legacy single custom currency to new multi-currency system
        migrateLegacyCustomCurrency();
        plugin.getLogger().info("Loaded " + items.size() + " shop items.");
    }

    private void loadGuildWhitelists() {
        String sql = "SELECT shop_item_id, guild_id FROM shop_item_guilds";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            Map<String, Set<Integer>> guildMap = new HashMap<>();
            while (rs.next()) {
                guildMap.computeIfAbsent(rs.getString("shop_item_id"), k -> new HashSet<>())
                    .add(rs.getInt("guild_id"));
            }
            for (var entry : guildMap.entrySet()) {
                ShopItem item = items.get(entry.getKey());
                if (item != null) item.setAllowedGuildIds(entry.getValue());
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load guild whitelists", e);
        }
    }

    /** Load per-page titles from shop_page_titles. */
    private void loadPageTitles() {
        pageTitles.clear();
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT page, title FROM shop_page_titles")) {
            while (rs.next()) {
                pageTitles.put(rs.getInt("page"), rs.getString("title"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load page titles", e);
        }
    }

    /** Load custom currency prices from shop_item_prices table and apply to items. */
    private void loadCustomCurrencyPrices() {
        String sql = "SELECT shop_item_id, currency_id, buy_price, sell_price FROM shop_item_prices";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String itemId = rs.getString("shop_item_id");
                String currencyId = rs.getString("currency_id");
                double buyPrice = rs.getLong("buy_price") / 100.0;
                double sellPrice = rs.getLong("sell_price") / 100.0;
                ShopItem item = items.get(itemId);
                if (item != null && currencyManager.getProvider(currencyId).isPresent()) {
                    item.setCustomCurrencyPrice(currencyId, buyPrice, sellPrice);
                }
            }
            plugin.getLogger().info("Loaded custom currency prices for " + items.size() + " items.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load custom currency prices", e);
        }
    }

    /** Migrate legacy single custom currency to the new map-based system. */
    private void migrateLegacyCustomCurrency() {
        for (ShopItem item : items.values()) {
            String legacyId = item.getCustomCurrencyId();
            if (legacyId != null && !legacyId.isEmpty()
                && item.getCustomCurrencyPrices().isEmpty()) {
                if (item.getCustomBuyPrice() > 0 || item.getCustomSellPrice() > 0) {
                    item.setCustomCurrencyPrice(legacyId, item.getCustomBuyPrice(), item.getCustomSellPrice());
                    plugin.getLogger().info("Migrated legacy custom currency for " + item.getId() + ": " + legacyId);
                }
            }
        }
    }

    private int getColumnInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (SQLException e) { return 0; }
    }
    private long getColumnLong(ResultSet rs, String col) {
        try { return rs.getLong(col); } catch (SQLException e) { return 0; }
    }
    /** Read a price column stored as integer cents. */
    private double getColumnCents(ResultSet rs, String col) {
        return getColumnLong(rs, col) / 100.0;
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
                total_limit, total_sold, player_limit, refund_limit, generate_unique_id,
                guild_restriction_enabled, broadcast_on_trade, announced_at,
                vault_buy_price, vault_sell_price, pp_buy_price, pp_sell_price,
                custom_currency_id, custom_buy_price, custom_sell_price,
                created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
                page=VALUES(page), slot=VALUES(slot), display_item=VALUES(display_item), product_item=VALUES(product_item),
                use_global_defaults=VALUES(use_global_defaults), buy_enabled=VALUES(buy_enabled),
                sell_enabled=VALUES(sell_enabled), stock=VALUES(stock), max_stock=VALUES(max_stock),
                cooldown_seconds=VALUES(cooldown_seconds), permission_required=VALUES(permission_required),
                apply_placeholders=VALUES(apply_placeholders), total_limit=VALUES(total_limit),
                total_sold=VALUES(total_sold), player_limit=VALUES(player_limit), refund_limit=VALUES(refund_limit),
                generate_unique_id=VALUES(generate_unique_id),
                guild_restriction_enabled=VALUES(guild_restriction_enabled),
                broadcast_on_trade=VALUES(broadcast_on_trade),
                announced_at=VALUES(announced_at),
                vault_buy_price=VALUES(vault_buy_price), vault_sell_price=VALUES(vault_sell_price),
                pp_buy_price=VALUES(pp_buy_price), pp_sell_price=VALUES(pp_sell_price),
                custom_currency_id=VALUES(custom_currency_id), custom_buy_price=VALUES(custom_buy_price),
                custom_sell_price=VALUES(custom_sell_price), updated_at=VALUES(updated_at)
            """ : """
            INSERT OR REPLACE INTO shop_items (id, page, slot, category, display_item, product_item,
                use_global_defaults, buy_enabled, sell_enabled, stock, max_stock,
                cooldown_seconds, permission_required, apply_placeholders,
                total_limit, total_sold, player_limit, refund_limit, generate_unique_id,
                guild_restriction_enabled, broadcast_on_trade, announced_at,
                vault_buy_price, vault_sell_price, pp_buy_price, pp_sell_price,
                custom_currency_id, custom_buy_price, custom_sell_price,
                created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            ps.setBoolean(i++, item.isGenerateUniqueId());
            ps.setBoolean(i++, item.isGuildRestrictionEnabled());
            ps.setBoolean(i++, item.isBroadcastOnTrade());
            ps.setLong(i++, item.getAnnouncedAt());
            ps.setLong(i++, PriceUtil.toCents(item.getVaultBuyPrice()));
            ps.setLong(i++, PriceUtil.toCents(item.getVaultSellPrice()));
            ps.setLong(i++, PriceUtil.toCents(item.getPpBuyPrice()));
            ps.setLong(i++, PriceUtil.toCents(item.getPpSellPrice()));
            ps.setString(i++, item.getCustomCurrencyId());
            ps.setLong(i++, PriceUtil.toCents(item.getCustomBuyPrice()));
            ps.setLong(i++, PriceUtil.toCents(item.getCustomSellPrice()));
            ps.setLong(i++, item.getCreatedAt());
            ps.setLong(i++, item.getUpdatedAt());
            ps.executeUpdate();
        }
        items.put(item.getId(), item);

        // Save custom currency prices to shop_item_prices table
        saveCustomCurrencyPrices(item);

        // Notify other servers behind BungeeCord to reload
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    public void deleteItem(String id) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            // Delete guild whitelist entries first (FK constraint)
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shop_item_guilds WHERE shop_item_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            // Delete custom currency prices
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shop_item_prices WHERE shop_item_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            // Delete player purchase records
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM player_purchases WHERE shop_item_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            // Delete the item itself
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shop_items WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
        items.remove(id);

        // Notify other servers behind BungeeCord to reload
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    /**
     * Persist the guild whitelist for an item.
     * Replaces all existing entries (DELETE + batch INSERT).
     */
    public void saveItemGuilds(String itemId, Set<Integer> guildIds) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            // Delete existing
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shop_item_guilds WHERE shop_item_id = ?")) {
                ps.setString(1, itemId);
                ps.executeUpdate();
            }
            // Insert new
            if (guildIds != null && !guildIds.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO shop_item_guilds (shop_item_id, guild_id) VALUES (?, ?)")) {
                    for (int gid : guildIds) {
                        ps.setString(1, itemId);
                        ps.setInt(2, gid);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
        // Notify other servers behind BungeeCord to reload
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    /** Persist custom currency prices for an item to the shop_item_prices table. */
    private void saveCustomCurrencyPrices(ShopItem item) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            // Delete existing custom currency prices for this item
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shop_item_prices WHERE shop_item_id = ?")) {
                ps.setString(1, item.getId());
                ps.executeUpdate();
            }
            // Insert current custom currency prices (skip deleted currencies)
            var prices = item.getCustomCurrencyPrices();
            if (!prices.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO shop_item_prices (shop_item_id, currency_id, buy_price, sell_price) VALUES (?, ?, ?, ?)")) {
                    for (var entry : prices.entrySet()) {
                        if (currencyManager.getProvider(entry.getKey()).isEmpty()) continue;
                        ps.setString(1, item.getId());
                        ps.setString(2, entry.getKey());
                        ps.setLong(3, PriceUtil.toCents(entry.getValue().buyPrice()));
                        ps.setLong(4, PriceUtil.toCents(entry.getValue().sellPrice()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
    }

    // --- Price lookup ---

    public List<PriceConfig> getBuyPrices(ShopItem item) {
        List<PriceConfig> list = new ArrayList<>();
        // Vault
        if (item.getVaultBuyPrice() > 0) {
            var vaultProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.VAULT);
            if (vaultProvider != null)
                list.add(new PriceConfig(vaultProvider, item.getVaultBuyPrice(), item.getVaultSellPrice()));
        }
        // PlayerPoints
        if (item.getPpBuyPrice() > 0) {
            var ppProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.PLAYER_POINTS);
            if (ppProvider != null)
                list.add(new PriceConfig(ppProvider, item.getPpBuyPrice(), item.getPpSellPrice()));
        }
        // All custom currencies with buy price > 0
        for (var entry : item.getCustomCurrencyPrices().entrySet()) {
            if (entry.getValue().buyPrice() > 0
                && currencyManager.getProvider(entry.getKey()).isPresent()) {
                list.add(new PriceConfig(entry.getKey(), entry.getValue().buyPrice(), entry.getValue().sellPrice()));
            }
        }
        return list;
    }

    public List<PriceConfig> getSellPrices(ShopItem item) {
        List<PriceConfig> list = new ArrayList<>();
        // Vault
        if (item.getVaultSellPrice() > 0) {
            var vaultProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.VAULT);
            if (vaultProvider != null)
                list.add(new PriceConfig(vaultProvider, item.getVaultBuyPrice(), item.getVaultSellPrice()));
        }
        // PlayerPoints
        if (item.getPpSellPrice() > 0) {
            var ppProvider = findFirstProviderByType(com.ohtoai.opshop.currency.CurrencyType.PLAYER_POINTS);
            if (ppProvider != null)
                list.add(new PriceConfig(ppProvider, item.getPpBuyPrice(), item.getPpSellPrice()));
        }
        // All custom currencies with sell price > 0
        for (var entry : item.getCustomCurrencyPrices().entrySet()) {
            if (entry.getValue().sellPrice() > 0
                && currencyManager.getProvider(entry.getKey()).isPresent()) {
                list.add(new PriceConfig(entry.getKey(), entry.getValue().buyPrice(), entry.getValue().sellPrice()));
            }
        }
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

    /** Get all items that have generateUniqueId enabled (for trace tab completion). */
    public List<ShopItem> getTraceEnabledItems() {
        return items.values().stream()
            .filter(ShopItem::isGenerateUniqueId)
            .toList();
    }

    // --- New-item announcements ---

    /**
     * Get items the given player has not been notified about yet:
     * announcedAt newer than the player's seen watermark, and visible to them
     * (guild-restricted items only count when the player is in an allowed
     * guild). Mirrors the shop listing, which also shows not-yet-tradeable items.
     */
    public List<ShopItem> getNewItemsFor(UUID playerUuid) {
        long seenAt = getNewItemsSeenAt(playerUuid);

        var hook = plugin.getHookManager();
        Integer playerGuildId = null;
        if (hook.isGuildEnabled()) {
            var guild = hook.getGuildBridge().getPlayerGuild(playerUuid);
            if (guild != null) playerGuildId = guild.id();
        }
        final Integer gid = playerGuildId;

        return items.values().stream()
            .filter(i -> i.getAnnouncedAt() > 0 && i.getAnnouncedAt() > seenAt)
            .filter(i -> {
                if (!i.isGuildRestricted()) return true;
                return gid != null && i.getAllowedGuildIds().contains(gid);
            })
            .sorted(Comparator.comparingLong(ShopItem::getAnnouncedAt))
            .toList();
    }

    /** The player's seen watermark (0 = never notified). */
    public long getNewItemsSeenAt(UUID playerUuid) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT seen_at FROM player_new_item_seen WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read new-item watermark", e);
        }
        return 0;
    }

    /**
     * Advance the player's seen watermark, never moving it backwards
     * (a player notified live of one item still gets older unseen items on join).
     */
    public void markNewItemsSeen(UUID playerUuid, long seenAt) {
        String sql = databaseManager.isMySQL()
            ? "INSERT INTO player_new_item_seen (player_uuid, seen_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE seen_at = GREATEST(seen_at, VALUES(seen_at))"
            : "INSERT INTO player_new_item_seen (player_uuid, seen_at) VALUES (?, ?) ON CONFLICT(player_uuid) DO UPDATE SET seen_at = MAX(seen_at, excluded.seen_at)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, seenAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update new-item watermark", e);
        }
    }

    /**
     * Re-announce an item: bump its announce marker in DB and in memory.
     * Uses a targeted UPDATE so unrelated unsaved editor changes are not persisted.
     */
    public void setAnnouncedAt(String itemId, long at) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE shop_items SET announced_at = ? WHERE id = ?")) {
            ps.setLong(1, at);
            ps.setString(2, itemId);
            ps.executeUpdate();
        }
        ShopItem item = items.get(itemId);
        if (item != null) item.setAnnouncedAt(at);
        // Notify other servers behind BungeeCord to reload
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    // --- Page titles ---

    /** The title for a page (raw &-codes), or null when unset. */
    public String getPageTitle(int page) {
        return pageTitles.get(page);
    }

    /** Set a page title. Passing null or an empty title deletes it. */
    public void setPageTitle(int page, String title) throws SQLException {
        if (title == null || title.isEmpty()) {
            deletePageTitle(page);
            return;
        }
        String sql = databaseManager.isMySQL()
            ? "INSERT INTO shop_page_titles (page, title) VALUES (?, ?) ON DUPLICATE KEY UPDATE title = VALUES(title)"
            : "INSERT OR REPLACE INTO shop_page_titles (page, title) VALUES (?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, page);
            ps.setString(2, title);
            ps.executeUpdate();
        }
        pageTitles.put(page, title);
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    /** Remove a page's title (no-op when unset). */
    public void deletePageTitle(int page) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM shop_page_titles WHERE page = ?")) {
            ps.setInt(1, page);
            ps.executeUpdate();
        }
        pageTitles.remove(page);
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
    }

    /**
     * Shift all titles on pages >= fromPage by delta, so titles follow their
     * pages when pages are inserted or deleted. Titles that would move below
     * page 0 are dropped.
     */
    public void shiftPageTitlesFrom(int fromPage, int delta) throws SQLException {
        List<Map.Entry<Integer, String>> moved = pageTitles.entrySet().stream()
            .filter(e -> e.getKey() >= fromPage)
            .toList();
        if (moved.isEmpty()) return;

        try (Connection conn = databaseManager.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                         "DELETE FROM shop_page_titles WHERE page >= ?")) {
                    del.setInt(1, fromPage);
                    del.executeUpdate();
                }
                String insertSql = databaseManager.isMySQL()
                    ? "INSERT INTO shop_page_titles (page, title) VALUES (?, ?) ON DUPLICATE KEY UPDATE title = VALUES(title)"
                    : "INSERT OR REPLACE INTO shop_page_titles (page, title) VALUES (?, ?)";
                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    for (var entry : moved) {
                        int newPage = entry.getKey() + delta;
                        if (newPage < 0) continue;
                        ins.setInt(1, newPage);
                        ins.setString(2, entry.getValue());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        }
        // Reload the in-memory map to match the shifted DB state
        loadPageTitles();
        OpShopPlugin.getInstance().sendBungeeSyncNotification();
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
    public void clearCooldowns(UUID playerUuid) {
        cooldowns.remove(playerUuid);
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
        // Read current count first — SQLite doesn't support MAX() in UPDATE SET
        int current = getPlayerPurchaseCount(puid, itemId);
        int newCount = Math.max(0, current - amount);
        String sql = databaseManager.isMySQL()
            ? "UPDATE player_purchases SET count = GREATEST(0, count - ?) WHERE player_uuid = ? AND shop_item_id = ?"
            : "UPDATE player_purchases SET count = ? WHERE player_uuid = ? AND shop_item_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (databaseManager.isMySQL()) {
                ps.setInt(1, amount);
            } else {
                ps.setInt(1, newCount);
            }
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

    /** Get all purchase counts for a player (itemId → count), for admin inspection / GUIs. */
    public Map<String, Integer> getAllPlayerPurchases(UUID puid) throws SQLException {
        Map<String, Integer> result = new HashMap<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT shop_item_id, count FROM player_purchases WHERE player_uuid=?")) {
            ps.setString(1, puid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("shop_item_id"), rs.getInt("count"));
            }
        }
        return result;
    }

    /** Total purchase count across all players for one item. */
    public int getItemPurchaseSum(String itemId) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COALESCE(SUM(count),0) FROM player_purchases WHERE shop_item_id=?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        }
        return 0;
    }

    /** Delete all purchase records for a player. Returns number of rows removed. */
    public int resetAllPlayerPurchases(UUID puid) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM player_purchases WHERE player_uuid=?")) {
            ps.setString(1, puid.toString());
            return ps.executeUpdate();
        }
    }

    /** Delete all purchase records for one item (server-wide). Returns number of rows removed. */
    public int resetItemPurchases(String itemId) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM player_purchases WHERE shop_item_id=?")) {
            ps.setString(1, itemId);
            return ps.executeUpdate();
        }
    }

    /** Delete every purchase record. Returns number of rows removed. */
    public int resetAllPurchases() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate("DELETE FROM player_purchases");
        }
    }
}
