package com.ohtoai.opshop.database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TransactionRepository {

    private final DatabaseManager dbManager;

    public TransactionRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Insert a transaction record and return the generated ID.
     */
    public long insert(TransactionRecord record) throws SQLException {
        String sql = """
            INSERT INTO transactions
            (player_uuid, player_name, shop_item_id, type, currency_id,
             price_per_unit, quantity, total_price, item_snapshot, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, record.getPlayerUuid().toString());
            ps.setString(2, record.getPlayerName());
            ps.setString(3, record.getShopItemId());
            ps.setString(4, record.getType().name());
            ps.setString(5, record.getCurrencyId());
            ps.setDouble(6, record.getPricePerUnit());
            ps.setInt(7, record.getQuantity());
            ps.setDouble(8, record.getTotalPrice());
            ps.setBytes(9, record.getItemSnapshot());
            ps.setLong(10, record.getTimestamp());

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    /**
     * Query transactions by player UUID, newest first.
     */
    public List<TransactionRecord> queryByPlayer(UUID playerUuid, int page, int pageSize) throws SQLException {
        String sql = """
            SELECT * FROM transactions
            WHERE player_uuid = ?
            ORDER BY timestamp DESC
            LIMIT ? OFFSET ?
            """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, pageSize);
            ps.setInt(3, page * pageSize);
            return mapResults(ps);
        }
    }

    /**
     * Query all transactions, newest first.
     */
    public List<TransactionRecord> queryRecent(int page, int pageSize) throws SQLException {
        String sql = """
            SELECT * FROM transactions
            ORDER BY timestamp DESC
            LIMIT ? OFFSET ?
            """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, page * pageSize);
            return mapResults(ps);
        }
    }

    /**
     * Query transactions by shop item ID, newest first.
     */
    public List<TransactionRecord> queryByItem(String shopItemId, int page, int pageSize) throws SQLException {
        String sql = "SELECT * FROM transactions WHERE shop_item_id = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopItemId);
            ps.setInt(2, pageSize);
            ps.setInt(3, page * pageSize);
            return mapResults(ps);
        }
    }

    /**
     * Count transactions by item.
     */
    public long countByItem(String shopItemId) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM transactions WHERE shop_item_id = ?")) {
            ps.setString(1, shopItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    /**
     * Count total transactions.
     */
    public long count() throws SQLException {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM transactions")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    /**
     * Count transactions by player.
     */
    public long countByPlayer(UUID playerUuid) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM transactions WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0;
    }

    private List<TransactionRecord> mapResults(PreparedStatement ps) throws SQLException {
        List<TransactionRecord> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TransactionRecord rec = new TransactionRecord(
                    UUID.fromString(rs.getString("player_uuid")),
                    rs.getString("player_name"),
                    rs.getString("shop_item_id"),
                    TransactionRecord.TransactionType.valueOf(rs.getString("type")),
                    rs.getString("currency_id"),
                    rs.getDouble("price_per_unit"),
                    rs.getInt("quantity"),
                    rs.getDouble("total_price"),
                    rs.getBytes("item_snapshot"),
                    rs.getLong("timestamp")
                );
                rec.setId(rs.getLong("id"));
                results.add(rec);
            }
        }
        return results;
    }
}
