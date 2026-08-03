package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.integration.GuildPluginBridge;
import com.ohtoai.opshop.shop.ShopItem;
import com.ohtoai.opshop.shop.ShopManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

/**
 * Paginated GUI for editing the guild whitelist of a shop item.
 * <p>
 * Shows all available guilds (fetched via {@link GuildPluginBridge}) with
 * ON/OFF toggles. Clicking a guild row flips its state; "Allow All" / "Deny All"
 * buttons operate on all guilds. Clicking "Back" auto-saves to the database.
 */
public class GuildWhitelistGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;

    // Navigation slots (row 5)
    private static final int SLOT_PREV = 45;
    private static final int SLOT_ALLOW_ALL = 46;
    private static final int SLOT_INFO = 48;
    private static final int SLOT_DENY_ALL = 50;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_BACK = 53;

    private final OpShopPlugin plugin;
    private final ShopManager shopManager;
    private final ShopItem shopItem;
    private final Set<Integer> whitelist; // in-memory mutable copy

    private Inventory inventory;
    private int currentPage = 0;
    private List<GuildPluginBridge.GuildInfo> allGuilds = List.of();

    public GuildWhitelistGui(OpShopPlugin plugin, ShopManager shopManager, ShopItem shopItem) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.shopItem = shopItem;
        this.whitelist = new HashSet<>(shopItem.getAllowedGuildIds());
    }

    private String m(String key, String en, String... reps) {
        return plugin.getMessages().get(key, en, reps);
    }

    public void open(Player player) {
        // Refresh guild list (in case guilds were created/deleted)
        var hook = plugin.getHookManager();
        if (hook.isGuildEnabled()) {
            this.allGuilds = hook.getGuildBridge().getAllGuilds();
        } else {
            this.allGuilds = List.of();
        }
        if (currentPage < 0) currentPage = 0;
        buildAndShow(player);
    }

    private void buildAndShow(Player player) {
        String title = m("guild-whitelist.title", "&8Guild Whitelist: {id}",
            "{id}", shopItem.getId());
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        int totalPages = allGuilds.isEmpty() ? 1
            : (int) Math.ceil((double) allGuilds.size() / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        // Populate guild slots
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, allGuilds.size());
        for (int i = startIdx; i < endIdx; i++) {
            GuildPluginBridge.GuildInfo guild = allGuilds.get(i);
            int slot = i - startIdx;
            inventory.setItem(slot, buildGuildIcon(guild));
        }

        // -- Navigation bar --
        // Previous Page
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV, GuiUtils.makeItem(Material.ARROW,
                m("guild-whitelist.prev-page", "&a← Previous Page"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 1), "{t}", String.valueOf(totalPages)))));
        }

        // Allow All
        inventory.setItem(SLOT_ALLOW_ALL, GuiUtils.makeItem(Material.LIME_WOOL,
            m("guild-whitelist.allow-all", "&a&lAllow All"),
            List.of(m("guild-whitelist.allow-all-desc", "&7Add all guilds to the whitelist."))));

        // Page info
        inventory.setItem(SLOT_INFO, GuiUtils.makeItem(Material.PAPER,
            m("editor.page", "&ePage {page}/{total}",
                "{page}", String.valueOf(currentPage + 1), "{total}", String.valueOf(totalPages)),
            List.of(m("guild-whitelist.whitelist-count", "&7{count} guild(s) allowed",
                "{count}", String.valueOf(whitelist.size())))));

        // Deny All
        inventory.setItem(SLOT_DENY_ALL, GuiUtils.makeItem(Material.RED_WOOL,
            m("guild-whitelist.deny-all", "&c&lDeny All"),
            List.of(m("guild-whitelist.deny-all-desc", "&7Remove all guilds from the whitelist."))));

        // Next Page
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtils.makeItem(Material.ARROW,
                m("guild-whitelist.next-page", "&aNext Page →"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 2), "{t}", String.valueOf(totalPages)))));
        }

        // Back (auto-save)
        inventory.setItem(SLOT_BACK, GuiUtils.makeItem(Material.OAK_SIGN,
            m("guild-whitelist.back", "&6← Back (auto-save)"),
            List.of(m("guild-whitelist.back-desc", "&7Return to editor and save whitelist."))));

        player.openInventory(inventory);
    }

    /** Build the ON/OFF toggle icon for a single guild. */
    private ItemStack buildGuildIcon(GuildPluginBridge.GuildInfo guild) {
        boolean allowed = whitelist.contains(guild.id());
        String stateLine = allowed
            ? m("guild-whitelist.allowed", "&a&l▶ Allowed")
            : m("guild-whitelist.denied", "&7Denied");
        Material mat = allowed ? Material.LIME_DYE : Material.GRAY_DYE;
        String displayName = m("guild-whitelist.guild-entry-name",
            "&f{name} &7[{tag}]",
            "{name}", guild.name() != null ? guild.name() : "?",
            "{tag}", guild.tag() != null ? guild.tag() : "?");
        return GuiUtils.makeItem(mat, displayName,
            List.of(
                m("guild-whitelist.guild-entry-id", "&7ID: &f{id}", "{id}", String.valueOf(guild.id())),
                stateLine
            ));
    }

    // -------------------------------------------------------------------------
    // Clicks
    // -------------------------------------------------------------------------

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int totalPages = allGuilds.isEmpty() ? 1
            : (int) Math.ceil((double) allGuilds.size() / ITEMS_PER_PAGE);

        // Previous page
        if (slot == SLOT_PREV && currentPage > 0) {
            currentPage--;
            buildAndShow(player);
            return;
        }

        // Next page
        if (slot == SLOT_NEXT && currentPage < totalPages - 1) {
            currentPage++;
            buildAndShow(player);
            return;
        }

        // Allow All
        if (slot == SLOT_ALLOW_ALL) {
            for (var g : allGuilds) whitelist.add(g.id());
            shopItem.setGuildRestrictionEnabled(true);
            int count = allGuilds.size();
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("guild-whitelist.toggle-allowed", "&aAdded {count} guild(s) to whitelist.",
                    "{count}", String.valueOf(count))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            buildAndShow(player);
            return;
        }

        // Deny All
        if (slot == SLOT_DENY_ALL) {
            int count = whitelist.size();
            whitelist.clear();
            shopItem.setGuildRestrictionEnabled(false);
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + m("guild-whitelist.toggle-denied", "&cRemoved {count} guild(s) from whitelist.",
                    "{count}", String.valueOf(count))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f);
            buildAndShow(player);
            return;
        }

        // Back — save and return to editor
        if (slot == SLOT_BACK) {
            saveAndReturn(player);
            return;
        }

        // Page info or navigation filler — no action
        if (slot == SLOT_INFO || slot >= ITEMS_PER_PAGE) return;

        // Toggle a guild
        int guildIdx = currentPage * ITEMS_PER_PAGE + slot;
        if (guildIdx >= 0 && guildIdx < allGuilds.size()) {
            GuildPluginBridge.GuildInfo guild = allGuilds.get(guildIdx);
            if (whitelist.contains(guild.id())) {
                whitelist.remove(guild.id());
                if (whitelist.isEmpty()) {
                    shopItem.setGuildRestrictionEnabled(false);
                }
            } else {
                whitelist.add(guild.id());
                shopItem.setGuildRestrictionEnabled(true);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            buildAndShow(player);
        }
    }

    private void saveAndReturn(Player player) {
        saveWhitelist(player);
        player.closeInventory();
        // Re-open the editor
        var editor = ShopEditorGui.getActiveInstance();
        if (editor != null) {
            editor.refresh();
            var inv = editor.getInventory();
            if (inv != null) {
                player.openInventory(inv);
            }
        }
    }

    private void saveWhitelist(Player player) {
        try {
            shopManager.saveItemGuilds(shopItem.getId(), whitelist);
            // Sync back to ShopItem
            shopItem.setAllowedGuildIds(whitelist);
            if (player != null) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("guild-whitelist.saved", "&aGuild whitelist saved.")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                "Failed to save guild whitelist for " + shopItem.getId(), e);
            if (player != null) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + m("guild-whitelist.whitelist-save-failed", "&cFailed to save guild whitelist!")));
            }
        }
    }

    // -------------------------------------------------------------------------
    // GuiHolder contract
    // -------------------------------------------------------------------------

    @Override
    public void refresh() {
        // Called by ShopEditorGui when reopening — no-op, state is self-contained
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
