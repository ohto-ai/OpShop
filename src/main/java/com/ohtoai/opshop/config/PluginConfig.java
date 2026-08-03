package com.ohtoai.opshop.config;

import com.ohtoai.opshop.OpShopPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PluginConfig {

    private final OpShopPlugin plugin;
    private FileConfiguration config;

    // Database settings
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;
    private String sqliteFile;

    // Global defaults
    private List<Map<String, Object>> globalDefaultPrices;

    // Currency definitions
    private Map<String, CurrencyDef> currencyDefs;

    // GUI settings
    private String guiTitle;
    private int guiRows;
    private int itemsPerPage;
    private String fillerMaterial;

    // Messages
    private Map<String, String> messageMap;

    // General
    private String locale;
    private boolean debug;
    private boolean defaultBuySellEnabled;

    public PluginConfig(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // Database
        ConfigurationSection db = config.getConfigurationSection("database");
        if (db != null) {
            this.databaseType = db.getString("type", "sqlite").toLowerCase();
            ConfigurationSection mysql = db.getConfigurationSection("mysql");
            if (mysql != null) {
                this.mysqlHost = mysql.getString("host", "localhost");
                this.mysqlPort = mysql.getInt("port", 3306);
                this.mysqlDatabase = mysql.getString("database", "opshop");
                this.mysqlUsername = mysql.getString("username", "root");
                this.mysqlPassword = mysql.getString("password", "");
                this.mysqlPoolSize = mysql.getInt("pool-size", 5);
            }
            ConfigurationSection sqlite = db.getConfigurationSection("sqlite");
            if (sqlite != null) {
                this.sqliteFile = sqlite.getString("file", "data.db");
            }
        }

        // Global defaults
        this.globalDefaultPrices = new ArrayList<>();
        ConfigurationSection defaults = config.getConfigurationSection("global-defaults");
        if (defaults != null) {
            List<Map<?, ?>> prices = defaults.getMapList("prices");
            for (Map<?, ?> price : prices) {
                Map<String, Object> p = new HashMap<>();
                p.put("currency", price.get("currency").toString());
                p.put("buy-price", ((Number) price.get("buy-price")).doubleValue());
                p.put("sell-price", ((Number) price.get("sell-price")).doubleValue());
                globalDefaultPrices.add(p);
            }
        }

        // Currencies
        this.currencyDefs = new HashMap<>();
        ConfigurationSection currencies = config.getConfigurationSection("currencies");
        if (currencies != null) {
            for (String key : currencies.getKeys(false)) {
                ConfigurationSection cs = currencies.getConfigurationSection(key);
                if (cs != null) {
                    CurrencyDef def = new CurrencyDef(
                        key,
                        cs.getString("type", "vault").toUpperCase(),
                        cs.getString("display-name", key),
                        cs.getString("display-singular", key)
                    );
                    if ("CUSTOM_ITEM".equalsIgnoreCase(def.type())) {
                        ConfigurationSection item = cs.getConfigurationSection("item");
                        if (item != null) {
                            def = new CurrencyDef(key, def.type(), def.displayName(), def.displaySingular(),
                                item.getString("material", "STONE"),
                                item.getString("name", ""),
                                item.getStringList("lore"),
                                item.getInt("custom-model-data", 0));
                        }
                    }
                    currencyDefs.put(key, def);
                }
            }
        }

        // GUI
        ConfigurationSection gui = config.getConfigurationSection("gui");
        if (gui != null) {
            this.guiTitle = gui.getString("title", "&8OpShop");
            this.guiRows = gui.getInt("rows", 6);
            this.itemsPerPage = gui.getInt("items-per-page", 45);
            this.fillerMaterial = gui.getString("filler-material", "BLACK_STAINED_GLASS_PANE");
        }

        // Messages
        this.messageMap = new HashMap<>();
        ConfigurationSection msgs = config.getConfigurationSection("messages");
        if (msgs != null) {
            for (String key : msgs.getKeys(false)) {
                messageMap.put(key, msgs.getString(key, ""));
            }
        }

        this.locale = config.getString("locale", "en");
        this.debug = config.getBoolean("debug", false);
        this.defaultBuySellEnabled = config.getBoolean("default-buy-sell-enabled", false);

        if (debug) {
            plugin.getLogger().info("Config loaded: " + currencyDefs.size() + " currencies, "
                + globalDefaultPrices.size() + " global defaults");
        }
    }

    // --- Accessors ---

    public String getDatabaseType() { return databaseType; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }
    public String getSqliteFile() { return sqliteFile; }

    public List<Map<String, Object>> getGlobalDefaultPrices() { return globalDefaultPrices; }
    public Map<String, CurrencyDef> getCurrencyDefs() { return currencyDefs; }

    public String getGuiTitle() { return guiTitle; }
    public int getGuiRows() { return guiRows; }
    public int getItemsPerPage() { return itemsPerPage; }
    public String getFillerMaterial() { return fillerMaterial; }

    public Map<String, String> getMessageMap() { return messageMap; }

    public String getLocale() { return locale; }
    public boolean isDebug() { return debug; }
    public boolean isDefaultBuySellEnabled() { return defaultBuySellEnabled; }

    /**
     * Currency definition record parsed from config.
     */
    public record CurrencyDef(
        String id,
        String type,
        String displayName,
        String displaySingular,
        String customMaterial,
        String customName,
        List<String> customLore,
        int customModelData
    ) {
        public CurrencyDef(String id, String type, String displayName, String displaySingular) {
            this(id, type, displayName, displaySingular, "STONE", "", List.of(), 0);
        }
    }
}
