package com.ohtoai.opshop.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports data from a SQLite database file into a MySQL database.
 * Reads all 6 OShop tables and inserts them into the target MySQL connection
 * using INSERT IGNORE for idempotent/safe re-runs.
 */
public class SqliteImportService {

    /** Number of rows to batch per INSERT statement (avoids max_allowed_packet overflow). */
    private static final int BATCH_SIZE = 500;

    private SqliteImportService() {
        // utility class
    }

    /**
     * Tables in FK-safe import order.
     */
    private static final String[] TABLE_ORDER = {
        "currencies",
        "shop_items",
        "shop_page_titles",
        "shop_item_prices",
        "shop_item_guilds",
        "player_purchases",
        "transactions",
        "player_new_item_seen"
    };

    /** Price columns that may need ×100 conversion when the source predates cents storage. */
    private static final Map<String, String[]> PRICE_COLUMNS = Map.of(
        "shop_items", new String[]{
            "vault_buy_price", "vault_sell_price",
            "pp_buy_price", "pp_sell_price",
            "custom_buy_price", "custom_sell_price"},
        "shop_item_prices", new String[]{"buy_price", "sell_price"},
        "transactions", new String[]{"price_per_unit", "total_price"}
    );

    /** Migration flag written by the plugin once prices are stored as integer cents. */
    private static final String CENTS_FLAG = "prices_to_cents_v1";

    /**
     * Import all data from a SQLite file into the given MySQL connection.
     *
     * @param sqliteFilePath absolute path to the SQLite .db file
     * @param mysqlConn      active MySQL connection (must have autoCommit=true on entry)
     * @return ordered map of table name → {imported, skipped}
     * @throws SQLException on connection or query errors
     */
    public static Map<String, int[]> importFromSqlite(String sqliteFilePath, Connection mysqlConn)
            throws SQLException {

        String sqliteUrl = "jdbc:sqlite:" + sqliteFilePath;
        Map<String, int[]> results = new LinkedHashMap<>();

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }

        try (Connection sqliteConn = DriverManager.getConnection(sqliteUrl)) {
            boolean originalAutoCommit = mysqlConn.getAutoCommit();
            mysqlConn.setAutoCommit(false);

            try {
                // Legacy source DBs stored prices as whole-unit doubles; convert to cents.
                boolean sourceHasCents = hasCentsFlag(sqliteConn);
                for (String table : TABLE_ORDER) {
                    int[] counts = importTable(sqliteConn, mysqlConn, table, !sourceHasCents);
                    results.put(table, counts);
                }
                mysqlConn.commit();
            } catch (SQLException e) {
                mysqlConn.rollback();
                throw e;
            } finally {
                mysqlConn.setAutoCommit(originalAutoCommit);
            }
        }

        return results;
    }

    /** Whether the source SQLite DB already stores prices as integer cents. */
    private static boolean hasCentsFlag(Connection sqliteConn) {
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT v FROM opshop_meta WHERE k = '" + CENTS_FLAG + "'")) {
            return rs.next();
        } catch (SQLException e) {
            return false; // no meta table → legacy schema
        }
    }

    /**
     * Import a single table: read rows from SQLite in batches, INSERT IGNORE into MySQL.
     * Tables that do not exist in the source are skipped (reported as 0/0).
     *
     * @param convertLegacyPrices multiply legacy whole-unit price columns by 100
     * @return {imported, skipped}
     */
    private static int[] importTable(Connection sqliteConn, Connection mysqlConn, String table,
                                     boolean convertLegacyPrices)
            throws SQLException {

        // First, read column metadata and all rows from SQLite
        List<Map<String, Object>> allRows = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        String selectSql = "SELECT * FROM " + table;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnName(i));
            }

            String[] priceCols = convertLegacyPrices ? PRICE_COLUMNS.get(table) : null;
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String col : columns) {
                    Object value = rs.getObject(col);
                    if (priceCols != null && value instanceof Number n) {
                        boolean isPriceCol = false;
                        for (String pc : priceCols) if (pc.equalsIgnoreCase(col)) { isPriceCol = true; break; }
                        if (isPriceCol) value = Math.round(n.doubleValue() * 100);
                    }
                    row.put(col, value);
                }
                allRows.add(row);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return new int[]{0, 0}; // table absent in this source DB
            }
            throw e;
        }

        if (allRows.isEmpty()) {
            return new int[]{0, 0};
        }

        // Build INSERT IGNORE statement template
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toArray(String[]::new));
        String insertSql = "INSERT IGNORE INTO " + table
                + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";

        int imported = 0;
        int total = allRows.size();

        // Insert in batches to avoid memory/network pressure
        try (PreparedStatement ps = mysqlConn.prepareStatement(insertSql)) {
            for (int start = 0; start < total; start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, total);

                for (int i = start; i < end; i++) {
                    Map<String, Object> row = allRows.get(i);
                    int idx = 1;
                    for (String col : columns) {
                        ps.setObject(idx++, row.get(col));
                    }
                    ps.addBatch();
                }

                int[] updateCounts = ps.executeBatch();
                for (int count : updateCounts) {
                    // SUCCESS_NO_INFO (-2) = driver accepted the row but didn't report count
                    if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                        imported++;
                    }
                }
            }
        }

        int skipped = total - imported;
        return new int[]{imported, skipped};
    }
}
