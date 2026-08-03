package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.integration.GuildPluginBridge;
import com.ohtoai.opshop.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * GUI listing all guilds that have at least one guild-restricted shop item.
 * Clicking a guild opens the guild-filtered shop for that guild.
 */
public class GuildListGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;

    private final OpShopPlugin plugin;

    private Inventory inventory;
    private int currentPage = 0;
    private List<GuildWithCount> guilds = List.of();

    private record GuildWithCount(GuildPluginBridge.GuildInfo info, int itemCount) {}

    public GuildListGui(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    private String m(String key, String en, String... reps) {
        return plugin.getMessages().get(key, en, reps);
    }

    public void open(Player player) {
        // Collect guilds that have at least one restricted item, with item count
        Map<Integer, Integer> guildItemCounts = new HashMap<>();
        for (ShopItem item : plugin.getShopManager().getAllItems()) {
            if (item.isGuildRestricted()) {
                for (int gid : item.getAllowedGuildIds()) {
                    guildItemCounts.merge(gid, 1, Integer::sum);
                }
            }
        }

        var hook = plugin.getHookManager();
        List<GuildWithCount> list = new ArrayList<>();
        if (hook.isGuildEnabled()) {
            for (var gi : hook.getGuildBridge().getAllGuilds()) {
                int count = guildItemCounts.getOrDefault(gi.id(), 0);
                if (count > 0) {
                    list.add(new GuildWithCount(gi, count));
                }
            }
            // Sort by name
            list.sort(Comparator.comparing(g -> g.info().name() != null ? g.info().name() : ""));
        }
        this.guilds = list;
        this.currentPage = 0;
        buildAndShow(player);
    }

    private void buildAndShow(Player player) {
        String title = m("guild-list.title", "&8Guild Shops");
        this.inventory = GuiUtils.createInventory(this, ROWS, title);

        Material filler = Material.matchMaterial(plugin.getPluginConfig().getFillerMaterial());
        if (filler == null) filler = Material.BLACK_STAINED_GLASS_PANE;
        GuiUtils.fill(inventory, filler);

        int totalPages = guilds.isEmpty() ? 1
            : (int) Math.ceil((double) guilds.size() / ITEMS_PER_PAGE);
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, guilds.size());
        for (int i = startIdx; i < endIdx; i++) {
            GuildWithCount gwc = guilds.get(i);
            int slot = i - startIdx;
            inventory.setItem(slot, buildGuildIcon(gwc));
        }

        // Navigation
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV, GuiUtils.makeItem(Material.ARROW,
                m("guild-list.prev-page", "&a← Previous Page"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 1), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_INFO, GuiUtils.makeItem(Material.PAPER,
            m("editor.page", "&ePage {page}/{total}",
                "{page}", String.valueOf(currentPage + 1), "{total}", String.valueOf(totalPages)),
            List.of(m("guild-list.total", "&7{count} guild(s) with items",
                "{count}", String.valueOf(guilds.size())))));
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtils.makeItem(Material.ARROW,
                m("guild-list.next-page", "&aNext Page →"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 2), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of()));

        player.openInventory(inventory);
    }

    private ItemStack buildGuildIcon(GuildWithCount gwc) {
        var g = gwc.info();
        String name = m("guild-list.entry", "&f{name} &7[{tag}]",
            "{name}", g.name() != null ? g.name() : "?",
            "{tag}", g.tag() != null ? g.tag() : "?");
        return GuiUtils.makeItem(Material.SHIELD, name,
            List.of(
                m("guild-list.item-count", "&7{count} guild item(s)",
                    "{count}", String.valueOf(gwc.itemCount())),
                m("guild-list.click", "&eClick to view guild shop")
            ));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int totalPages = guilds.isEmpty() ? 1
            : (int) Math.ceil((double) guilds.size() / ITEMS_PER_PAGE);

        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_PREV && currentPage > 0) {
            currentPage--;
            buildAndShow(player);
            return;
        }
        if (slot == SLOT_NEXT && currentPage < totalPages - 1) {
            currentPage++;
            buildAndShow(player);
            return;
        }
        if (slot == SLOT_INFO || slot >= ITEMS_PER_PAGE) return;

        int idx = currentPage * ITEMS_PER_PAGE + slot;
        if (idx >= 0 && idx < guilds.size()) {
            var gwc = guilds.get(idx);
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            plugin.getShopMainGui().openForGuild(player,
                gwc.info().id(),
                gwc.info().name() != null ? gwc.info().name() : "Guild #" + gwc.info().id(),
                gwc.info().tag());
        }
    }

    @Override
    public void refresh() {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
