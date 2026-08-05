package com.ohtoai.opshop.database;

import com.ohtoai.opshop.OpShopPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final OpShopPlugin plugin;
    private HikariDataSource dataSource;   // Only used for MySQL
    private String sqliteUrl;              // SQLite JDBC URL
    private String dbType;

    // Table for custom-item currency definitions (added via commands)
    private static final String CREATE_CURRENCIES = """
        CREATE TABLE IF NOT EXISTS currencies (
            id          VARCHAR(64) PRIMARY KEY,
            type        VARCHAR(32) NOT NULL,
            name        VARCHAR(128) NOT NULL,
            item_data   BLOB,
            created_at  BIGINT NOT NULL
        )
        """;

    private static final String CREATE_CURRENCIES_MYSQL = """
        CREATE TABLE IF NOT EXISTS currencies (
            id          VARCHAR(64) PRIMARY KEY,
            type        VARCHAR(32) NOT NULL,
            name        VARCHAR(128) NOT NULL,
            item_data   BLOB,
            created_at  BIGINT NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    // Player purchase tracking table (for per-player limits)
    private static final String CREATE_PLAYER_PURCHASES = """
        CREATE TABLE IF NOT EXISTS player_purchases (
            player_uuid   VARCHAR(36) NOT NULL,
            shop_item_id  VARCHAR(64) NOT NULL,
            count         INT NOT NULL DEFAULT 0,
            PRIMARY KEY (player_uuid, shop_item_id)
        )
        """;

    private static final String CREATE_PLAYER_PURCHASES_MYSQL = """
        CREATE TABLE IF NOT EXISTS player_purchases (
            player_uuid   VARCHAR(36) NOT NULL,
            shop_item_id  VARCHAR(64) NOT NULL,
            count         INT NOT NULL DEFAULT 0,
            PRIMARY KEY (player_uuid, shop_item_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    // Guild whitelist table — which guilds can buy a shop item
    private static final String CREATE_SHOP_ITEM_GUILDS = """
        CREATE TABLE IF NOT EXISTS shop_item_guilds (
            shop_item_id  VARCHAR(64) NOT NULL,
            guild_id      INT NOT NULL,
            PRIMARY KEY (shop_item_id, guild_id),
            FOREIGN KEY (shop_item_id) REFERENCES shop_items(id) ON DELETE CASCADE
        )
        """;

    private static final String CREATE_SHOP_ITEM_GUILDS_MYSQL = """
        CREATE TABLE IF NOT EXISTS shop_item_guilds (
            shop_item_id  VARCHAR(64) NOT NULL,
            guild_id      INT NOT NULL,
            PRIMARY KEY (shop_item_id, guild_id),
            FOREIGN KEY (shop_item_id) REFERENCES shop_items(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    private static final String CREATE_SHOP_ITEMS = """
        CREATE TABLE IF NOT EXISTS shop_items (
            id                  VARCHAR(64) PRIMARY KEY,
            page                INT NOT NULL DEFAULT 0,
            slot                INT NOT NULL DEFAULT 0,
            category            VARCHAR(64) DEFAULT 'default',
            display_item        BLOB NOT NULL,
            product_item        BLOB NOT NULL,
            use_global_defaults BOOLEAN NOT NULL DEFAULT 1,
            buy_enabled         BOOLEAN NOT NULL DEFAULT 1,
            sell_enabled        BOOLEAN NOT NULL DEFAULT 1,
            stock               INT NOT NULL DEFAULT -1,
            max_stock           INT NOT NULL DEFAULT -1,
            cooldown_seconds    BIGINT NOT NULL DEFAULT 0,
            permission_required VARCHAR(128),
            apply_placeholders  BOOLEAN NOT NULL DEFAULT 0,
            total_limit         INT NOT NULL DEFAULT -1,
            total_sold          INT NOT NULL DEFAULT 0,
            player_limit        INT NOT NULL DEFAULT -1,
            refund_limit                BOOLEAN NOT NULL DEFAULT 0,
            guild_restriction_enabled   BOOLEAN NOT NULL DEFAULT 0,
            vault_buy_price     DOUBLE NOT NULL DEFAULT 0,
            vault_sell_price    DOUBLE NOT NULL DEFAULT 0,
            pp_buy_price        DOUBLE NOT NULL DEFAULT 0,
            pp_sell_price       DOUBLE NOT NULL DEFAULT 0,
            custom_currency_id  VARCHAR(64),
            custom_buy_price    DOUBLE NOT NULL DEFAULT 0,
            custom_sell_price   DOUBLE NOT NULL DEFAULT 0,
            created_at          BIGINT NOT NULL,
            updated_at          BIGINT NOT NULL
        )
        """;

    private static final String CREATE_SHOP_ITEM_PRICES = """
        CREATE TABLE IF NOT EXISTS shop_item_prices (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            shop_item_id VARCHAR(64) NOT NULL,
            currency_id VARCHAR(64) NOT NULL,
            buy_price   DOUBLE NOT NULL DEFAULT 0,
            sell_price  DOUBLE NOT NULL DEFAULT 0,
            FOREIGN KEY (shop_item_id) REFERENCES shop_items(id) ON DELETE CASCADE,
            UNIQUE (shop_item_id, currency_id)
        )
        """;

    private static final String CREATE_TRANSACTIONS = """
        CREATE TABLE IF NOT EXISTS transactions (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid     VARCHAR(36) NOT NULL,
            player_name     VARCHAR(32) NOT NULL,
            shop_item_id    VARCHAR(64) NOT NULL,
            type            VARCHAR(8) NOT NULL,
            currency_id     VARCHAR(64) NOT NULL,
            price_per_unit  DOUBLE NOT NULL,
            quantity        INT NOT NULL,
            total_price     DOUBLE NOT NULL,
            item_snapshot   BLOB,
            timestamp       BIGINT NOT NULL
        )
        """;

    private static final String CREATE_SHOP_ITEM_PRICES_MYSQL = """
        CREATE TABLE IF NOT EXISTS shop_item_prices (
            id          INT AUTO_INCREMENT PRIMARY KEY,
            shop_item_id VARCHAR(64) NOT NULL,
            currency_id VARCHAR(64) NOT NULL,
            buy_price   DOUBLE NOT NULL DEFAULT 0,
            sell_price  DOUBLE NOT NULL DEFAULT 0,
            FOREIGN KEY (shop_item_id) REFERENCES shop_items(id) ON DELETE CASCADE,
            UNIQUE (shop_item_id, currency_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    private static final String CREATE_TRANSACTIONS_MYSQL = """
        CREATE TABLE IF NOT EXISTS transactions (
            id              INT AUTO_INCREMENT PRIMARY KEY,
            player_uuid     VARCHAR(36) NOT NULL,
            player_name     VARCHAR(32) NOT NULL,
            shop_item_id    VARCHAR(64) NOT NULL,
            type            VARCHAR(8) NOT NULL,
            currency_id     VARCHAR(64) NOT NULL,
            price_per_unit  DOUBLE NOT NULL,
            quantity        INT NOT NULL,
            total_price     DOUBLE NOT NULL,
            item_snapshot   BLOB,
            timestamp       BIGINT NOT NULL,
            INDEX idx_transactions_player (player_uuid, timestamp),
            INDEX idx_transactions_time (timestamp)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    public DatabaseManager(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        var config = plugin.getPluginConfig();
        this.dbType = config.getDatabaseType();

        if ("mysql".equalsIgnoreCase(dbType)) {
            initializeMySQL(config);
        } else {
            initializeSQLite(config);
        }
    }

    private void initializeMySQL(com.ohtoai.opshop.config.PluginConfig config) {
        String url = "jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
            + "/" + config.getMysqlDatabase();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(config.getMysqlUsername());
        hikariConfig.setPassword(config.getMysqlPassword());
        hikariConfig.setMaximumPoolSize(config.getMysqlPoolSize());
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            plugin.getLogger().info("MySQL database connected: " + config.getMysqlHost());
            createTables(true);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to MySQL!", e);
        }
    }

    private void initializeSQLite(com.ohtoai.opshop.config.PluginConfig config) {
        File dbFile = new File(plugin.getDataFolder(), config.getSqliteFile());
        File parentDir = dbFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        this.sqliteUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try {
            // Use server's built-in SQLite JDBC driver (Paper includes it)
            Class.forName("org.sqlite.JDBC");
            // Test connection
            try (Connection conn = DriverManager.getConnection(sqliteUrl)) {
                plugin.getLogger().info("SQLite database connected: " + dbFile.getAbsolutePath());
            }
            createTables(false);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to SQLite!", e);
        }
    }

    private void createTables(boolean isMysql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(isMysql ? CREATE_CURRENCIES_MYSQL : CREATE_CURRENCIES);
            stmt.execute(CREATE_SHOP_ITEMS);
            stmt.execute(isMysql ? CREATE_SHOP_ITEM_PRICES_MYSQL : CREATE_SHOP_ITEM_PRICES);
            stmt.execute(isMysql ? CREATE_TRANSACTIONS_MYSQL : CREATE_TRANSACTIONS);
            stmt.execute(isMysql ? CREATE_PLAYER_PURCHASES_MYSQL : CREATE_PLAYER_PURCHASES);
            stmt.execute(isMysql ? CREATE_SHOP_ITEM_GUILDS_MYSQL : CREATE_SHOP_ITEM_GUILDS);
            // Migration: add refund_limit column (silently skip if already exists)
            try { stmt.execute("ALTER TABLE shop_items ADD COLUMN refund_limit BOOLEAN NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            // Migration: add generate_unique_id column
            try { stmt.execute("ALTER TABLE shop_items ADD COLUMN generate_unique_id BOOLEAN NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            // Migration: add guild_restriction_enabled column
            try { stmt.execute("ALTER TABLE shop_items ADD COLUMN guild_restriction_enabled BOOLEAN NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            // Migration: add broadcast_on_trade column
            try { stmt.execute("ALTER TABLE shop_items ADD COLUMN broadcast_on_trade BOOLEAN NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
        }
    }

    /**
     * Get a database connection.
     * For MySQL, returns a connection from the HikariCP pool.
     * For SQLite, creates a new connection on demand (embedded DB, no network overhead).
     * Callers MUST close the connection when done (try-with-resources).
     */
    public Connection getConnection() throws SQLException {
        if ("mysql".equalsIgnoreCase(dbType)) {
            return dataSource.getConnection();
        }
        return DriverManager.getConnection(sqliteUrl);
    }

    public boolean isMySQL() {
        return "mysql".equalsIgnoreCase(dbType);
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("MySQL connection pool shut down.");
        }
    }

    public TransactionRepository getTransactionRepository() {
        return new TransactionRepository(this);
    }
}
