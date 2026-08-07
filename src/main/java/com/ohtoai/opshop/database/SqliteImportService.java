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
        "shop_item_prices",
        "shop_item_guilds",
        "player_purchases",
        "transactions"
    };

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
                for (String table : TABLE_ORDER) {
                    int[] counts = importTable(sqliteConn, mysqlConn, table);
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

    /**
     * Import a single table: read rows from SQLite in batches, INSERT IGNORE into MySQL.
     *
     * @return {imported, skipped}
     */
    private static int[] importTable(Connection sqliteConn, Connection mysqlConn, String table)
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

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String col : columns) {
                    row.put(col, rs.getObject(col));
                }
                allRows.add(row);
            }
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
