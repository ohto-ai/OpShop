package com.ohtoai.opshop.item;

import com.ohtoai.opshop.OpShopPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces placeholders in item display name and lore.
 * <p>
 * Uses Adventure's {@code replaceText} with {@code .replacement(String)} —
 * this only swaps the placeholder text while preserving ALL original formatting
 * (color, italic, bold, hover events, etc.) on the surrounding text node.
 * <p>
 * Available placeholders (built-in):
 * <ul>
 *   <li>{@code %buyer_name%} — player username</li>
 *   <li>{@code %buyer_uuid%} — player UUID</li>
 *   <li>{@code %date%} — current date (YYYY-MM-DD)</li>
 *   <li>{@code %time%} — current time (HH:MM:SS)</li>
 *   <li>{@code %player_level%} — player XP level</li>
 *   <li>{@code %player_world%} — player's world name</li>
 *   <li>{@code %player_health%} — player health (e.g. "18.5 / 20.0")</li>
 *   <li>{@code %player_food%} — player food level</li>
 *   <li>{@code %player_gamemode%} — player game mode</li>
 *   <li>{@code %server_online%} — online player count</li>
 *   <li>{@code %server_max%} — max player slots</li>
 * </ul>
 * If PlaceholderAPI is present, all {@code %papi_xxx%} placeholders are also resolved.
 */
public final class PlaceholderReplacer {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private PlaceholderReplacer() {}

    // --- Public API ---

    /** Apply placeholders for a completed purchase (real data). */
    public static ItemStack apply(ItemStack template, Player buyer) {
        ItemStack result = applyBuiltins(template, buyer);
        var hook = OpShopPlugin.getInstance().getHookManager();
        if (hook.isPlaceholderApiEnabled()) {
            result = applyPlaceholderApi(result, buyer);
        }
        return result;
    }

    /** Apply placeholders with preview data (shown before purchase). */
    public static ItemStack applyPreview(ItemStack template, Player viewer) {
        return applyBuiltins(template, viewer);
    }

    /** Returns a human-readable list of available placeholder names (for editor lore hints). */
    public static List<String> getAvailablePlaceholders() {
        return List.of(
            "%buyer_name%",
            "%buyer_uuid%",
            "%date%",
            "%time%",
            "%player_level%",
            "%player_world%",
            "%player_health%",
            "%player_food%",
            "%player_gamemode%",
            "%server_online%",
            "%server_max%"
        );
    }

    /** Resolve a single placeholder against a player, returning "placeholder → value". */
    public static String resolveSingle(String placeholder, Player player) {
        // Create a dummy component containing just the placeholder, resolve it, extract value
        Component dummy = Component.text(placeholder);
        Component resolved = replaceBuiltins(dummy, player, player.getName(), player.getUniqueId().toString());
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(resolved);
        if (plain.equals(placeholder)) return placeholder + " → &7(n/a)";
        return placeholder + " → &f" + plain;
    }

    // --- Built-in placeholder replacement ---

    private static ItemStack applyBuiltins(ItemStack template, Player player) {
        ItemStack result = template.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();

        if (meta.hasDisplayName()) {
            meta.displayName(replaceBuiltins(meta.displayName(), player, playerName, playerUuid));
        }
        if (meta.hasLore()) {
            List<Component> newLore = new ArrayList<>();
            for (Component line : meta.lore()) {
                newLore.add(replaceBuiltins(line, player, playerName, playerUuid));
            }
            meta.lore(newLore);
        }
        result.setItemMeta(meta);
        return result;
    }

    /**
     * Replace built-in %placeholders% in a Component tree.
     * Uses .replacement(String) which preserves ALL original formatting
     * (italic, color, bold, hover events, etc.) on the surrounding text node.
     */
    private static Component replaceBuiltins(Component c, Player player,
                                             String playerName, String playerUuid) {
        Component result = c;

        // --- Player identity ---
        result = result.replaceText(b -> b.matchLiteral("%buyer_name%")
            .replacement(playerName));
        result = result.replaceText(b -> b.matchLiteral("%buyer_uuid%")
            .replacement(playerUuid));

        // --- Date / time ---
        result = result.replaceText(b -> b.matchLiteral("%date%")
            .replacement(LocalDate.now().toString()));
        result = result.replaceText(b -> b.matchLiteral("%time%")
            .replacement(LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_TIME)));

        // --- Player stats ---
        result = result.replaceText(b -> b.matchLiteral("%player_level%")
            .replacement(String.valueOf(player.getLevel())));
        result = result.replaceText(b -> b.matchLiteral("%player_world%")
            .replacement(player.getWorld().getName()));
        result = result.replaceText(b -> b.matchLiteral("%player_health%")
            .replacement(String.format("%.1f / %.1f", player.getHealth(),
                player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
                    : 20.0)));
        result = result.replaceText(b -> b.matchLiteral("%player_food%")
            .replacement(String.valueOf(player.getFoodLevel())));
        result = result.replaceText(b -> b.matchLiteral("%player_gamemode%")
            .replacement(player.getGameMode().name().toLowerCase()));

        // --- Server stats ---
        result = result.replaceText(b -> b.matchLiteral("%server_online%")
            .replacement(String.valueOf(Bukkit.getOnlinePlayers().size())));
        result = result.replaceText(b -> b.matchLiteral("%server_max%")
            .replacement(String.valueOf(Bukkit.getMaxPlayers())));

        return result;
    }

    // --- PlaceholderAPI integration ---

    private static ItemStack applyPlaceholderApi(ItemStack item, Player buyer) {
        var papiHook = OpShopPlugin.getInstance().getHookManager().getPlaceholderApiHook();
        if (papiHook == null) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (meta.hasDisplayName()) {
            String legacy = LEGACY.serialize(meta.displayName());
            String resolved = papiHook.setPlaceholders(buyer, legacy);
            if (!resolved.equals(legacy)) {
                meta.displayName(LEGACY.deserialize(resolved));
            }
        }
        if (meta.hasLore()) {
            List<Component> newLore = new ArrayList<>();
            for (Component line : meta.lore()) {
                String legacy = LEGACY.serialize(line);
                String resolved = papiHook.setPlaceholders(buyer, legacy);
                newLore.add(resolved.equals(legacy) ? line : LEGACY.deserialize(resolved));
            }
            meta.lore(newLore);
        }
        item.setItemMeta(meta);
        return item;
    }
}
