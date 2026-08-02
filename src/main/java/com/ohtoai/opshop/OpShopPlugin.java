package com.ohtoai.opshop;

import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.config.PluginConfig;
import com.ohtoai.opshop.currency.CurrencyManager;
import com.ohtoai.opshop.database.DatabaseManager;
import com.ohtoai.opshop.gui.GuiListener;
import com.ohtoai.opshop.gui.ShopMainGui;
import com.ohtoai.opshop.integration.HookManager;
import com.ohtoai.opshop.listener.PlayerListener;
import com.ohtoai.opshop.placeholder.OpShopExpansion;
import com.ohtoai.opshop.shop.ShopManager;
import com.ohtoai.opshop.shop.TransactionProcessor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class OpShopPlugin extends JavaPlugin {

    private static OpShopPlugin instance;

    // Managers
    private PluginConfig pluginConfig;
    private Messages messages;
    private CurrencyManager currencyManager;
    private ShopManager shopManager;
    private DatabaseManager databaseManager;
    private TransactionProcessor transactionProcessor;
    private HookManager hookManager;

    // GUI
    private ShopMainGui shopMainGui;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        // Initialize config
        this.pluginConfig = new PluginConfig(this);
        this.pluginConfig.load();
        this.messages = new Messages(this);

        // Detect integrations
        this.hookManager = new HookManager(this);
        this.hookManager.detectAll();

        if (pluginConfig.isDebug()) {
            getLogger().info("Integrations: Vault=" + hookManager.isVaultEnabled()
                + ", PlayerPoints=" + hookManager.isPlayerPointsEnabled()
                + ", PlaceholderAPI=" + hookManager.isPlaceholderApiEnabled());
        }

        // Initialize database FIRST — required by currency and shop managers
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // Initialize currency system — loads custom currencies from DB
        this.currencyManager = new CurrencyManager(this, hookManager);
        this.currencyManager.loadFromConfig(pluginConfig);

        // Initialize shop data — loads items from DB
        this.shopManager = new ShopManager(this, currencyManager, databaseManager);
        this.shopManager.loadFromConfig(pluginConfig);

        // Initialize transaction processor
        this.transactionProcessor = new TransactionProcessor(
            this, currencyManager, shopManager, databaseManager);

        // Initialize GUIs
        this.shopMainGui = new ShopMainGui(this, shopManager, currencyManager, transactionProcessor);

        // Register commands
        var shopCommand = new com.ohtoai.opshop.command.OpShopCommand(this);
        var cmd = getCommand("oshop");
        if (cmd != null) {
            cmd.setExecutor(shopCommand);
            cmd.setTabCompleter(shopCommand);
        }

        // Register listeners
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register PlaceholderAPI expansion
        if (hookManager.isPlaceholderApiEnabled()) {
            new OpShopExpansion(this, currencyManager, shopManager).register();
        }

        getLogger().log(Level.INFO, "OpShop v{0} enabled!", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (hookManager != null) {
            hookManager.shutdown();
        }
        instance = null;
        getLogger().info("OpShop disabled.");
    }

    public static OpShopPlugin getInstance() {
        return instance;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public Messages getMessages() {
        return messages;
    }

    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public TransactionProcessor getTransactionProcessor() {
        return transactionProcessor;
    }

    public HookManager getHookManager() {
        return hookManager;
    }

    public ShopMainGui getShopMainGui() {
        return shopMainGui;
    }
}
