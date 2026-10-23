package com.ohtoai.opshop.shop;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.item.ItemSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/**
 * New-item announcements.
 * <p>
 * Every shop item carries an {@code announced_at} marker. Each player has a
 * "seen" watermark in the database. On join, players are told about items
 * announced after their watermark (visible + tradeable ones only), and the
 * watermark is advanced. The editor's refresh button re-announces a single
 * item: its marker is bumped and online players are notified immediately.
 */
public class NewItemNotifier {

    /** Cap the join list — longer lists collapse into a "+N more" line. */
    private static final int MAX_LISTED = 10;

    private final OpShopPlugin plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public NewItemNotifier(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    /** Send the join notification for anything announced since the player last saw. */
    public void sendJoinNotification(Player player) {
        List<ShopItem> newItems = plugin.getShopManager().getNewItemsFor(player.getUniqueId());
        if (newItems.isEmpty()) return;

        // Advance the watermark so the same list is not shown on the next join
        plugin.getShopManager().markNewItemsSeen(player.getUniqueId(), System.currentTimeMillis());
        sendItemsMessage(player, newItems);
    }

    /**
     * Re-announce an item (editor refresh button): bump its announce marker,
     * notify all online players who can see it, and advance their watermark
     * to the item's new marker (not "now" — so other unseen items still
     * surface on their next join).
     */
    public void announceItem(String itemId, Player admin) {
        var itemOpt = plugin.getShopManager().getItem(itemId);
        if (itemOpt.isEmpty()) return;
        ShopItem item = itemOpt.get();

        long marked = System.currentTimeMillis();
        try {
            plugin.getShopManager().setAnnouncedAt(itemId, marked);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to re-announce item: " + itemId, e);
            return;
        }

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!canSee(online, item)) continue;
            sendItemsMessage(online, List.of(item));
            plugin.getShopManager().markNewItemsSeen(online.getUniqueId(), marked);
        }

        String prefix = plugin.getMessages().getPrefix();
        admin.sendMessage(Messages.colorize(prefix + plugin.getMessages().get(
            "new-items.announce-refreshed",
            "&aAnnouncement refreshed for &e{id}&a — players will be notified.",
            "{id}", itemId)));
    }

    /** Whether a guild-restricted item is visible to this player. */
    private boolean canSee(Player player, ShopItem item) {
        if (!item.isGuildRestricted()) return true;
        var hook = plugin.getHookManager();
        if (!hook.isGuildEnabled()) return false;
        var guild = hook.getGuildBridge().getPlayerGuild(player.getUniqueId());
        return guild != null && item.getAllowedGuildIds().contains(guild.id());
    }

    private void sendItemsMessage(Player player, List<ShopItem> items) {
        String prefix = plugin.getMessages().getPrefix();
        player.sendMessage(Messages.colorize(prefix + plugin.getMessages().get(
            "new-items.header", "&6&lNew items are available!")));

        String hover = plugin.getMessages().get("new-items.hover", "&eClick to open the trade view");

        int shown = 0;
        for (ShopItem item : items) {
            if (shown >= MAX_LISTED) break;
            player.sendMessage(buildEntry(item, hover));
            shown++;
        }
        int remaining = items.size() - shown;
        if (remaining > 0) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get(
                "new-items.more", "  &7... and &f{count} &7more",
                "{count}", String.valueOf(remaining))));
        }
        player.sendMessage(Messages.colorize(plugin.getMessages().get(
            "new-items.click-hint", "&7Click an item name to open its trade view.")));
    }

    /** A clickable announcement line: clicking runs /oshop item <id>. */
    private Component buildEntry(ShopItem item, String hoverText) {
        String name = displayName(item);
        Component line = Component.empty()
            .append(legacy.deserialize(Messages.colorize("  &7- &f")))
            .append(legacy.deserialize(name))
            .append(legacy.deserialize(Messages.colorize("&r &8[" + item.getId() + "]")));
        return line
            .clickEvent(ClickEvent.runCommand("/oshop item " + item.getId()))
            .hoverEvent(HoverEvent.showText(legacy.deserialize(Messages.colorize(hoverText))));
    }

    /** Best-effort display name for the item's icon (falls back to the ID). */
    private String displayName(ShopItem item) {
        ItemStack icon = ItemSerializer.deserialize(item.getDisplayItem());
        if (icon != null && icon.hasItemMeta() && icon.getItemMeta().hasDisplayName()) {
            return legacy.serialize(icon.getItemMeta().displayName());
        }
        return item.getId();
    }
}
