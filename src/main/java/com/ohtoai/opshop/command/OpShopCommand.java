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

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }
        if (args.length == 0) { sendAdminHelp(sender); return true; }

        return switch (args[0].toLowerCase()) {
            case "editor" -> handleEditor(sender);
            case "add" -> handleAdd(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "give" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "currency" -> handleCurrency(sender, Arrays.copyOfRange(args, 1, args.length));
            case "resetlimit" -> handleResetLimit(sender, args);
            case "logs" -> handleLogs(sender, Arrays.copyOfRange(args, 1, args.length));
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

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("command.console-no-add", "Console cannot add items. Use the editor."));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cHold an item in your hand to use as the product!"));
            return true;
        }

        // ID: join all remaining args (supports spaces; page assigned automatically)
        if (args.length < 2) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin add <id>"));
            return true;
        }
        String id = cleanId(joinArgs(args, 1, args.length));
        if (plugin.getShopManager().getItem(id).isPresent()) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cItem '" + id + "' already exists!"));
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
                + "&aCreated shop item: &e" + id));
            // Open editor at the correct page
            var editor = new ShopEditorGui(plugin, plugin.getShopManager(), plugin.getCurrencyManager());
            editor.open(player, targetPage);
        } catch (SQLException e) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cFailed to create item!"));
            plugin.getLogger().log(Level.WARNING, "Failed to add item " + id, e);
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin delete <id>"));
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
                + "&aDeleted item: &e" + id));
        } catch (SQLException e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cFailed to delete item!"));
        }
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin give <player> <itemId> <amount>"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cPlayer not found."));
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
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cInvalid amount."));
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
            + "&aGave " + amount + "x " + itemId + " to " + target.getName()));
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
                + "&cUsage: /oshop admin currency add <id>"));
            return true;
        }

        String id = cleanId(joinArgs(args, 1, args.length));

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cHold the currency item in your hand!"));
            return true;
        }

        boolean ok = plugin.getCurrencyManager().addCustomCurrency(id, held);
        if (ok) {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&aRegistered custom currency: &e" + id));
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&7Use &e/oshop admin currency setglobal " + id + " <buy> <sell> &7to set prices."));
        } else {
            player.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cCurrency '" + id + "' already exists!"));
        }
        return true;
    }

    private boolean handleCurrencyDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin currency delete <id>"));
            return true;
        }

        String currencyId = cleanId(joinArgs(args, 1, args.length));
        boolean ok = plugin.getCurrencyManager().deleteCurrency(currencyId);
        if (ok) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&aDeleted currency: &e" + currencyId));
        } else {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cCannot delete '" + currencyId + "'. Only custom-item currencies can be deleted."));
        }
        return true;
    }

    private boolean handleCurrencyGiveTake(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin currency <give|take> <player> <currencyId> [amount]"));
            return true;
        }

        String action = args[0].toLowerCase();
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cPlayer not found."));
            return true;
        }

        String currencyId = cleanId(joinArgs(args, 2, args.length > 3 && isNumeric(args[args.length - 1]) ? args.length - 1 : args.length));
        var providerOpt = plugin.getCurrencyManager().getProvider(currencyId);
        if (providerOpt.isEmpty()) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cCurrency not found: " + currencyId));
            return true;
        }

        double amount = 1.0;
        if (args.length > 3 && isNumeric(args[args.length - 1])) {
            try { amount = Double.parseDouble(args[args.length - 1]); }
            catch (NumberFormatException e) {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cInvalid amount."));
                return true;
            }
        }
        CurrencyProvider provider = providerOpt.get();

        if (action.equals("give")) {
            provider.deposit(target, amount);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&aGave " + provider.format(amount) + " to " + target.getName()));
        } else {
            provider.withdraw(target, amount);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&aTook " + provider.format(amount) + " from " + target.getName()));
        }
        return true;
    }

    private boolean handleCurrencySetGlobal(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cUsage: /oshop admin currency setglobal <currencyId> <buyPrice> <sellPrice>"));
            return true;
        }

        // Last two args are buy/sell prices; everything before them is the currency ID
        String currencyId = cleanId(joinArgs(args, 1, args.length - 2));
        var provider = plugin.getCurrencyManager().getProvider(currencyId);
        if (provider.isEmpty()) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cCurrency not found: " + currencyId));
            return true;
        }
        // Only VAULT and PLAYER_POINTS can have global defaults; custom items are per-item
        var type = provider.get().getType();
        if (type == com.ohtoai.opshop.currency.CurrencyType.CUSTOM_ITEM) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&cCannot set global default for custom-item currency '" + currencyId
                + "'. Custom currencies are set per-item in the editor."));
            return true;
        }

        try {
            double buy = Double.parseDouble(args[args.length - 2]);
            double sell = Double.parseDouble(args[args.length - 1]);
            plugin.getCurrencyManager().setGlobalDefault(currencyId, buy, sell);
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + "&aGlobal default for &e" + currencyId
                + " &aset: buy=" + formatPrice(buy) + " sell=" + formatPrice(sell)));
        } catch (NumberFormatException e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cInvalid price."));
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
            if (oldCount > 0) msg += " &7(refunded " + oldCount + ")";
            sender.sendMessage(Messages.colorize(msg));
        } catch (SQLException e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix")
                + plugin.getMessages().get("error-reading-logs", "&cError resetting limit!")));
            plugin.getLogger().log(Level.WARNING, "Failed to reset player purchase limit", e);
        }
        return true;
    }

    // --- Logs ---

    private boolean handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opshop.admin.logs")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }

        // GUI mode
        if (args.length > 0 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getMessages().get("command.console-no-log-gui", "Console cannot open the log GUI."));
                return true;
            }
            new com.ohtoai.opshop.gui.TransactionLogGui(plugin).open(player);
            return true;
        }

        // CUI mode — parse optional page number
        int page = 0;
        int pageSize = 10;
        if (args.length > 0) {
            try { page = Math.max(0, Integer.parseInt(args[0]) - 1); }
            catch (NumberFormatException ignored) {}
        }

        TransactionRepository repo = plugin.getDatabaseManager().getTransactionRepository();
        try {
            var total = repo.count();
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            if (page >= totalPages) page = totalPages - 1;
            var records = repo.queryRecent(page, pageSize);
            sendLogPage(sender, records, page + 1, totalPages);
        } catch (Exception e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().get("prefix") + "&cError reading logs!"));
            plugin.getLogger().log(Level.WARNING, "Failed to read logs", e);
        }
        return true;
    }

    private void sendLogPage(CommandSender sender, List<TransactionRecord> records, int page, int totalPages) {
        sender.sendMessage(plugin.getMessages().getPrefix()
            + plugin.getMessages().get("gui.log.log-header", "&eTransaction Log (Page {page}/{total})",
                "{page}", String.valueOf(page), "{total}", String.valueOf(totalPages)));
        if (records.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("gui.log.no-records", "&7No transactions recorded yet."));
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

        try {
            if ("item".equals(subType)) {
                String itemId = cleanId(value);
                var total = plugin.getDatabaseManager().getTransactionRepository().countByItem(itemId);
                int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
                if (page >= totalPages) page = totalPages - 1;
                var records = plugin.getDatabaseManager().getTransactionRepository().queryByItem(itemId, page, pageSize);
                if (records.isEmpty()) {
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
                var total = plugin.getDatabaseManager().getTransactionRepository().countByPlayer(target.getUniqueId());
                int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
                if (page >= totalPages) page = totalPages - 1;
                var records = plugin.getDatabaseManager().getTransactionRepository().queryByPlayer(target.getUniqueId(), page, pageSize);
                if (records.isEmpty()) {
                    sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("trace.list.no-records-player", "&7No trace records for this player.")));
                    return true;
                }
                sender.sendMessage(Messages.colorize(plugin.getMessages().get("trace.list.header-player",
                    "&e===== Trace records for {player} (Page {page}/{total}) =====",
                    "{player}", target.getName(), "{page}", String.valueOf(page + 1), "{total}", String.valueOf(totalPages))));
                for (TransactionRecord rec : records) {
                    sendTraceListRecord(sender, rec);
                }
            } else {
                sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("trace.usage", "&cUsage: /oshop admin trace list <item|player> <value> [page]")));
            }
        } catch (Exception e) {
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cError querying trace records!"));
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
            sender.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cError looking up trace ID!"));
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
            player.sendMessage(Messages.colorize(plugin.getMessages().getPrefix() + "&cError looking up trace ID!"));
            plugin.getLogger().log(Level.WARNING, "Failed to open trace view for ID " + traceId, e);
        }
        return true;
    }

    private void sendTraceHelp(CommandSender sender) {
        var m = plugin.getMessages();
        sender.sendMessage(Messages.colorize("&e===== Trace Commands ====="));
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
        sender.sendMessage(m.get("command.help-admin", "&e/oshop admin &7— Admin commands"));
        sender.sendMessage(m.get("command.help-version", "&e/oshop version &7— Show version"));
    }

    private void sendAdminHelp(CommandSender sender) {
        var m = plugin.getMessages();
        sender.sendMessage(m.get("command.admin-header", "&6=== OShop Admin ==="));
        sender.sendMessage(m.get("command.admin-editor", "&e/oshop admin editor &7— Open GUI item editor"));
        sender.sendMessage(m.get("command.admin-add", "&e/oshop admin add <id> &7— Add item from hand"));
        sender.sendMessage(m.get("command.admin-delete", "&e/oshop admin delete <id> &7— Delete item"));
        sender.sendMessage(m.get("command.admin-give", "&e/oshop admin give <plr> <id> <amt> &7— Give item"));
        sender.sendMessage(m.get("command.admin-currency", "&e/oshop admin currency &7— List currencies"));
        sender.sendMessage(m.get("command.admin-currency-add", "&e/oshop admin currency add <id> &7— Register held item as currency"));
        sender.sendMessage(m.get("command.admin-currency-delete", "&e/oshop admin currency delete <id> &7— Remove custom currency"));
        sender.sendMessage(m.get("command.admin-currency-give-take", "&e/oshop admin currency give|take <plr> <id> [amt] &7— Give/take currency"));
        sender.sendMessage(m.get("command.admin-currency-setglobal", "&e/oshop admin currency setglobal <id> <buy> <sell> &7— Set global price"));
        sender.sendMessage(m.get("command.admin-logs", "&e/oshop admin logs [page] &7— View transactions in chat"));
        sender.sendMessage(m.get("command.admin-logs-gui", "&e/oshop admin logs gui &7— View transactions in GUI"));
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
            return filter(List.of("open", "admin", "version"), args[0]);
        }

        if (!args[0].equalsIgnoreCase("admin")) return List.of();

        if (args.length == 2) {
            return filter(List.of("editor", "add", "delete", "give", "reload", "currency", "logs", "resetlimit", "trace"), args[1]);
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
                    yield plugin.getCurrencyManager().getAvailableCurrencyIds();
                }
                if (args.length == 4 && args[2].equals("add")) {
                    yield getHeldItemNameSuggestion(sender);
                }
                if (args.length == 4 && args[2].equals("setglobal")) {
                    // Only VAULT and PLAYER_POINTS — custom items don't use global defaults
                    yield plugin.getCurrencyManager().getAllProviders().stream()
                        .filter(p -> p.getType() != com.ohtoai.opshop.currency.CurrencyType.CUSTOM_ITEM)
                        .map(com.ohtoai.opshop.currency.CurrencyProvider::getId)
                        .collect(Collectors.toList());
                }
                if (args.length == 4 && args[2].equals("delete")) {
                    yield plugin.getCurrencyManager().getDefinitions().entrySet().stream()
                        .filter(e -> "CUSTOM_ITEM".equalsIgnoreCase(e.getValue().type()))
                        .map(Map.Entry::getKey)
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
                    name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(held.getItemMeta().displayName());
                } else {
                    name = held.getType().name().toLowerCase();
                }
                return name.isEmpty() ? List.of() : List.of(name);
            }
        }
        return List.of();
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
