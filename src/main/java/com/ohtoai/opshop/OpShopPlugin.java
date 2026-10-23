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
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteStreams;

import java.util.logging.Level;

public final class OpShopPlugin extends JavaPlugin implements PluginMessageListener {

    private static OpShopPlugin instance;

    // Managers
    private PluginConfig pluginConfig;
    private Messages messages;
    private CurrencyManager currencyManager;
    private ShopManager shopManager;
    private DatabaseManager databaseManager;
    private TransactionProcessor transactionProcessor;
    private HookManager hookManager;
    private com.ohtoai.opshop.shop.NewItemNotifier newItemNotifier;

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

        // Initialize new-item notifications
        this.newItemNotifier = new com.ohtoai.opshop.shop.NewItemNotifier(this);

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

        // BungeeCord cross-server sync
        if (pluginConfig.isBungeeSyncEnabled() && databaseManager.isMySQL()) {
            // Register both legacy and modern channel names for maximum compatibility
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", this);
            getServer().getMessenger().registerOutgoingPluginChannel(this, "bungeecord:main");
            getServer().getMessenger().registerIncomingPluginChannel(this, "bungeecord:main", this);
            getLogger().info("BungeeCord sync enabled — cross-server reload notifications active.");
        }

        getLogger().log(Level.INFO, "OpShop v{0} enabled!", getPluginMeta().getVersion());
    }

    // --- BungeeCord cross-server sync ---

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        // Accept both legacy and modern channel names
        if (!"BungeeCord".equals(channel) && !"bungeecord:main".equals(channel)) return;

        try {
            var in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();
            if (!"opshop:reload".equals(subChannel)) return;

            // Read and discard the length-prefixed payload (Forward format)
            short len = in.readShort();
            if (len > 0) {
                byte[] payload = new byte[len];
                in.readFully(payload);
            }

            getLogger().info("[OShop] Received reload notification via BungeeCord — refreshing data.");
            getServer().getScheduler().runTask(this, () -> {
                shopManager.reloadFromDatabase();
                currencyManager.loadFromConfig(pluginConfig);
            });
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "[OShop] Failed to process BungeeCord plugin message", e);
        }
    }

    /**
     * Notify other servers behind the same BungeeCord/Velocity proxy to reload
     * their cached shop data. Safe to call on every data-changing write.
     */
    public void sendBungeeSyncNotification() {
        if (!pluginConfig.isBungeeSyncEnabled() || !databaseManager.isMySQL()) return;

        Player player = getServer().getOnlinePlayers().stream().findAny().orElse(null);
        if (player == null) return;

        var out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ALL");
        out.writeUTF("opshop:reload");
        out.writeShort(0); // empty payload

        // Send on legacy channel name — Paper normalizes to bungeecord:main internally
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
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

    public com.ohtoai.opshop.shop.NewItemNotifier getNewItemNotifier() {
        return newItemNotifier;
    }

    public ShopMainGui getShopMainGui() {
        return shopMainGui;
    }
}
