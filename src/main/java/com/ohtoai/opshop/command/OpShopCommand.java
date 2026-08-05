package com.ohtoai.opshop.command;

import com.ohtoai.opshop.OpShopPlugin;
import com.ohtoai.opshop.config.Messages;
import com.ohtoai.opshop.currency.CurrencyProvider;
import com.ohtoai.opshop.database.TransactionRecord;
import com.ohtoai.opshop.database.TransactionRepository;
import com.ohtoai.opshop.gui.ShopEditorGui;
import com.ohtoai.opshop.item.ItemSerializer;
import com.ohtoai.opshop.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class OpShopCommand implements CommandExecutor, TabCompleter {

    private final OpShopPlugin plugin;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public OpShopCommand(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // /oshop with no args = open shop
            if (!(sender instanceof Player player)) {
                sendHelp(sender);
                return true;
            }
            if (!player.hasPermission("opshop.use")) {
                player.sendMessage(plugin.getMessages().get("no-permission"));
                return true;
            }
            plugin.getShopMainGui().open(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "open" -> handleOpen(sender, args);
            case "guild" -> handleGuild(sender, Arrays.copyOfRange(args, 1, args.length));
            case "guild-all" -> handleGuildAll(sender);
            case "admin" -> handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
            case "version" -> {
                sender.sendMessage(plugin.getMessages().get("command.version", "&6OShop v{version}",
                    "{version}", plugin.getPluginMeta().getVersion()));
                yield true;
            }
            default -> { sendHelp(sender); yield true; }
        };
    }

    private boolean handleOpen(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            // Opening for another player requires admin
            if (!sender.hasPermission("opshop.admin")) {
                sender.sendMessage(plugin.getMessages().get("no-permission"));
                return true;
            }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found", "&cPlayer not found."));
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getMessages().get("command.console-specify-open", "Console must specify a player: /oshop open <player>"));
                return true;
            }
            if (!player.hasPermission("opshop.use")) {
                player.sendMessage(plugin.getMessages().get("no-permission"));
                return true;
            }
            target = player;
        }
        plugin.getShopMainGui().open(target);
        if (sender != target) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.open-shop-for", "&aOpened shop for {player}", "{player}", target.getName()));
        }
        return true;
    }

    // --- Guild Shop ---

    private boolean handleGuild(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.console-specify-open",
                    "Console must specify a player: /oshop open <player>")));
            return true;
        }
        if (!player.hasPermission("opshop.use")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }
        if (!plugin.getHookManager().isGuildEnabled()) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.guild-not-installed", "&cGuildPlugin is not installed.")));
            return true;
        }

        Integer guildId = null;
        String guildName = null;
        String guildTag = null;

        if (args.length > 0) {
            // Search guild by name
            String searchName = joinArgs(args, 0, args.length);
            for (var gi : plugin.getHookManager().getGuildBridge().getAllGuilds()) {
                if (gi.name() != null && gi.name().equalsIgnoreCase(searchName)) {
                    guildId = gi.id();
                    guildName = gi.name();
                    guildTag = gi.tag();
                    break;
                }
            }
            if (guildId == null) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("command.guild-not-found", "&cGuild not found: &e{name}",
                        "{name}", searchName)));
                return true;
            }
        } else {
            // Default to player's own guild
            var playerGuild = plugin.getHookManager().getGuildBridge().getPlayerGuild(player.getUniqueId());
            if (playerGuild == null) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("command.not-in-guild", "&cYou are not in a guild. Use &e/oshop guild <name> &cto specify one.")));
                return true;
            }
            guildId = playerGuild.id();
            guildName = playerGuild.name();
            guildTag = playerGuild.tag();
        }

        plugin.getShopMainGui().openForGuild(player, guildId,
            guildName != null ? guildName : "Guild #" + guildId, guildTag);
        return true;
    }

    // --- Guild List ---

    private boolean handleGuildAll(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.console-no-guild-list", "Console cannot open the guild list GUI.")));
            return true;
        }
        if (!player.hasPermission("opshop.use")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }
        if (!plugin.getHookManager().isGuildEnabled()) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.guild-not-installed", "&cGuildPlugin is not installed.")));
            return true;
        }
        new com.ohtoai.opshop.gui.GuildListGui(plugin).open(player);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }
        if (args.length == 0) { sendAdminHelp(sender); return true; }

        return switch (args[0].toLowerCase()) {
            case "editor" -> handleEditor(sender);
            case "guild" -> handleAdminGuild(sender, Arrays.copyOfRange(args, 1, args.length));
            case "add" -> handleAdd(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "give" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "currency" -> handleCurrency(sender, Arrays.copyOfRange(args, 1, args.length));
            case "resetlimit" -> handleResetLimit(sender, args);
            case "log" -> handleLog(sender, Arrays.copyOfRange(args, 1, args.length));
            case "view" -> handleView(sender, Arrays.copyOfRange(args, 1, args.length));
            case "trace" -> handleTrace(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> { sendAdminHelp(sender); yield true; }
        };
    }

    private boolean handleEditor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("command.console-no-editor", "Console cannot open the editor GUI."));
            return true;
        }
        new ShopEditorGui(plugin, plugin.getShopManager(), plugin.getCurrencyManager()).open(player);
        return true;
    }

    private boolean handleAdminGuild(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.console-no-guild-editor", "Console cannot open the guild editor.")));
            return true;
        }
        if (!plugin.getHookManager().isGuildEnabled()) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.guild-not-installed", "&cGuildPlugin is not installed.")));
            return true;
        }

        if (args.length == 0) {
            // Show guild list for admin
            new com.ohtoai.opshop.gui.GuildAdminListGui(plugin).open(player);
            return true;
        }

        // Search guild by name
        String searchName = joinArgs(args, 0, args.length);
        for (var gi : plugin.getHookManager().getGuildBridge().getAllGuilds()) {
            if (gi.name() != null && gi.name().equalsIgnoreCase(searchName)) {
                new ShopEditorGui(plugin, plugin.getShopManager(), plugin.getCurrencyManager())
                    .openForGuild(player, gi.id(), gi.name(), gi.tag());
                return true;
            }
        }
        player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
            + plugin.getMessages().get("command.guild-not-found", "&cGuild not found: &e{name}",
                "{name}", searchName)));
        return true;
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("command.console-no-add", "Console cannot add items. Use the editor."));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.hold-item", "&cHold an item in your hand to use as the product!")));
            return true;
        }

        // ID: join all remaining args (supports spaces; page assigned automatically)
        if (args.length < 2) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.add-usage", "&cUsage: /oshop admin add <id>")));
            return true;
        }
        String id = cleanId(joinArgs(args, 1, args.length));
        if (plugin.getShopManager().getItem(id).isPresent()) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.item-exists", "&cItem '{id}' already exists!", "{id}", id)));
            return true;
        }

        try {
            ShopItem item = new ShopItem(id);
            item.setDisplayItem(ItemSerializer.serialize(held));
            item.setProductItem(ItemSerializer.serialize(held));

            // Page: auto-assign to first available slot
            int maxPage = plugin.getShopManager().getItemCount() == 0 ? -1 : plugin.getShopManager().getMaxPage();
            int targetPage = Math.max(0, maxPage);
            while (plugin.getShopManager().findFirstEmptySlot(targetPage) < 0) {
                targetPage++;
            }
            item.setPage(targetPage);
            item.setSlot(plugin.getShopManager().findFirstEmptySlot(targetPage));
            item.setBuyEnabled(true);
            item.setSellEnabled(true);
            plugin.getCurrencyManager().applyGlobalDefaults(item);

            plugin.getShopManager().saveItem(item);
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.item-created", "&aCreated shop item: &e{id}", "{id}", id)));
            // Open editor at the correct page
            var editor = new ShopEditorGui(plugin, plugin.getShopManager(), plugin.getCurrencyManager());
            editor.open(player, targetPage);
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.create-failed", "&cFailed to create item!")));
            plugin.getLogger().log(Level.WARNING, "Failed to add item " + id, e);
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.delete-usage", "&cUsage: /oshop admin delete <id>")));
            return true;
        }
        // Support IDs with spaces: /oshop admin delete My Cool Item
        String id = cleanId(joinArgs(args, 1, args.length));
        if (plugin.getShopManager().getItem(id).isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("item-not-found"));
            return true;
        }
        try {
            plugin.getShopManager().deleteItem(id);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.item-deleted", "&aDeleted item: &e{id}", "{id}", id)));
        } catch (SQLException e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.delete-failed", "&cFailed to delete item!")));
        }
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.give-usage", "&cUsage: /oshop admin give <player> <itemId> <amount>")));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found", "&cPlayer not found."));
            return true;
        }
        // Last arg is amount, everything between player and amount is the item ID
        String itemId = cleanId(joinArgs(args, 2, args.length - 1));
        var opt = plugin.getShopManager().getItem(itemId);
        if (opt.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("item-not-found"));
            return true;
        }
        int amount;
        try { amount = Integer.parseInt(args[args.length - 1]); } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.invalid-amount", "&cInvalid amount."));
            return true;
        }

        ItemStack product = ItemSerializer.deserialize(opt.get().getProductItem());
        if (product == null) return true;

        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, product.getMaxStackSize());
            ItemStack give = product.clone();
            give.setAmount(stackSize);
            var leftover = target.getInventory().addItem(give);
            for (ItemStack drop : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            remaining -= stackSize;
        }

        sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
            + plugin.getMessages().get("command.gave-item", "&aGave {amount}x {item} to {player}",
                "{amount}", String.valueOf(amount), "{item}", itemId, "{player}", target.getName())));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.getPluginConfig().load();
        plugin.getMessages().reload();
        plugin.getCurrencyManager().loadFromConfig(plugin.getPluginConfig());
        plugin.getShopManager().loadFromConfig(plugin.getPluginConfig());
        sender.sendMessage(plugin.getMessages().get("reload-success"));
        return true;
    }

    // --- Currency Management ---

    private boolean handleCurrency(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin.currency")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }

        if (args.length == 0) {
            // List all currencies
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("currency.list-header", "&eCurrencies:"));
            for (CurrencyProvider p : plugin.getCurrencyManager().getAllProviders()) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("currency.list-item", "  &7- &f{id} &7({name}, {type})",
                    "{id}", p.getId(), "{name}", p.getDisplayName(), "{type}", p.getType().toString())));
            }
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("currency.global-defaults", "&7Global defaults:"));
            for (var entry : plugin.getCurrencyManager().getGlobalDefaults()) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("currency.global-entry", "  &7- &f{id} &7buy: {buy} sell: {sell}",
                    "{id}", String.valueOf(entry.get("currency")),
                    "{buy}", formatPrice((Double) entry.get("buy-price")),
                    "{sell}", formatPrice((Double) entry.get("sell-price")))));
            }
            return true;
        }

        String action = args[0].toLowerCase();

        return switch (action) {
            case "add" -> handleCurrencyAdd(sender, args);
            case "delete" -> handleCurrencyDelete(sender, args);
            case "give", "take" -> handleCurrencyGiveTake(sender, args);
            case "setglobal" -> handleCurrencySetGlobal(sender, args);
            default -> {
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                    + plugin.getMessages().get("currency.usage-general", "&cUsage: /oshop admin currency <add|delete|give|take|setglobal> ...")));
                yield true;
            }
        };
    }

    private boolean handleCurrencyAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("command.console-no-currency-add", "Console cannot add currencies from held items."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-add-usage", "&cUsage: /oshop admin currency add <id>")));
            return true;
        }

        String id = cleanId(joinArgs(args, 1, args.length));

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.hold-currency", "&cHold the currency item in your hand!")));
            return true;
        }

        boolean ok = plugin.getCurrencyManager().addCustomCurrency(id, held);
        if (ok) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-registered", "&aRegistered custom currency: &e{id}", "{id}", id)));
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-registered-hint", "&7Use &e/oshop admin currency setglobal {id} <buy> <sell> &7to set prices.", "{id}", id)));
        } else {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-exists", "&cCurrency '{id}' already exists!", "{id}", id)));
        }
        return true;
    }

    private boolean handleCurrencyDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-delete-usage", "&cUsage: /oshop admin currency delete <id>")));
            return true;
        }

        String currencyId = cleanId(joinArgs(args, 1, args.length));
        boolean ok = plugin.getCurrencyManager().deleteCurrency(currencyId);
        if (ok) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-deleted", "&aDeleted currency: &e{id}", "{id}", currencyId)));
        } else {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-cannot-delete", "&cCannot delete '{id}'. Only custom-item currencies can be deleted.", "{id}", currencyId)));
        }
        return true;
    }

    private boolean handleCurrencyGiveTake(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-give-take-usage", "&cUsage: /oshop admin currency <give|take> <player> <currencyId> [amount]")));
            return true;
        }

        String action = args[0].toLowerCase();
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found", "&cPlayer not found."));
            return true;
        }

        String currencyId = cleanId(joinArgs(args, 2, args.length > 3 && isNumeric(args[args.length - 1]) ? args.length - 1 : args.length));
        var providerOpt = plugin.getCurrencyManager().getProvider(currencyId);
        if (providerOpt.isEmpty()) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.currency-not-found", "&cCurrency not found: {currency}", "{currency}", currencyId));
            return true;
        }

        double amount = 1.0;
        if (args.length > 3 && isNumeric(args[args.length - 1])) {
            try { amount = Double.parseDouble(args[args.length - 1]); }
            catch (NumberFormatException e) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("command.currency-invalid-amount", "&cInvalid amount.")));
                return true;
            }
        }
        CurrencyProvider provider = providerOpt.get();

        if (action.equals("give")) {
            provider.deposit(target, amount);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-gave", "&aGave {amount} to {player}",
                    "{amount}", provider.format(amount), "{player}", target.getName())));
        } else {
            provider.withdraw(target, amount);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-took", "&aTook {amount} from {player}",
                    "{amount}", provider.format(amount), "{player}", target.getName())));
        }
        return true;
    }

    private boolean handleCurrencySetGlobal(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-setglobal-usage", "&cUsage: /oshop admin currency setglobal <currencyId> <buyPrice> <sellPrice>")));
            return true;
        }

        // Last two args are buy/sell prices; everything before them is the currency ID
        String currencyId = cleanId(joinArgs(args, 1, args.length - 2));
        var provider = plugin.getCurrencyManager().getProvider(currencyId);
        if (provider.isEmpty()) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("editor.currency-not-found", "&cCurrency not found: {currency}", "{currency}", currencyId));
            return true;
        }
        // Only VAULT and PLAYER_POINTS can have global defaults; custom items are per-item
        var type = provider.get().getType();
        if (type == com.ohtoai.opshop.currency.CurrencyType.CUSTOM_ITEM) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-cannot-setglobal", "&cCannot set global default for custom-item currency '{id}'. Custom currencies are set per-item in the editor.", "{id}", currencyId)));
            return true;
        }

        try {
            double buy = Double.parseDouble(args[args.length - 2]);
            double sell = Double.parseDouble(args[args.length - 1]);
            plugin.getCurrencyManager().setGlobalDefault(currencyId, buy, sell);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.currency-setglobal-done", "&aGlobal default for &e{id} &aset: buy={buy} sell={sell}",
                    "{id}", currencyId, "{buy}", formatPrice(buy), "{sell}", formatPrice(sell))));
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.invalid-price", "&cInvalid price."));
        }
        return true;
    }

    // --- Reset Limit ---

    private boolean handleResetLimit(CommandSender sender, String[] args) {
        // Permission already checked by handleAdmin; args[0]="resetlimit", args[1]=player, args[2..]=itemId
        if (args.length < 3) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.resetlimit-usage", "&cUsage: /oshop admin resetlimit <player> <itemId>")));
            return true;
        }
        // Support offline players — use getOfflinePlayer
        var target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("player-not-found", "&cPlayer not found."));
            return true;
        }
        String itemId = cleanId(joinArgs(args, 2, args.length));
        if (plugin.getShopManager().getItem(itemId).isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("item-not-found"));
            return true;
        }
        try {
            int oldCount = plugin.getShopManager().resetPlayerPurchase(target.getUniqueId(), itemId);
            // Also refund the global totalSold counter
            if (oldCount > 0) {
                var si = plugin.getShopManager().getItem(itemId);
                if (si.isPresent()) {
                    var s = si.get();
                    s.setTotalSold(Math.max(0, s.getTotalSold() - oldCount));
                    plugin.getShopManager().saveItem(s);
                }
            }
            String msg = plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.resetlimit-done", "&aReset purchase limit for {player} on {item}.",
                    "{player}", target.getName(), "{item}", itemId);
            if (oldCount > 0) msg += plugin.getMessages().get("command.resetlimit-refunded", " &7(refunded {count})",
                "{count}", String.valueOf(oldCount));
            sender.sendMessage(Messages.colorize(msg));
        } catch (SQLException e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("command.resetlimit-error", "&cError resetting purchase limit!")));
            plugin.getLogger().log(Level.WARNING, "Failed to reset player purchase limit", e);
        }
        return true;
    }

    // --- Log (chat-based transaction viewer) ---

    private boolean handleLog(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin.logs")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }

        int pageSize = 10;
        int page = 0;

        if (args.length == 0) {
            // /oshop admin log — show all recent
            return showLogRecent(sender, page);
        }

        // Check for subcommand or page number
        String first = args[0].toLowerCase();
        if ("player".equals(first)) {
            if (args.length < 2) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("log.usage-player", "&cUsage: /oshop admin log player <player> [page]")));
                return true;
            }
            // Parse page from last arg
            int nameEnd = args.length;
            if (isNumeric(args[args.length - 1])) {
                page = Math.max(0, Integer.parseInt(args[args.length - 1]) - 1);
                nameEnd = args.length - 1;
            }
            String playerName = joinArgs(args, 1, nameEnd);
            var target = Bukkit.getOfflinePlayer(playerName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found"));
                return true;
            }
            return showLogByPlayer(sender, target.getUniqueId(), target.getName(), page);
        } else if ("item".equals(first)) {
            if (args.length < 2) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("log.usage-item", "&cUsage: /oshop admin log item <itemId> [page]")));
                return true;
            }
            int idEnd = args.length;
            if (isNumeric(args[args.length - 1])) {
                page = Math.max(0, Integer.parseInt(args[args.length - 1]) - 1);
                idEnd = args.length - 1;
            }
            String itemId = cleanId(joinArgs(args, 1, idEnd));
            return showLogByItem(sender, itemId, page);
        } else {
            // Try to parse as page number
            try { page = Math.max(0, Integer.parseInt(first) - 1); }
            catch (NumberFormatException e) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("log.usage", "&cUsage: /oshop admin log [player <name>|item <id>] [page]")));
                return true;
            }
            return showLogRecent(sender, page);
        }
    }

    private boolean showLogRecent(CommandSender sender, int page) {
        int pageSize = 10;
        TransactionRepository repo = plugin.getDatabaseManager().getTransactionRepository();
        try {
            var total = repo.count();
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            if (page >= totalPages) page = totalPages - 1;
            var records = repo.queryRecent(page, pageSize);
            sendLogPage(sender, records, page + 1, totalPages, null);
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("error-reading-logs", "&cError reading logs!"));
            plugin.getLogger().log(Level.WARNING, "Failed to read logs", e);
        }
        return true;
    }

    private boolean showLogByPlayer(CommandSender sender, UUID puid, String name, int page) {
        int pageSize = 10;
        TransactionRepository repo = plugin.getDatabaseManager().getTransactionRepository();
        try {
            var total = repo.countByPlayer(puid);
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            if (page >= totalPages) page = totalPages - 1;
            var records = repo.queryByPlayer(puid, page, pageSize);
            sendLogPage(sender, records, page + 1, totalPages, "Player: " + name);
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("error-reading-logs", "&cError reading logs!"));
            plugin.getLogger().log(Level.WARNING, "Failed to read logs by player", e);
        }
        return true;
    }

    private boolean showLogByItem(CommandSender sender, String itemId, int page) {
        int pageSize = 10;
        TransactionRepository repo = plugin.getDatabaseManager().getTransactionRepository();
        try {
            var total = repo.countByItem(itemId);
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            if (page >= totalPages) page = totalPages - 1;
            var records = repo.queryByItem(itemId, page, pageSize);
            sendLogPage(sender, records, page + 1, totalPages, "Item: " + itemId);
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("error-reading-logs", "&cError reading logs!"));
            plugin.getLogger().log(Level.WARNING, "Failed to read logs by item", e);
        }
        return true;
    }

    private void sendLogPage(CommandSender sender, List<TransactionRecord> records, int page, int totalPages, String filter) {
        String header = filter != null
            ? plugin.getMessages().get("log.header-filter", "&eTransaction Log: {filter} (Page {page}/{total})",
                "{filter}", filter, "{page}", String.valueOf(page), "{total}", String.valueOf(totalPages))
            : plugin.getMessages().get("log.header", "&eTransaction Log (Page {page}/{total})",
                "{page}", String.valueOf(page), "{total}", String.valueOf(totalPages));
        sender.sendMessage(plugin.getMessages().getPrefix() + header);
        if (records.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("log.no-records", "&7No transactions recorded yet."));
            return;
        }
        for (TransactionRecord rec : records) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("log-record",
                "&7{time} &f{player} &7{type} &f{count}x {item} &7for &f{price} {currency}",
                "{time}", DATE_FORMAT.format(new Date(rec.getTimestamp())),
                "{player}", rec.getPlayerName(),
                "{type}", rec.getType().toString(),
                "{count}", String.valueOf(rec.getQuantity()),
                "{item}", rec.getShopItemId(),
                "{price}", formatPrice(rec.getTotalPrice()),
                "{currency}", rec.getCurrencyId())));
        }
    }

    // --- View (GUI-based transaction viewer) ---

    private boolean handleView(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin.logs")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("command.console-no-log-gui", "Console cannot open the log GUI.")));
            return true;
        }

        if (args.length == 0) {
            // /oshop admin view — show all
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin).open(player);
            return true;
        }

        String first = args[0].toLowerCase();
        if ("player".equals(first)) {
            if (args.length < 2) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("view.usage-player", "&cUsage: /oshop admin view player <player>")));
                return true;
            }
            String playerName = joinArgs(args, 1, args.length);
            var target = Bukkit.getOfflinePlayer(playerName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                player.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found"));
                return true;
            }
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin, target.getUniqueId(), target.getName()).open(player);
        } else if ("item".equals(first)) {
            if (args.length < 2) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("view.usage-item", "&cUsage: /oshop admin view item <itemId>")));
                return true;
            }
            String itemId = cleanId(joinArgs(args, 1, args.length));
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin, itemId).open(player);
        } else {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("view.usage", "&cUsage: /oshop admin view [player <name>|item <id>]")));
        }
        return true;
    }

    // --- Trace ---

    private boolean handleTrace(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendTraceHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "list" -> handleTraceList(sender, Arrays.copyOfRange(args, 1, args.length));
            case "log" -> handleTraceLog(sender, Arrays.copyOfRange(args, 1, args.length));
            case "view" -> handleTraceView(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> { sendTraceHelp(sender); yield true; }
        };
    }

    /** Read the trace ID from a player's held item, or -1 if not found. */
    private long getTraceIdFromHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || !held.hasItemMeta()) return -1;
        Long id = held.getItemMeta().getPersistentDataContainer()
            .get(com.ohtoai.opshop.shop.TransactionProcessor.TRACE_ID_KEY,
                org.bukkit.persistence.PersistentDataType.LONG);
        return id != null ? id : -1;
    }

    /** Read track_id from args or from held item. Returns -1 if not available. */
    private long resolveTraceId(CommandSender sender, String[] args) {
        if (args.length >= 1) {
            try { return Long.parseLong(args[0]); }
            catch (NumberFormatException e) { return -1; }
        }
        if (sender instanceof Player player) {
            return getTraceIdFromHeld(player);
        }
        return -1;
    }

    private boolean handleTraceList(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("trace.usage", "&cUsage: /oshop admin trace list <item|player> <value> [page]")));
            return true;
        }
        String subType = args[0].toLowerCase();

        // Parse optional page number from last argument
        int page = 0;
        int pageSize = 10;
        int valueEnd = args.length;
        if (args.length >= 2 && isNumeric(args[args.length - 1])) {
            page = Math.max(0, Integer.parseInt(args[args.length - 1]) - 1);
            valueEnd = args.length - 1;
        }
        String value = joinArgs(args, 1, valueEnd);

        // Collect trace-enabled item IDs for filtering
        Set<String> traceItemIds = plugin.getShopManager().getTraceEnabledItems().stream()
            .map(com.ohtoai.opshop.shop.ShopItem::getId).collect(Collectors.toSet());

        try {
            if ("item".equals(subType)) {
                String itemId = cleanId(value);
                // Only show if this item has trace enabled
                if (!traceItemIds.contains(itemId)) {
                    sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("trace.list.not-trace-item", "&7This item does not have Trace ID enabled.")));
                    return true;
                }
                var records = plugin.getDatabaseManager().getTransactionRepository().queryByItem(itemId, page, pageSize);
                var total = plugin.getDatabaseManager().getTransactionRepository().countByItem(itemId);
                int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
                if (page >= totalPages) page = totalPages - 1;
                if (total == 0) {
                    sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("trace.list.no-records-item", "&7No trace records for this item.")));
                    return true;
                }
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.list.header-item",
                    "&e===== Trace records for {item} (Page {page}/{total}) =====",
                    "{item}", itemId, "{page}", String.valueOf(page + 1), "{total}", String.valueOf(totalPages))));
                for (TransactionRecord rec : records) {
                    sendTraceListRecord(sender, rec);
                }
            } else if ("player".equals(subType)) {
                var target = Bukkit.getOfflinePlayer(value);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("player-not-found"));
                    return true;
                }
                // Fetch all records and filter to only trace-enabled items (paginated in memory)
                var allRecords = plugin.getDatabaseManager().getTransactionRepository()
                    .queryByPlayer(target.getUniqueId(), 0, 500);
                var traceRecords = allRecords.stream()
                    .filter(r -> traceItemIds.contains(r.getShopItemId()))
                    .toList();
                if (traceRecords.isEmpty()) {
                    sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("trace.list.no-records-player", "&7No trace records for this player.")));
                    return true;
                }
                int total = traceRecords.size();
                int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
                if (page >= totalPages) page = totalPages - 1;
                int start = page * pageSize;
                int end = Math.min(start + pageSize, total);
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.list.header-player",
                    "&e===== Trace records for {player} (Page {page}/{total}) =====",
                    "{player}", target.getName(), "{page}", String.valueOf(page + 1), "{total}", String.valueOf(totalPages))));
                for (int i = start; i < end; i++) {
                    sendTraceListRecord(sender, traceRecords.get(i));
                }
            } else {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.usage", "&cUsage: /oshop admin trace list <item|player> <value> [page]")));
            }
        } catch (Exception e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.trace-query-error", "&cError querying trace records!")));
            plugin.getLogger().log(Level.WARNING, "Failed to query trace records", e);
        }
        return true;
    }

    private void sendTraceListRecord(CommandSender sender, TransactionRecord rec) {
        String curName = plugin.getCurrencyManager().getProvider(rec.getCurrencyId())
            .map(p -> p.getDisplayName()).orElse(rec.getCurrencyId());
        sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.list.record",
            "&7{time} &f#{id} &f{player} &7x{count} &7for &f{price} {currency}",
            "{time}", DATE_FORMAT.format(new Date(rec.getTimestamp())),
            "{id}", String.valueOf(rec.getId()),
            "{player}", rec.getPlayerName(),
            "{count}", String.valueOf(rec.getQuantity()),
            "{price}", formatPrice(rec.getTotalPrice()),
            "{currency}", curName)));
    }

    private boolean handleTraceLog(CommandSender sender, String[] args) {
        long traceId = resolveTraceId(sender, args);
        if (traceId < 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.console-no-trace",
                        "Console must specify a trace ID: /oshop admin trace log <id>")));
            } else if (getTraceIdFromHeld((Player) sender) < 0) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.no-trace-id",
                        "&7This item has no trace ID.")));
            } else {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.usage",
                        "&cUsage: /oshop admin trace <list|log|view> [...]")));
            }
            return true;
        }
        return showTraceLog(sender, traceId);
    }

    private boolean showTraceLog(CommandSender sender, long traceId) {
        try {
            TransactionRecord rec = plugin.getDatabaseManager().getTransactionRepository().queryById(traceId);
            if (rec == null) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.not-found", "&cNo transaction found with trace ID &e{id}&c.")
                        .replace("{id}", String.valueOf(traceId))));
                return true;
            }
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.header",
                "&e===== Trace ID: {id} =====", "{id}", String.valueOf(traceId))));
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.player",
                "&7Buyer: &f{player}", "{player}", rec.getPlayerName())));
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.item",
                "&7Item: &f{item}", "{item}", rec.getShopItemId())));
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.quantity",
                "&7Quantity: &f{count}x", "{count}", String.valueOf(rec.getQuantity()))));
            String curName = plugin.getCurrencyManager().getProvider(rec.getCurrencyId())
                .map(p -> p.getDisplayName()).orElse(rec.getCurrencyId());
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.price",
                "&7Price: &f{price} {currency}",
                "{price}", formatPrice(rec.getTotalPrice()), "{currency}", curName)));
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.log.time",
                "&7Time: &f{time}", "{time}", DATE_FORMAT.format(new Date(rec.getTimestamp())))));
        } catch (Exception e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.trace-lookup-error", "&cError looking up trace ID!")));
            plugin.getLogger().log(Level.WARNING, "Failed to lookup trace ID " + traceId, e);
        }
        return true;
    }

    private boolean handleTraceView(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("trace.console-no-trace",
                    "Console must specify a trace ID: /oshop admin trace log <id>")));
            return true;
        }
        long traceId = resolveTraceId(sender, args);
        if (traceId < 0) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                + plugin.getMessages().get("trace.no-trace-id",
                    "&7This item has no trace ID.")));
            return true;
        }
        try {
            TransactionRecord rec = plugin.getDatabaseManager().getTransactionRepository().queryById(traceId);
            if (rec == null) {
                player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.not-found", "&cNo transaction found with trace ID &e{id}&c.")
                        .replace("{id}", String.valueOf(traceId))));
                return true;
            }
            new com.ohtoai.opshop.gui.TraceViewGui(plugin, rec).open(player);
        } catch (Exception e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + plugin.getMessages().get("editor.trace-lookup-error", "&cError looking up trace ID!")));
            plugin.getLogger().log(Level.WARNING, "Failed to open trace view for ID " + traceId, e);
        }
        return true;
    }

    private void sendTraceHelp(CommandSender sender) {
        var m = plugin.getMessages();
        sender.sendMessage(plugin.getMessages().get("editor.trace-help-header", "&e===== Trace Commands ====="));
        sender.sendMessage(m.get("trace.help-list-item", "&e/oshop admin trace list item <item> &7— List trace records for an item"));
        sender.sendMessage(m.get("trace.help-list-player", "&e/oshop admin trace list player <player> &7— List trace records for a player"));
        sender.sendMessage(m.get("trace.help-log", "&e/oshop admin trace log [ID] &7— View trace record in chat"));
        sender.sendMessage(m.get("trace.help-view", "&e/oshop admin trace view [ID] &7— View trace record in GUI"));
    }

    // --- Help ---

    private void sendHelp(CommandSender sender) {
        var m = plugin.getMessages();
        sender.sendMessage(m.get("command.help-header", "&6=== OShop Commands ==="));
        sender.sendMessage(m.get("command.help-open", "&e/oshop &7— Open the shop"));
        sender.sendMessage(m.get("command.help-open-player", "&e/oshop open [player] &7— Open shop for a player"));
        sender.sendMessage(m.get("command.help-guild", "&e/oshop guild [name] &7— View guild shop"));
        sender.sendMessage(m.get("command.help-guild-all", "&e/oshop guild-all &7— List guild shops"));
        sender.sendMessage(m.get("command.help-admin", "&e/oshop admin &7— Admin commands"));
        sender.sendMessage(m.get("command.help-version", "&e/oshop version &7— Show version"));
    }

    private void sendAdminHelp(CommandSender sender) {
        var m = plugin.getMessages();
        sender.sendMessage(m.get("command.admin-header", "&6=== OShop Admin ==="));
        sender.sendMessage(m.get("command.admin-editor", "&e/oshop admin editor &7— Open GUI item editor"));
        sender.sendMessage(m.get("command.admin-guild", "&e/oshop admin guild [name] &7— Edit guild shop"));
        sender.sendMessage(m.get("command.admin-add", "&e/oshop admin add <id> &7— Add item from hand"));
        sender.sendMessage(m.get("command.admin-delete", "&e/oshop admin delete <id> &7— Delete item"));
        sender.sendMessage(m.get("command.admin-give", "&e/oshop admin give <plr> <id> <amt> &7— Give item"));
        sender.sendMessage(m.get("command.admin-currency", "&e/oshop admin currency &7— List currencies"));
        sender.sendMessage(m.get("command.admin-currency-add", "&e/oshop admin currency add <id> &7— Register held item as currency"));
        sender.sendMessage(m.get("command.admin-currency-delete", "&e/oshop admin currency delete <id> &7— Remove custom currency"));
        sender.sendMessage(m.get("command.admin-currency-give-take", "&e/oshop admin currency give|take <plr> <id> [amt] &7— Give/take currency"));
        sender.sendMessage(m.get("command.admin-currency-setglobal", "&e/oshop admin currency setglobal <id> <buy> <sell> &7— Set global price"));
        sender.sendMessage(m.get("command.admin-log", "&e/oshop admin log [player|item] [page] &7— View transactions in chat"));
        sender.sendMessage(m.get("command.admin-view", "&e/oshop admin view [player|item] &7— View transactions in GUI"));
        sender.sendMessage(m.get("command.admin-reload", "&e/oshop admin reload &7— Reload config"));
        sender.sendMessage(m.get("command.admin-resetlimit", "&e/oshop admin resetlimit <player> <id> &7— Reset player purchase limit"));
        sender.sendMessage(m.get("trace.help-list-item", "&e/oshop admin trace list item <item> &7— List trace records for an item"));
        sender.sendMessage(m.get("trace.help-list-player", "&e/oshop admin trace list player <player> &7— List trace records for a player"));
        sender.sendMessage(m.get("trace.help-log", "&e/oshop admin trace log [ID] &7— View trace record in chat"));
        sender.sendMessage(m.get("trace.help-view", "&e/oshop admin trace view [ID] &7— View trace record in GUI"));
    }

    // --- Tab Complete ---

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "guild", "guild-all", "admin", "version"), args[0]);
        }

        if (args[0].equalsIgnoreCase("guild")) {
            if (plugin.getHookManager().isGuildEnabled()) {
                return plugin.getHookManager().getGuildBridge().getAllGuilds().stream()
                    .map(g -> g.name() != null ? g.name() : String.valueOf(g.id()))
                    .filter(s -> s.toLowerCase().startsWith(
                        args.length >= 2 ? args[args.length - 1].toLowerCase() : ""))
                    .collect(Collectors.toList());
            }
            return List.of();
        }

        if (!args[0].equalsIgnoreCase("admin")) return List.of();

        if (args.length == 2) {
            return filter(List.of("editor", "guild", "add", "delete", "give", "reload", "currency", "log", "view", "resetlimit", "trace"), args[1]);
        }
        if (args[1].equalsIgnoreCase("guild")) {
            if (plugin.getHookManager().isGuildEnabled()) {
                return plugin.getHookManager().getGuildBridge().getAllGuilds().stream()
                    .map(g -> g.name() != null ? g.name() : String.valueOf(g.id()))
                    .filter(s -> s.toLowerCase().startsWith(
                        args.length >= 3 ? args[args.length - 1].toLowerCase() : ""))
                    .collect(Collectors.toList());
            }
            return List.of();
        }

        return switch (args[1].toLowerCase()) {
            case "add" -> args.length == 3 ? getHeldItemNameSuggestion(sender) : List.of();
            case "delete" -> args.length >= 3
                ? plugin.getShopManager().getAllItems().stream().map(ShopItem::getId).map(OpShopCommand::quoteIfNeeded).toList()
                : List.of();
            case "give" -> switch (args.length) {
                case 3 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                case 4 -> plugin.getShopManager().getAllItems().stream().map(ShopItem::getId).map(OpShopCommand::quoteIfNeeded).toList();
                default -> List.of();
            };
            case "resetlimit" -> switch (args.length) {
                case 3 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                case 4 -> plugin.getShopManager().getAllItems().stream().map(ShopItem::getId).map(OpShopCommand::quoteIfNeeded).toList();
                default -> List.of();
            };
            case "currency" -> {
                if (args.length == 3) {
                    yield filter(List.of("add", "delete", "give", "take", "setglobal"), args[2]);
                }
                if (args.length == 4 && (args[2].equals("give") || args[2].equals("take"))) {
                    yield Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                }
                if (args.length == 5 && (args[2].equals("give") || args[2].equals("take"))) {
                    yield plugin.getCurrencyManager().getAvailableCurrencyIds().stream()
                        .map(OpShopCommand::quoteIfNeeded)
                        .collect(Collectors.toList());
                }
                if (args.length == 4 && args[2].equals("add")) {
                    yield getHeldItemNameSuggestion(sender);
                }
                if (args.length == 4 && args[2].equals("setglobal")) {
                    // Only VAULT and PLAYER_POINTS — custom items don't use global defaults
                    yield plugin.getCurrencyManager().getAllProviders().stream()
                        .filter(p -> p.getType() != com.ohtoai.opshop.currency.CurrencyType.CUSTOM_ITEM)
                        .map(com.ohtoai.opshop.currency.CurrencyProvider::getId)
                        .map(OpShopCommand::quoteIfNeeded)
                        .collect(Collectors.toList());
                }
                if (args.length == 4 && args[2].equals("delete")) {
                    yield plugin.getCurrencyManager().getDefinitions().entrySet().stream()
                        .filter(e -> "CUSTOM_ITEM".equalsIgnoreCase(e.getValue().type()))
                        .map(Map.Entry::getKey)
                        .map(OpShopCommand::quoteIfNeeded)
                        .collect(Collectors.toList());
                }
                yield List.of();
            }
            case "log" -> {
                if (args.length == 3) {
                    yield filter(List.of("player", "item"), args[2]);
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("player")) {
                    yield Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                        .collect(Collectors.toList());
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("item")) {
                    yield plugin.getShopManager().getAllItems().stream().map(ShopItem::getId)
                        .map(OpShopCommand::quoteIfNeeded)
                        .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                        .collect(Collectors.toList());
                }
                yield List.of();
            }
            case "view" -> {
                if (args.length == 3) {
                    yield filter(List.of("player", "item"), args[2]);
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("player")) {
                    yield Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                        .collect(Collectors.toList());
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("item")) {
                    yield plugin.getShopManager().getAllItems().stream().map(ShopItem::getId)
                        .map(OpShopCommand::quoteIfNeeded)
                        .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                        .collect(Collectors.toList());
                }
                yield List.of();
            }
            case "trace" -> {
                if (args.length == 3) {
                    yield filter(List.of("list", "log", "view"), args[2]);
                }
                if (args.length == 4 && args[2].equalsIgnoreCase("list")) {
                    yield filter(List.of("item", "player"), args[3]);
                }
                if (args.length == 5 && args[2].equalsIgnoreCase("list") && args[3].equalsIgnoreCase("item")) {
                    yield plugin.getShopManager().getTraceEnabledItems().stream()
                        .map(ShopItem::getId).map(OpShopCommand::quoteIfNeeded)
                        .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
                }
                if (args.length == 5 && args[2].equalsIgnoreCase("list") && args[3].equalsIgnoreCase("player")) {
                    yield Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    /** Suggest the held item's display name (plain text, no formatting) as ID. */
    private List<String> getHeldItemNameSuggestion(CommandSender sender) {
        if (sender instanceof Player player) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() != Material.AIR) {
                String name;
                if (held.hasItemMeta() && held.getItemMeta().hasDisplayName()) {
                    // Use the item's custom display name (plain text)
                    name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(held.getItemMeta().displayName());
                } else {
                    // Format material name: DIAMOND_SWORD → Diamond Sword
                    name = formatMaterialName(held.getType().name());
                }
                return name.isEmpty() ? List.of() : List.of(quoteIfNeeded(name));
            }
        }
        return List.of();
    }

    /** Convert a Material enum name like "OAK_WOOD" to human-readable "Oak Wood". */
    private static String formatMaterialName(String materialName) {
        if (materialName == null || materialName.isEmpty()) return materialName;
        String[] parts = materialName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
            .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
            .collect(Collectors.toList());
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }

    /** Join args from start (inclusive) to end (exclusive) with a space. */
    private static String joinArgs(String[] args, int start, int end) {
        if (start >= end) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, end));
    }

    /** Strip matching surrounding quotes from a joined ID (Bukkit may not strip them). */
    private static String cleanId(String id) {
        if (id == null || id.isEmpty()) return id;
        if (id.length() >= 2) {
            char first = id.charAt(0), last = id.charAt(id.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return id.substring(1, id.length() - 1);
            }
        }
        return id;
    }

    private static boolean isNumeric(String s) {
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
    }

    /** Wrap a suggestion in quotes if it contains spaces, for safe tab completion. */
    private static String quoteIfNeeded(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }
}
