package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Admin GUI — list all guilds with their shop item count.
 * Click a guild to open ShopEditorGui in guild mode for that guild.
 */
public class GuildAdminListGui implements GuiHolder {

    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 45;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;

    private final OpShopPlugin plugin;

    private Inventory inventory;
    private int currentPage = 0;
    private List<GuildEntry> guilds = List.of();

    private record GuildEntry(int id, String name, String tag, int itemCount) {}

    public GuildAdminListGui(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    private String m(String key, String en, String... reps) {
        return plugin.getMessages().get(key, en, reps);
    }

    public void open(Player player) {
        // Build guild list with item counts
        Map<Integer, Integer> counts = new HashMap<>();
        for (var item : plugin.getShopManager().getAllItems()) {
            if (item.isGuildRestricted()) {
                for (int gid : item.getAllowedGuildIds()) {
                    counts.merge(gid, 1, Integer::sum);
                }
            }
        }

        List<GuildEntry> list = new ArrayList<>();
        var hook = plugin.getHookManager();
        if (hook.isGuildEnabled()) {
            for (var gi : hook.getGuildBridge().getAllGuilds()) {
                list.add(new GuildEntry(gi.id(), gi.name(), gi.tag(),
                    counts.getOrDefault(gi.id(), 0)));
            }
            list.sort(Comparator.comparing(g -> g.name() != null ? g.name() : ""));
        }
        this.guilds = list;
        this.currentPage = 0;
        buildAndShow(player);
    }

    private void buildAndShow(Player player) {
        String title = m("guild-admin-list.title", "&8Guild Admin — Select Guild");
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
            int slot = i - startIdx;
            inventory.setItem(slot, buildGuildIcon(guilds.get(i)));
        }

        // Navigation
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV, GuiUtils.makeItem(Material.ARROW,
                m("guild-admin-list.prev-page", "&a← Previous Page"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 1), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_INFO, GuiUtils.makeItem(Material.PAPER,
            m("editor.page", "&ePage {page}/{total}",
                "{page}", String.valueOf(currentPage + 1), "{total}", String.valueOf(totalPages)),
            List.of(m("guild-admin-list.total", "&7{count} guild(s)",
                "{count}", String.valueOf(guilds.size())))));
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtils.makeItem(Material.ARROW,
                m("guild-admin-list.next-page", "&aNext Page →"),
                List.of(m("editor.page-num", "&7Page {p}/{t}",
                    "{p}", String.valueOf(currentPage + 2), "{t}", String.valueOf(totalPages)))));
        }
        inventory.setItem(SLOT_CLOSE, GuiUtils.makeItem(Material.BARRIER,
            m("gui.close", "&cClose"), List.of()));

        player.openInventory(inventory);
    }

    private ItemStack buildGuildIcon(GuildEntry g) {
        String displayName = m("guild-admin-list.entry", "&f{name} &7[{tag}]",
            "{name}", g.name() != null ? g.name() : "?",
            "{tag}", g.tag() != null ? g.tag() : "?");
        return GuiUtils.makeItem(Material.SHIELD, displayName,
            List.of(
                m("guild-admin-list.item-count", "&7{count} guild item(s)",
                    "{count}", String.valueOf(g.itemCount())),
                m("guild-admin-list.click", "&eClick to edit guild shop")
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
            var g = guilds.get(idx);
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            new ShopEditorGui(plugin, plugin.getShopManager(), plugin.getCurrencyManager())
                .openForGuild(player, g.id(), g.name(), g.tag());
        }
    }

    @Override
    public void refresh() {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
