# OpShop

A multi-currency virtual shop plugin for Paper Minecraft servers. Players can buy and sell items using Vault economy, PlayerPoints, or custom item-based currencies — all through an intuitive GUI.

## Features

| Feature | Detail |
|---|---|
| **Multi-currency** | Vault (economy), PlayerPoints, and custom item-based currencies — all simultaneously |
| **GUI-driven** | Full shop browsing, item detail, purchase, and sell interfaces |
| **Shop editor** | In-game GUI to create, edit, and arrange shop items |
| **Transaction tracking** | Chat-based log viewer (`/oshop admin log`) and GUI viewer (`/oshop admin view`) with player/item filtering and pagination |
| **Trace ID system** | Generate unique trace IDs per purchased item for accountability — view trace logs in chat or GUI |
| **Purchase limits** | Per-player and global stock limits, cooldowns, and refund-on-limit support |
| **PlaceholderAPI** | `%oshop_*%` placeholders for currency balances and shop stats |
| **Configurable messages** | All messages customizable in `config.yml` with full color code support |
| **Database** | SQLite (default) or MySQL via HikariCP connection pool |
| **Localization** | Built-in `zh_CN` locale support |

## Requirements

- **Paper 1.20.6+** (or compatible fork)
- **Java 21**
- **Vault** (required dependency — provides economy integration)
- **PlayerPoints** (optional — token currency support)
- **PlaceholderAPI** (optional — `%oshop_*%` placeholders)

## Quick Start

1. Drop `OpShop-<version>.jar` into your server's `plugins/` folder.
2. Start (or restart) the server — OpShop will generate `config.yml` and the SQLite database.
3. Edit `plugins/OpShop/config.yml` to configure currencies and messages.
4. Run `/oshop admin editor` to open the shop editor and add items.
5. Use `/oshop admin reload` to reload configuration without restarting.

## Commands

### Player Commands

| Command | Description |
|---|---|
| `/oshop` | Open the shop GUI |
| `/oshop version` | Show plugin version |

### Admin Commands

| Command | Description |
|---|---|
| `/oshop admin editor` | Open the GUI shop editor |
| `/oshop admin add <id>` | Add held item to shop |
| `/oshop admin delete <id>` | Remove item from shop |
| `/oshop admin give <player> <id> <amount>` | Give shop item to a player |
| `/oshop admin reload` | Reload configuration |
| `/oshop admin resetlimit <player> <id>` | Reset a player's purchase limit |
| `/oshop admin log [player\|item] [page]` | View transaction log in chat |
| `/oshop admin view [player\|item]` | View transaction log in GUI |
| `/oshop admin trace list <item\|player> <value> [page]` | List trace records |
| `/oshop admin trace log [ID]` | View trace record in chat |
| `/oshop admin trace view [ID]` | View trace record in GUI |
| `/oshop admin currency` | List all currencies |
| `/oshop admin currency add <id>` | Register held item as custom currency |
| `/oshop admin currency delete <id>` | Remove custom currency |
| `/oshop admin currency give\|take <player> <id> [amount]` | Give/take currency |
| `/oshop admin currency setglobal <id> <buy> <sell>` | Set global default price |

### Aliases

`/os`, `/shop`, `/opshop`

## Permissions

| Permission | Default | Description |
|---|---|---|
| `opshop.use` | `true` | Open and use the shop (buy & sell) |
| `opshop.admin` | `op` | Full admin access (includes `opshop.use`) |
| `opshop.admin.editor` | `op` | Access the shop editor GUI |
| `opshop.admin.currency` | `op` | Manage currencies |
| `opshop.admin.logs` | `op` | View transaction logs |
| `opshop.buy.*` | `true` | Buy all items |
| `opshop.sell.*` | `true` | Sell all items |

## Supported Currencies

| Type | Provider | Description |
|---|---|---|
| **Vault** | Vault economy | Server economy (e.g., EssentialsX) |
| **PlayerPoints** | PlayerPoints | Token/points currency |
| **Custom Item** | OpShop (built-in) | Any item as currency (e.g., gems, tickets) |

Custom item currencies are registered with `/oshop admin currency add <id>` while holding the item. Their definition is persisted in the database.

## PlaceholderAPI

When PlaceholderAPI is installed, the following placeholders are available:

- `%oshop_balance_<currencyId>%` — Player's balance for a specific currency
- `%oshop_items_total%` — Total number of shop items
- `%oshop_items_on_page_<page>%` — Item count on a specific page

## Configuration

See [config.yml](src/main/resources/config.yml) for the default configuration with comments.

Key sections:
- **database** — SQLite or MySQL connection settings
- **global-defaults** — Default buy/sell prices applied to new items
- **currencies** — Currency definitions (Vault, PlayerPoints, custom items)
- **gui** — Shop GUI title, rows, and filler material
- **messages** — All plugin messages with color code support
- **locale** — `en_US` or `zh_CN`

## Project Structure

```
src/main/java/com/ohtoai/opshop/
├── OpShopPlugin.java              # Main plugin class
├── command/
│   └── OpShopCommand.java         # Command handler + tab completer
├── config/
│   ├── PluginConfig.java          # config.yml loader
│   └── Messages.java              # Message provider
├── currency/
│   ├── CurrencyManager.java       # Currency registry & lifecycle
│   ├── CurrencyProvider.java      # Abstract currency provider
│   ├── CurrencyType.java          # VAULT / PLAYER_POINTS / CUSTOM_ITEM
│   └── provider/
│       ├── VaultCurrencyProvider.java
│       ├── PlayerPointsCurrencyProvider.java
│       └── CustomItemCurrencyProvider.java
├── database/
│   ├── DatabaseManager.java       # SQLite/MySQL via HikariCP
│   ├── TransactionRecord.java     # Transaction data object
│   └── TransactionRepository.java # Transaction queries
├── gui/
│   ├── ShopMainGui.java           # Paginated item grid
│   ├── ItemDetailGui.java         # Buy/sell detail view
│   ├── PurchaseGui.java           # Buy confirmation
│   ├── SellGui.java               # Sell confirmation
│   ├── ShopEditorGui.java         # Admin item editor
│   ├── TransactionLogGui.java     # GUI log viewer
│   ├── TraceViewGui.java          # GUI trace viewer
│   ├── GuiHolder.java             # GUI contract interface
│   ├── GuiListener.java           # Inventory event handler
│   ├── GuiUtils.java              # GUI helper utilities
│   └── Icon.java                  # Icon builder
├── integration/
│   ├── HookManager.java           # Plugin hook detection
│   ├── VaultHook.java             # Vault API wrapper
│   ├── PlayerPointsHook.java      # PlayerPoints API wrapper
│   └── PlaceholderApiHook.java    # PlaceholderAPI wrapper
├── item/
│   ├── ItemSerializer.java        # ItemStack ↔ byte[] (NBT)
│   └── PlaceholderReplacer.java   # %player_name% etc. in items
├── listener/
│   └── PlayerListener.java        # Player event handlers
├── placeholder/
│   └── OpShopExpansion.java       # PlaceholderAPI expansion
└── shop/
    ├── ShopItem.java              # Shop item data model
    ├── ShopManager.java           # Item registry & persistence
    ├── TransactionProcessor.java  # Buy/sell transaction logic
    └── PriceConfig.java           # Price data object

src/main/resources/
├── plugin.yml                     # Plugin descriptor
└── config.yml                     # Default configuration
```

## Building from Source

```bash
./gradlew build
```

Output JAR: `build/libs/OpShop-<version>.jar`

### Gradle Details

- **Build system**: Gradle 8.10+ (Kotlin DSL)
- **Java**: 21 (toolchain)
- **Packaging**: Shadow JAR (relocates HikariCP and MySQL connector)
- **CI**: GitHub Actions — builds on push/PR, uploads artifact

## Dependencies

| Dependency | Scope |
|---|---|
| Paper API 1.20.6 | `compileOnly` |
| VaultAPI | `compileOnly` |
| PlayerPoints | `compileOnly` |
| PlaceholderAPI | `compileOnly` |
| HikariCP 5.1.0 | `implementation` (shaded) |
| MySQL Connector/J 8.3.0 | `implementation` (shaded) |

## License

All rights reserved — OhtoAi
